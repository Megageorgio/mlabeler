package mlabeler.core.oto

import kotlinx.serialization.Serializable
import mlabeler.core.format.OtoAbsolute
import mlabeler.core.format.OtoEntry

@Serializable
enum class AliasScript { Auto, Kana, Romaji, Both }

@Serializable
enum class OtoEntryKind { Initial, CV, VC, VV, VCV, Ending }

/** Optional corrections to the existing generator, in ms. Zero corrections preserve its output. */
@Serializable
data class OtoRule(val kind: OtoEntryKind, val offset: Double = 0.0, val overlap: Double = 0.0,
                   val preutterance: Double = 0.0, val consonant: Double = 0.0, val cutoff: Double = 0.0) {
    fun apply(e: OtoEntry, duration: Double): OtoEntry {
        if (offset == 0.0 && overlap == 0.0 && preutterance == 0.0 && consonant == 0.0 && cutoff == 0.0) return e
        require(duration.isFinite() && duration > 0)
        val a = e.absolute(duration)
        val left = (a.left + offset).coerceIn(0.0, (duration - 1.0).coerceAtLeast(0.0))
        val right = (a.right + cutoff).coerceIn((left + 1.0).coerceAtMost(duration), duration)
        val pre = (a.preutterance + preutterance).coerceIn(left, right)
        return OtoEntry.fromAbsolute(e.sample, e.alias,
            OtoAbsolute(left, (a.overlap + overlap).coerceIn(left, right), pre,
                (a.consonant + consonant).coerceIn(pre, right), right), duration, negativeCutoff = true)
    }
}

object OtoRules {
    fun aliases(alias: String, script: AliasScript): List<String> = when (script) {
        AliasScript.Auto -> listOf(alias)
        AliasScript.Kana -> listOf(Kana.aliasToKana(alias))
        AliasScript.Romaji -> listOf(Kana.aliasToRomaji(alias))
        AliasScript.Both -> listOf(Kana.aliasToKana(alias), Kana.aliasToRomaji(alias)).distinct()
    }

    fun kind(alias: String, headPrefix: String = "- ", endAlias: String = "-"): OtoEntryKind {
        if (headPrefix.isNotEmpty() && alias.startsWith(headPrefix)) return OtoEntryKind.Initial
        if (endAlias.isNotEmpty() && alias.endsWith(" $endAlias")) return OtoEntryKind.Ending
        if (' ' !in alias) return OtoEntryKind.CV
        if (alias.substringAfterLast(' ') == "n") return OtoEntryKind.VC
        val last = Kana.aliasToRomaji(alias).substringAfterLast(' ')
        val (c, v) = Syllables.parts(last)
        return when { v.isEmpty() -> OtoEntryKind.VC; c.isEmpty() -> OtoEntryKind.VV; else -> OtoEntryKind.VCV }
    }
}

/** A pure, reviewable change set. Excluded removals/replacements leave the original entry intact. */
class OtoPlan(val before: List<OtoEntry>, generated: List<OtoEntry>, samples: Set<String>, replace: Boolean) {
    enum class Action { Add, Replace, Remove, Unchanged }
    data class Row(val action: Action, val beforeIndex: Int?, val after: OtoEntry?, val conflict: Boolean, val entry: OtoEntry)
    val rows: List<Row>
    init {
        fun key(e: OtoEntry) = e.sample.lowercase() to e.alias
        val pending = mutableMapOf<Pair<String, String>, ArrayDeque<Int>>()
        generated.forEachIndexed { i, e -> pending.getOrPut(key(e)) { ArrayDeque() }.addLast(i) }
        val consumed = BooleanArray(generated.size)
        val scope = samples.map { it.lowercase() }.toSet()
        val result = mutableListOf<Row>()
        for ((i, old) in before.withIndex()) {
            if (old.sample.lowercase() !in scope) continue
            val k = pending[key(old)]?.removeFirstOrNull()
            val next = k?.let { consumed[it] = true; generated[it] }
            val action = when { next != null && (old == next || !replace) -> Action.Unchanged
                next != null -> Action.Replace; replace -> Action.Remove; else -> Action.Unchanged }
            result += Row(action, i, next, false, next ?: old)
        }
        for ((i, e) in generated.withIndex()) if (!consumed[i]) result += Row(Action.Add, null, e, false, e)
        val counts = generated.groupingBy { it.alias }.eachCount()
        val oldSamples = before.groupBy { it.alias }.mapValues { (_, es) -> es.map { it.sample.lowercase() }.toSet() }
        rows = result.map { r ->
            // Same aliases in other samples can be intentional takes; make them explicit, never silently choose one.
            r.copy(conflict = r.action != Action.Remove &&
                (oldSamples[r.entry.alias].orEmpty().any { it != r.entry.sample.lowercase() } ||
                    (counts[r.entry.alias] ?: 0) > 1))
        }
    }
    fun apply(selected: Set<Int>): List<OtoEntry> {
        val edits = rows.withIndex().filter { it.index in selected }.map { it.value }
        val byIndex = edits.filter { it.beforeIndex != null }.associateBy { it.beforeIndex }
        val additions = edits.filter { it.action == Action.Add }.mapNotNull { it.after }.groupBy { it.sample.lowercase() }.toMutableMap()
        val last = before.withIndex().associate { it.value.sample.lowercase() to it.index }
        val out = mutableListOf<OtoEntry>()
        for ((i, e) in before.withIndex()) {
            val row = byIndex[i]
            when (row?.action) { Action.Replace -> row.after?.let { out += it }; Action.Remove -> Unit; else -> out += e }
            if (last[e.sample.lowercase()] == i) additions.remove(e.sample.lowercase())?.let { out += it }
        }
        out += additions.values.flatten()
        return out
    }
}

/** Alias conventions, not an emulation of an OpenUtau phonemizer. Prefix/suffix select one pitch/colour group. */
object OtoReadiness {
    enum class Phonemizer { FullCoverage, OpenUtauJaVcv, OpenUtauJaCvvc }
    data class Issue(val alias: String, val sample: String?, val kind: Kind)
    enum class Kind { MissingAlias, FallbackAlias, DuplicateAlias, MissingSample, InvalidTiming }
    fun check(entries: List<OtoEntry>, syllables: List<Syllable>, style: RecStyle,
              samples: Set<String>, prefix: String = "", suffix: String = "", phonemizer: Phonemizer = Phonemizer.FullCoverage): List<Issue> {
        require(style in setOf(RecStyle.CV, RecStyle.VCV, RecStyle.CVVC))
        val scoped = entries.filter { it.alias.startsWith(prefix) && it.alias.endsWith(suffix) && it.alias.length >= prefix.length + suffix.length }
        val names = scoped.map { it.alias.removePrefix(prefix).removeSuffix(suffix) }.toSet()
        val canonical = names.map { Kana.aliasToRomaji(it) }.toSet()
        val syl = syllables.distinctBy { Kana.aliasToRomaji(it.text) }
        val vowels = syl.map { it.vowel }.filter { it.isNotEmpty() }.distinct()
        val expected = linkedSetOf<String>()
        for (s in syl) {
            val name = Kana.aliasToRomaji(s.text)
            when (style) {
                RecStyle.CV -> expected += name
                RecStyle.VCV -> { expected += "- $name"; vowels.forEach { expected += "$it $name" } }
                RecStyle.CVVC -> {
                    expected += name; expected += "- $name"
                    vowels.forEach { expected += "$it ${s.consonant.ifEmpty { name }}" }
                }
                else -> Unit
            }
        }
        val out = if (phonemizer == Phonemizer.FullCoverage)
            expected.filter { it !in canonical }.map { Issue(prefix + Kana.aliasToKana(it) + suffix, null, Kind.MissingAlias) }.toMutableList()
        else phonemizerIssues(names, syl, phonemizer).map { it.copy(alias = prefix + it.alias + suffix) }.toMutableList()
        for ((alias, es) in scoped.groupBy { it.alias }) if (es.size > 1) out += Issue(alias, es.joinToString { it.sample }, Kind.DuplicateAlias)
        val available = samples.map { it.lowercase() }.toSet()
        for (e in scoped) {
            if (e.sample.lowercase() !in available) out += Issue(e.alias, e.sample, Kind.MissingSample)
            if (listOf(e.offset, e.consonant, e.cutoff, e.preutterance, e.overlap).any { !it.isFinite() } ||
                e.offset < 0 || e.consonant < 0 || e.preutterance < 0 || e.consonant < e.preutterance)
                out += Issue(e.alias, e.sample, Kind.InvalidTiming)
        }
        return out
    }

    /** Static alias lookup for the two legacy plugins, without tone/colour mapping or timing simulation.
     * Source: OpenUtau.Plugin.Builtin/Japanese{VCV,CVVC}Phonemizer.cs (checked 2026-10-23).
     * JA CVVC uses palatal VC names even before plain き/ぎ/に/ひ/び/ぴ/み/り.
     */
    private fun phonemizerIssues(names: Set<String>, syl: List<Syllable>, mode: Phonemizer): List<Issue> {
        val out = linkedMapOf<String, Issue>()
        fun requireAlias(primary: String, fallbacks: List<String> = emptyList()) {
            if (primary in names) return
            val fallback = fallbacks.firstOrNull { it in names }
            out[primary] = Issue(primary, fallback, if (fallback != null) Kind.FallbackAlias else Kind.MissingAlias)
        }
        val vowels = syl.map { it.vowel }.filter { it.isNotEmpty() }.distinct()
        val substitute = mapOf("ty" to "t", "ch" to "t", "ts" to "t", "j" to "d", "dy" to "d", "gy" to "g", "ky" to "k",
            "py" to "p", "ny" to "n", "ry" to "r", "my" to "m", "hy" to "h", "f" to "h", "by" to "b", "v" to "b", "dz" to "z")
        val specials = mapOf('き' to "ky", 'ぎ' to "gy", 'し' to "sh", 'じ' to "j", 'ち' to "ch", 'ぢ' to "j", 'つ' to "ts",
            'づ' to "dz", 'に' to "ny", 'ひ' to "hy", 'ふ' to "f", 'び' to "by", 'ぴ' to "py", 'み' to "my", 'り' to "ry")
        for (s in syl) {
            val kana = Kana.aliasToKana(s.text)
            requireAlias("- $kana", listOf(kana))
            if (mode == Phonemizer.OpenUtauJaVcv) {
                for (v in vowels) requireAlias("$v $kana", listOf("* $kana", kana, "- $kana"))
            } else {
                requireAlias("* $kana", listOf(kana))
                if (kana in setOf("あ", "い", "う", "え", "お", "を", "ん", "ン")) {
                    for (v in vowels) requireAlias("$v $kana", listOf("* $kana", kana))
                } else {
                    val consonant = specials[kana.firstOrNull()] ?: s.consonant
                    if (consonant.isNotEmpty()) for (v in vowels) {
                        val vc = "$v $consonant"
                        // Missing VC is skipped by the plugin; report that loss even when a substitute exists.
                        requireAlias(vc, listOfNotNull(substitute[consonant]?.let { "$v $it" }))
                    }
                }
            }
        }
        return out.values.toList()
    }
}
