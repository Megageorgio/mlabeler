package mlabeler.core.oto

import mlabeler.core.dsp.Pitch
import mlabeler.core.format.OtoAbsolute
import mlabeler.core.format.OtoEntry
import mlabeler.core.model.IntervalTier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One sung syllable: consonant (may be empty) + vowel; alias text as it should appear in oto.ini. */
data class Syllable(val text: String, val consonant: String, val vowel: String)

/** Where a syllable is in the recording, seconds: consonant start, vowel start, vowel end. */
data class SyllableTiming(val syllable: Syllable, val cStart: Double, val vStart: Double, val vEnd: Double)

enum class RecStyle { Auto, CV, VCV, CVVC,
    /** Russian-style CVC: "-b", "-ba", "ab", "ba", "ab-", "b" from samples like "babab" (no spaces in aliases). */
    CVC,
    /** English ARPAsing: "- m", "m ah", "ah", "ah s", "s -" from samples named by ARPAbet phonemes ("m_ah_s"). */
    ARPA }

data class AutoOtoSettings(
    val style: RecStyle = RecStyle.Auto,
    /** Beats per minute of the recording; 0 = find syllables from the audio only. */
    val bpm: Double = 0.0,
    /** Offset this far before the overlap, ms (syllables with a consonant). */
    val leftMarginMs: Double = 140.0,
    /** Offset this far before the overlap, ms (syllables without a consonant). */
    val vowelLeftMs: Double = 80.0,
    /** Overlap this far before the consonant, inside the vowel before it, ms (VCV). */
    val overlapBeforeMs: Double = 55.0,
    /** Consonant (fixed) part reaches this far into the vowel, ms. */
    val fixedMs: Double = 100.0,
    /** Overlap for syllables without a consonant, ms before the vowel. */
    val vowelOverlapMs: Double = 25.0,
    /** End of an entry this far before the next consonant, ms. */
    val endMarginMs: Double = 150.0,
    /** Alias of the vowel going into silence at the end of a VCV sample ("a -"); empty = none. */
    val endAlias: String = "-",
    /** Prefix of the first syllable after silence ("- "), empty to leave it out. */
    val headPrefix: String = "- ",
    /** CV entries in CVVC banks: with the "- " head prefix or plain. */
    val cvWithHead: Boolean = false,
)

object Kana {
    private val table: Map<String, String> = buildMap {
        val rows = """
            あa いi うu えe おo かka きki くku けke こko さsa しshi すsu せse そso たta ちchi つtsu てte とto
            なna にni ぬnu ねne のno はha ひhi ふfu へhe ほho まma みmi むmu めme もmo やya ゆyu よyo
            らra りri るru れre ろro わwa ゐwi ゑwe をwo んn がga ぎgi ぐgu げge ごgo ざza じji ずzu ぜze ぞzo
            だda ぢji づzu でde どdo ばba びbi ぶbu べbe ぼbo ぱpa ぴpi ぷpu ぺpe ぽpo ゔvu
            きゃkya きゅkyu きょkyo しゃsha しゅshu しょsho ちゃcha ちゅchu ちょcho にゃnya にゅnyu にょnyo
            ひゃhya ひゅhyu ひょhyo みゃmya みゅmyu みょmyo りゃrya りゅryu りょryo ぎゃgya ぎゅgyu ぎょgyo
            じゃja じゅju じょjo びゃbya びゅbyu びょbyo ぴゃpya ぴゅpyu ぴょpyo てぃti でぃdi とぅtu どぅdu
            ふぁfa ふぃfi ふぇfe ふぉfo うぃwi うぇwe うぉwo いぇye つぁtsa つぃtsi つぇtse つぉtso しぇshe じぇje ちぇche
            ゔぁva ゔぃvi ゔぇve ゔぉvo すぃsi ずぃzi
        """.trimIndent()
        for (tok in rows.split(Regex("\\s+"))) {
            val k = tok.takeWhile { it.code > 0x3000 }
            put(k, tok.drop(k.length))
        }
        // katakana map to the same romaji
        val copy = HashMap(this)
        for ((k, v) in copy) put(k.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString(""), v)
    }

    fun isKana(c: Char) = c in '぀'..'ヿ'

    /** Small kana that change the sound of the one before (しょ, づぁ, ヴィ). */
    private val smallVowel = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'ゎ' to "wa")
    private fun small(c: Char): String? = smallVowel[if (c in 'ァ'..'ヶ') c - 0x60 else c]
    fun isSmall(c: Char) = small(c) != null

    /** Splits kana text into syllables (longest match, so きゃ is one). */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when {
                two.isNotEmpty() && two in table -> { out += two; i += 2 }
                // any kana with a small one after it is one syllable, also pairs the table doesn't list (づぁ, ぐぃ)
                two.isNotEmpty() && text[i].toString() in table && isSmall(text[i + 1]) -> { out += two; i += 2 }
                text[i].toString() in table -> { out += text[i].toString(); i++ }
                text[i] == 'ー' || text[i] == '・' -> i++
                else -> i++
            }
        }
        return out
    }

    /** Hiragana for a Japanese romaji syllable ("ka" → か, "kye" → きぇ, "n" → ん), or null. */
    fun fromRomaji(r: String): String? {
        val t = r.lowercase()
        reverse[t]?.let { return it }
        // a consonant with y and a vowel the table doesn't list: the i-kana of the consonant and a small one
        val m = Regex("^([a-z]+)y([aeiou])$").find(t) ?: return null
        val base = reverse[m.groupValues[1] + "i"] ?: return null
        val small = mapOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ", "e" to "ぇ", "i" to "ぃ")[m.groupValues[2]] ?: return null
        return base + small
    }

    private val reverse: Map<String, String> by lazy {
        buildMap { for ((k, v) in table) if (k.all { it in 'ぁ'..'ゖ' } && v !in this) put(v, k) }
    }

    fun romaji(kana: String): String? = table[kana] ?: run {
        // a kana and a small one: the consonant of the first and the sound of the small one
        if (kana.length != 2) return@run null
        val base = table[kana[0].toString()] ?: return@run null
        val add = small(kana[1]) ?: return@run null
        val cons = base.dropLastWhile { it in "aiueo" }
        when {
            add.startsWith("y") && (cons.endsWith("sh") || cons.endsWith("ch") || cons == "j") -> cons + add.drop(1)
            add.startsWith("y") && cons.isEmpty() -> add
            else -> cons + add
        }
    }
}

object Syllables {
    private val latinVowels = "aeiou"
    private val cyrVowels = "аеёиоуыэюя"

    /**
     * Syllables of a word written without separators: consonants (a letter with its modifiers: ' ь ъ, or a digit
     * standing for a consonant, as in "4'") up to a vowel; consonants after the last vowel make a syllable without
     * a vowel ("babab" → ba ba b). A latin "y" before a vowel is a consonant (kya), otherwise a vowel. A number at
     * the end (a take) is left out.
     */
    fun run(word: String): List<Syllable> {
        val w = word.lowercase().trimEnd { it.isDigit() }
        val cyr = w.any { it in cyrVowels }
        fun vowelAt(i: Int): Boolean {
            val ch = w[i]
            if (cyr) return ch in cyrVowels
            if (ch == 'y') return i + 1 >= w.length || w[i + 1] !in latinVowels
            return ch in latinVowels
        }
        val out = mutableListOf<Syllable>()
        var cons = StringBuilder()
        var i = 0
        while (i < w.length) {
            val ch = w[i]
            when {
                vowelAt(i) -> { out += Syllable(cons.toString() + ch, cons.toString(), ch.toString()); cons = StringBuilder() }
                ch.isLetter() || ch == '~' || ch == '\'' || ch == 'ь' || ch == 'ъ' -> cons.append(ch)
                ch.isDigit() && i + 1 < w.length && w[i + 1] == '\'' -> cons.append(ch)
                else -> return emptyList()
            }
            i++
        }
        if (cons.isNotEmpty()) out += Syllable(cons.toString(), cons.toString(), "")
        return out
    }

    private val pinyinInitials = listOf("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w")
    private val pinyinFinals = setOf("a", "o", "e", "i", "u", "v", "ü", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "ong", "er", "ia", "ie", "iao",
        "iu", "iou", "ian", "in", "iang", "ing", "iong", "ua", "uo", "uai", "ui", "uei", "uan", "un", "uen", "uang", "ueng", "ue", "ve", "üe", "van", "vn", "n", "ng", "m")

    /** Initial and final of a pinyin syllable without tone ("zhuang" → zh + uang), or null. */
    fun pinyin(token: String): Pair<String, String>? {
        val t = token.lowercase().trimEnd { it.isDigit() }
        if (t.isEmpty()) return null
        val ini = pinyinInitials.firstOrNull { t.startsWith(it) && t.length > it.length } ?: ""
        val fin = t.removePrefix(ini)
        return if (fin in pinyinFinals) ini to fin else null
    }

    val arpaVowels = setOf("aa", "ae", "ah", "ao", "aw", "ax", "ay", "eh", "er", "ey", "ih", "ix", "iy", "ow", "oy", "uh", "uw", "ux")
    private val arpaConsonants = setOf("b", "ch", "d", "dh", "dx", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "q", "r", "s", "sh",
        "t", "th", "v", "w", "y", "z", "zh", "el", "em", "en")

    /** Syllables of a name made of ARPAbet phonemes; the consonants of one syllable are joined by spaces. Null: not such a name. */
    fun arpa(tokens: List<String>): List<Syllable>? {
        val t = tokens.map { it.lowercase().trimEnd { c -> c.isDigit() } }
        if (t.isEmpty() || t.any { it !in arpaVowels && it !in arpaConsonants } || t.none { it in arpaVowels }) return null
        val out = mutableListOf<Syllable>()
        val cons = mutableListOf<String>()
        for (p in t) {
            if (p in arpaVowels) { out += Syllable((cons + p).joinToString(" "), cons.joinToString(" "), p); cons.clear() }
            else cons += p
        }
        if (cons.isNotEmpty()) out += Syllable(cons.joinToString(" "), cons.joinToString(" "), "")
        return out
    }

    /** Consonant and vowel of a romaji or Cyrillic syllable ("kya" → ky + a, "n" → n as a vowel-like coda). */
    fun parts(romaji: String): Pair<String, String> {
        val r = romaji.lowercase()
        if (r == "n" || r == "ん") return "" to "n"
        val vowels = if (r.any { it in cyrVowels }) cyrVowels else latinVowels
        var vi = r.indexOfFirst { it in vowels }
        if (vi < 0) vi = r.indexOf('y')
        if (vi < 0) return r to ""
        return r.substring(0, vi) to r.substring(vi)
    }

    /**
     * Syllables named by a sample file: kana ("_あかさ", "かきくけこ") or tokens separated by "_", "-" or spaces
     * ("ka_ki_ku", "ма-мо"). Leading "_" (pause) is ignored.
     */
    /**
     * True when most [stems] are Japanese written in romaji ("ka_ki_ku", "ka-ki-ku"): such banks name their aliases
     * in kana.
     */
    fun romajiJapanese(stems: List<String>): Boolean {
        if (stems.isEmpty()) return false
        val ok = stems.count { st ->
            val name = st.trim().trimStart('_', '-', ' ')
            val toks = name.split('_', '-', ' ').filter { it.isNotEmpty() }
            name.none { Kana.isKana(it) } && toks.isNotEmpty() && toks.all { Kana.fromRomaji(it) != null }
        }
        return ok >= stems.size * 0.6
    }

    /**
     * The line of a reclist that a numbered sample ("00117") was recorded from: banks recorded with a numbered
     * reclist name their files by line number. [zeroBased]: the first line is 0 (a "00000" file exists).
     */
    fun reclistLine(stem: String, reclist: List<String>, zeroBased: Boolean): String? {
        val n = stem.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }?.toIntOrNull() ?: return null
        return reclist.getOrNull(if (zeroBased) n else n - 1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Lines of a reclist file: one recording per line, blank lines and "#" comments left out. */
    fun reclist(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun fromName(stem: String, kana: Boolean = false): List<Syllable> {
        val name = stem.trim().trimStart('_', '-', ' ')
        if (name.any { Kana.isKana(it) }) {
            return Kana.split(name).map { k ->
                val r = Kana.romaji(k) ?: k
                val (c, v) = parts(r)
                Syllable(k, c, v.take(1).ifEmpty { v })
            }
        }
        val tokens = name.split('_', '-', ' ').filter { it.isNotEmpty() }
        // ARPAbet phonemes ("m_ah_s_t_ah"): syllables are the consonants before each vowel and the vowel
        arpa(tokens)?.let { return it }
        // Chinese pinyin ("mai_mai", "zhuang"): the whole final is the vowel ("ai m", not "a m")
        if (!kana && tokens.isNotEmpty() && tokens.all { pinyin(it) != null } && tokens.any { Kana.fromRomaji(it) == null }) {
            return tokens.map { t -> val (c, v) = pinyin(t)!!; Syllable(t, c, v) }
        }
        // one word with several syllables in it ("kakiku", "babab", "b'ab'ab'", "мамам")
        if (tokens.size == 1) run(tokens[0]).takeIf { it.size > 1 }?.let { return it }
        return tokens.map { t ->
            val (c, v) = parts(t)
            Syllable((if (kana) Kana.fromRomaji(t) else null) ?: t, c, v.take(1).ifEmpty { v })
        }
    }
}

/** Finds syllables in a recording and writes oto entries for them. */
object AutoOto {
    /**
     * Places [syllables] in the audio from loudness and voicing alone: the gaps between them are the deepest
     * dips of loudness (or unvoiced stretches) between the first and last sound; with a [bpm] the dips are
     * searched near the beat grid.
     */
    fun segment(samples: FloatArray, sampleRate: Int, syllables: List<Syllable>, bpm: Double = 0.0): List<SyllableTiming> {
        if (syllables.isEmpty()) return emptyList()
        val hop = 0.005
        val pow = Pitch.power(samples, sampleRate, hop).values
        val f0 = Pitch.yin(samples, sampleRate, hop).values
        val n = pow.size
        // smoothed loudness and where the sound is
        val sm = FloatArray(n) { i -> var s = 0f; var c = 0; for (k in max(0, i - 3)..min(n - 1, i + 3)) { s += pow[k]; c++ }; s / c }
        val peak = sm.max()
        val floor = sorted10(sm)
        val gate = floor + (peak - floor) * 0.35f
        val voiced = BooleanArray(n) { i -> i < f0.size && f0[i] > 0f }
        // singing starts with the first loud voiced sound and the consonant right before it (up to 300 ms of
        // sound without a gap); a breath before it, often loud in soft takes, is left out
        val low = floor + (peak - floor) * 0.15f
        val v0 = (0 until n).firstOrNull { sm[it] > gate && voiced[it] } ?: sm.indexOfFirst { it > gate }.coerceAtLeast(0)
        var first = v0
        while (first > 0 && v0 - first < 60 && sm[first - 1] > low) first--
        val v1 = (n - 1 downTo 0).firstOrNull { sm[it] > gate && voiced[it] } ?: sm.indexOfLast { it > gate }
        var last = v1.coerceAtLeast(first + 1)
        // a sample ending with a consonant ("babab"): its sound after the last vowel belongs in too
        val endsWithConsonant = syllables.size > 1 && syllables.last().vowel.isEmpty()
        // (a stop has a silent closure before its release, so a short gap doesn't end it)
        if (endsWithConsonant) { val l0 = last; for (i in l0 + 1 until min(n, l0 + 100)) if (sm[i] > low) last = i }
        // "dipness": how much quieter than the surroundings, plus a bonus where voicing stops
        val count = syllables.size
        val cuts = mutableListOf<Int>()
        if (count > 1) {
            val span = last - first
            val minGap = max(8, span / (count * 3))
            val score = FloatArray(n)
            for (i in first + minGap until last - minGap) {
                var l = -1e9f; var r = -1e9f
                for (k in max(first, i - 30)..i) l = max(l, sm[k])
                for (k in i..min(last, i + 30)) r = max(r, sm[k])
                score[i] = (min(l, r) - sm[i]) + (if (!voiced[i]) 6f else 0f)
            }
            // reclists are sung to a steady beat: the cuts are placed together, as the deepest dips that keep
            // the syllables about equally long (a nasal or a glide after a vowel leaves no dip of its own, the beat
            // still puts its cut in the right place); the beat is the given one or the best fitting one
            val periods = if (bpm > 0) listOf(60.0 / bpm / hop) else {
                // (a final consonant takes only a little of its beat)
                val even = span.toDouble() / (if (endsWithConsonant) count - 0.85 else count.toDouble())
                (0..24).map { even * (0.75 + it * 0.025) }
            }
            var best: List<Int>? = null
            var bestCost = Double.MAX_VALUE
            for (period in periods) {
                val r = gridCuts(score, first, last, count, period, endsWithConsonant) ?: continue
                if (r.second < bestCost) { bestCost = r.second; best = r.first }
            }
            cuts += best ?: (1 until count).map { first + span * it / count }
        }
        // a vowel (or ん) right after a vowel leaves no dip: its start is where the timbre changes most
        if (cuts.isNotEmpty() && syllables.drop(1).any { it.consonant.isEmpty() }) {
            val spec = mlabeler.core.dsp.Spectrogram.compute(samples, sampleRate, hopSeconds = hop, bands = 40, maxFreq = 8000.0)
            fun change(i: Int): Double {
                if (i - 6 < 0 || i + 6 >= spec.frames) return 0.0
                var d = 0.0
                for (b in 0 until spec.bands) {
                    var l = 0; var r = 0
                    for (k in 1..6) { l += spec.value(i - k, b); r += spec.value(i + k - 1, b) }
                    d += abs(l - r)
                }
                return d
            }
            for (j in cuts.indices) {
                if (syllables[j + 1].consonant.isNotEmpty()) continue
                val lo = max(first + 1, cuts[j] - 20); val hi = min(last - 1, cuts[j] + 20)
                if (lo < hi) cuts[j] = (lo..hi).maxBy { change(it) }
            }
        }
        // a cut inside an unvoiced stretch (a consonant) moves to where that stretch begins
        val starts = listOf(first) + cuts.map { cut ->
            var a = cut
            if (!voiced[cut]) while (a > first + 1 && !voiced[a - 1]) a--
            a
        }
        val ends = starts.drop(1) + listOf(last)
        return syllables.mapIndexed { k, syl ->
            val a = starts[k]
            val b = ends[k]
            // vowel starts where it gets loud and voiced after the consonant
            var v = a
            if (syl.consonant.isNotEmpty()) {
                val target = sm[a] + (sm.sliceMax(a, b) - sm[a]) * 0.6f
                v = a
                while (v < b && !(sm[v] >= target && voiced[v])) v++
                if (v >= b) v = a + (b - a) / 4
            } else {
                while (v < b && !voiced[v]) v++
                if (v >= b) v = a
            }
            SyllableTiming(syl, a * hop, v * hop, b * hop)
        }
    }

    /** One phoneme of a sample and where it is, ms. */
    private class Phone(val name: String, val start: Double, val end: Double, val vowel: Boolean)

    /** Phonemes from syllable timings: the consonants share the time before the vowel, the vowel lasts to the next syllable. */
    private fun phones(timings: List<SyllableTiming>): List<Phone> {
        val out = mutableListOf<Phone>()
        for ((k, t) in timings.withIndex()) {
            val c = t.cStart * 1000
            val v = t.vStart * 1000
            val end = timings.getOrNull(k + 1)?.cStart?.times(1000) ?: (t.vEnd * 1000)
            val cons = t.syllable.consonant.split(' ').filter { it.isNotEmpty() }
            if (t.syllable.vowel.isEmpty()) {
                val step = (end - c) / cons.size.coerceAtLeast(1)
                cons.forEachIndexed { i, p -> out += Phone(p, c + i * step, c + (i + 1) * step, false) }
                continue
            }
            val step = (v - c) / cons.size.coerceAtLeast(1)
            cons.forEachIndexed { i, p -> out += Phone(p, c + i * step, c + (i + 1) * step, false) }
            out += Phone(t.syllable.vowel, v, end, true)
        }
        return out
    }

    /**
     * ARPAsing entries: from silence into the first phoneme ("- m"), every pair of neighbouring phonemes ("m ah",
     * "s t"), every vowel held on its own ("ah"), the last phoneme into silence ("v -").
     */
    fun arpaEntries(sample: String, timings: List<SyllableTiming>, lengthMs: Double): List<OtoEntry> {
        val out = mutableListOf<OtoEntry>()
        val ph = phones(timings)
        if (ph.isEmpty()) return out
        fun add(alias: String, left: Double, overlap: Double, preu: Double, fixed: Double, right: Double) {
            val l = left.coerceIn(0.0, lengthMs)
            val r = max(right, fixed + 5).coerceIn(l, lengthMs)
            out += OtoEntry.fromAbsolute(sample, alias, OtoAbsolute(l, overlap.coerceIn(l, r), preu.coerceIn(l, r), fixed.coerceIn(l, r), r), lengthMs, negativeCutoff = true)
        }
        val first = ph.first()
        add("- " + first.name, first.start - 110, first.start - 10, first.start, first.start + 10, (first.start + first.end) / 2)
        for ((i, p) in ph.withIndex()) {
            val next = ph.getOrNull(i + 1)
            if (p.vowel) {
                val d = p.end - p.start
                add(p.name, p.start + d * 0.25, p.start + d * 0.25, p.start + d * 0.25, p.start + d * 0.25 + 10, p.end - d * 0.2)
            }
            if (next != null) {
                // a vowel into a consonant is heard earlier than the loudness dip that separates the syllables
                val b = if (p.vowel && !next.vowel) next.start - 60 else next.start
                add(p.name + " " + next.name, b - 110, b - 10, b, b + 10, (next.start + next.end) / 2)
            }
        }
        // into silence: from the end of a vowel, or from the start of a final consonant (its release is the end)
        val last = ph.last()
        val e = if (last.vowel) last.end - 50 else last.start
        add(last.name + " -", e - 110, e - 10, e, e + 10, lengthMs - 10)
        return out
    }

    /**
     * CVC entries (aliases without spaces), placed as in hand-made Russian CVC banks: from silence "-b" (the
     * consonant) and "-ba"; between syllables "ab" (vowel into the consonant) and "ba"; at the end "ab-" and the
     * final consonant alone "b".
     */
    fun cvcEntries(sample: String, timings: List<SyllableTiming>, lengthMs: Double, s: AutoOtoSettings): List<OtoEntry> {
        val out = mutableListOf<OtoEntry>()
        fun add(alias: String, left: Double, overlap: Double, preu: Double, fixed: Double, right: Double) {
            val l = left.coerceIn(0.0, lengthMs)
            val r = max(right, fixed + 5).coerceIn(l, lengthMs)
            out += OtoEntry.fromAbsolute(sample, alias, OtoAbsolute(l, overlap.coerceIn(l, r), preu.coerceIn(l, r), fixed.coerceIn(l, r), r), lengthMs, negativeCutoff = true)
        }
        for ((k, t) in timings.withIndex()) {
            val c = t.cStart * 1000
            val v = t.vStart * 1000
            val syl = t.syllable
            val nextC = timings.getOrNull(k + 1)?.cStart?.times(1000)
            if (syl.vowel.isEmpty()) {
                // the final consonant: after the vowel before it ("ab-") and on its own ("b")
                val prev = timings.getOrNull(k - 1) ?: continue
                add(prev.syllable.vowel + syl.consonant + "-", c - 85, c - 25, c + 35, c + 285, lengthMs - 10)
                add(syl.consonant, c - 2, c + 30, c + 42, c + 292, lengthMs - 10)
                continue
            }
            val end = (nextC ?: (t.vEnd * 1000)) - if (nextC == null) 40.0 else if (k == 0) 101.0 else 87.0
            if (k == 0) {
                // (the detected start of a consonant from silence is a little early: a voiced one hums before it)
                if (syl.consonant.isNotEmpty()) add("-" + syl.consonant, c - 4, c + 34, c + 34, v + 114, v + 144)
                if (syl.consonant.isNotEmpty()) add("-" + syl.text, c + 26, c + 65, v + 41, v + 211, end)
                else add("-" + syl.text, v - 60, v - 20, v, v + 170, end)
            } else {
                add(syl.text, c + 4, c + 33, v - 19, v + 159, end)
            }
            // the vowel into the next consonant
            val nt = timings.getOrNull(k + 1)
            if (nt != null && nt.syllable.vowel.isNotEmpty() && nextC != null) {
                add(syl.vowel + nt.syllable.consonant, nextC - 96, nextC - 37, nextC - 27, nextC + 4, nextC + 33)
            }
        }
        return out
    }

    /**
     * Cuts between [count] syllables from [first] to [last] frames, about [period] frames apart: the best sum of
     * dip [score]s minus a penalty for uneven spacing (dynamic programming). Null when the beat doesn't fit.
     */
    private fun gridCuts(score: FloatArray, first: Int, last: Int, count: Int, period: Double, shortLast: Boolean = false): Pair<List<Int>, Double>? {
        val w = (period * 0.4).toInt().coerceAtLeast(2)
        fun cost(len: Int): Double { val d = (len - period) / period; return 40.0 * d * d }
        // cand[j]: frames allowed for cut j (1-based), around first + j·period
        val cand = (1 until count).map { j -> val c = (first + j * period).toInt(); (max(first + 1, c - w)..min(last - 1, c + w)).toList() }
        if (cand.any { it.isEmpty() }) return null
        var prevPos = listOf(first)
        var prevCost = doubleArrayOf(0.0)
        val back = mutableListOf<IntArray>()
        for (j in cand.indices) {
            val pos = cand[j]
            val cur = DoubleArray(pos.size) { Double.MAX_VALUE }
            val from = IntArray(pos.size)
            for ((a, x) in pos.withIndex()) for ((b, y) in prevPos.withIndex()) {
                if (x - y < 4) continue
                val c = prevCost[b] + cost(x - y) - score[x]
                if (c < cur[a]) { cur[a] = c; from[a] = b }
            }
            back += from
            prevPos = pos; prevCost = cur
        }
        // the last syllable may be held longer: only a shorter one than the beat costs
        var bestEnd = -1; var bestCost = Double.MAX_VALUE
        for ((a, x) in prevPos.withIndex()) {
            if (prevCost[a] == Double.MAX_VALUE) continue
            val tail = last - x
            val c = prevCost[a] + if (tail < period && !shortLast) cost(tail) else 0.0
            if (c < bestCost) { bestCost = c; bestEnd = a }
        }
        if (bestEnd < 0) return null
        val out = IntArray(cand.size)
        var k = bestEnd
        for (j in cand.indices.reversed()) { out[j] = cand[j][k]; k = back[j][k] }
        return out.toList() to bestCost
    }

    private fun FloatArray.sliceMax(a: Int, b: Int): Float { var m = -1e9f; for (i in a until max(a + 1, b)) m = max(m, this[i]); return m }

    private fun sorted10(x: FloatArray): Float = x.sorted()[(x.size * 0.1).toInt().coerceIn(0, x.size - 1)]

    /** Syllable timings from an aligned phoneme tier (consonant and vowel phonemes in order). */
    fun fromPhonemes(phones: IntervalTier, syllables: List<Syllable>, silence: Set<String> = setOf("SP", "AP", "pau", "sil", "")): List<SyllableTiming> {
        val ph = (0 until phones.size).filter { phones.texts[it] !in silence }
        val out = mutableListOf<SyllableTiming>()
        var p = 0
        for (syl in syllables) {
            if (p >= ph.size) break
            val hasC = syl.consonant.isNotEmpty() && p + 1 < ph.size
            val cStart = phones.startOf(ph[p])
            val vIdx = if (hasC) ph[p + 1] else ph[p]
            out += SyllableTiming(syl, cStart, phones.startOf(vIdx), phones.endOf(vIdx))
            p += if (hasC) 2 else 1
        }
        return out
    }

    /** Phonemes to give an aligner for [syllables] (romaji consonant + vowel). */
    fun phonemesFor(syllables: List<Syllable>): List<String> = syllables.flatMap { s ->
        listOfNotNull(s.consonant.takeIf { it.isNotEmpty() }, s.vowel.takeIf { it.isNotEmpty() })
    }

    fun styleOf(syllables: List<Syllable>, settings: AutoOtoSettings): RecStyle = when {
        settings.style != RecStyle.Auto -> settings.style
        syllables.any { it.vowel in Syllables.arpaVowels } && syllables.none { s -> s.text.any { Kana.isKana(it) } } -> RecStyle.ARPA
        syllables.size <= 1 -> RecStyle.CV
        // a run of syllables ending with a consonant: "babab"
        syllables.last().vowel.isEmpty() && syllables.none { s -> s.text.any { Kana.isKana(it) } } -> RecStyle.CVC
        else -> RecStyle.VCV
    }

    /**
     * oto entries for one sample from syllable timings. The rules follow hand-made oto of VCV banks: the
     * preutterance at the vowel, the overlap a little before the consonant (in the vowel before it), the offset a
     * fixed distance before the overlap, the end of the entry well before the next consonant.
     */
    fun entries(sample: String, timings: List<SyllableTiming>, lengthMs: Double, s: AutoOtoSettings): List<OtoEntry> {
        val style = styleOf(timings.map { it.syllable }, s)
        if (style == RecStyle.CVC) return cvcEntries(sample, timings, lengthMs, s)
        if (style == RecStyle.ARPA) return arpaEntries(sample, timings, lengthMs)
        val out = mutableListOf<OtoEntry>()
        for ((k, t) in timings.withIndex()) {
            val c = t.cStart * 1000
            val v = t.vStart * 1000
            val next = timings.getOrNull(k + 1)?.cStart?.times(1000)
            val prev = timings.getOrNull(k - 1)
            val hasC = t.syllable.consonant.isNotEmpty()
            // after a pause (the first syllable, or every one in CV banks) the sound starts from silence
            val head = k == 0 || style == RecStyle.CV || style == RecStyle.CVVC
            val onset = if (hasC) c + min(20.0, (v - c) / 3) else v - s.vowelOverlapMs
            val overlap = onset - when { head && hasC -> 5.0; head -> 40.0; else -> s.overlapBeforeMs }
            val leftLimit = if (head) 0.0 else prev?.let { it.vStart * 1000 + 20 } ?: 0.0
            val left = (overlap - if (hasC) s.leftMarginMs else s.vowelLeftMs).coerceAtLeast(leftLimit).coerceAtLeast(0.0)
            val vowelEnd = (next ?: (t.vEnd * 1000)) - if (next == null) 40.0 else if (head) s.endMarginMs / 2 else s.endMarginMs
            val fixed = min(v + s.fixedMs, max(v + 10, vowelEnd - 10))
            val right = max(vowelEnd, fixed + 10).coerceAtMost(lengthMs)
            var a = OtoAbsolute(left, overlap.coerceAtLeast(left), v, fixed, right)
            val headA = a
            // CV banks: the offset just before the sound, a longer consonant part, the end well before the vowel fades
            if (style == RecStyle.CV) {
                val l = max(0.0, overlap - 30)
                val f = min(v + 170, max(v + 10, right - 20))
                a = OtoAbsolute(l, overlap.coerceAtLeast(l), v, f, max(f + 10, right - 150))
            }
            // CV entries of CVVC banks follow a vowel-to-consonant entry: they start within the consonant
            // (as in hand-made CVVC banks)
            if (style == RecStyle.CVVC && hasC) {
                val l = max(0.0, c + 25)
                a = OtoAbsolute(l, max(l, c + 70).coerceAtMost(v), max(v - 20, l), max(v + 80, v - 20 + 10), right)
            }
            val alias = when {
                style == RecStyle.CV -> (if (k == 0 && s.cvWithHead) s.headPrefix else "") + t.syllable.text
                style == RecStyle.CVVC -> (if (k == 0 && s.cvWithHead) s.headPrefix else "") + t.syllable.text
                k == 0 -> s.headPrefix + t.syllable.text
                else -> (prev?.syllable?.vowel ?: "") + " " + t.syllable.text
            }
            out += OtoEntry.fromAbsolute(sample, alias, a, lengthMs, negativeCutoff = true)
            // CVVC: the first syllable also from silence ("- か")
            if (style == RecStyle.CVVC && k == 0 && !s.cvWithHead && s.headPrefix.isNotEmpty()) out += OtoEntry.fromAbsolute(sample, s.headPrefix + t.syllable.text, headA, lengthMs, negativeCutoff = true)
            // CVVC: vowel-to-consonant part before the next consonant
            if (style == RecStyle.CVVC && next != null) {
                val nt = timings[k + 1]
                if (nt.syllable.consonant.isNotEmpty()) {
                    val nc = nt.cStart * 1000
                    val nv = nt.vStart * 1000
                    // (the detected consonant start is a little late for these)
                    val vcLeft = max(v + s.fixedMs, nc - 175)
                    val vc = OtoAbsolute(vcLeft, max(vcLeft, nc - 75), max(vcLeft, nc - 25), nc + min(5.0, (nv - nc) / 2), min(nv, nc + 40).coerceAtLeast(nc + 10))
                    out += OtoEntry.fromAbsolute(sample, t.syllable.vowel + " " + nt.syllable.consonant, vc, lengthMs, negativeCutoff = true)
                }
            }
        }
        // VCV: the end of the last vowel into silence ("a -")
        if (style == RecStyle.VCV && timings.isNotEmpty() && s.endAlias.isNotEmpty()) {
            val t = timings.last()
            // where the voice fades out (the loudness gate is a little late for that), then the silence after it
            val end = t.vEnd * 1000 - 50
            val left = max(t.vStart * 1000 + 20, end - 160)
            val e = OtoAbsolute(left, left + 80, end, end + 100, max(end + 150, lengthMs - 100).coerceAtMost(lengthMs))
            out += OtoEntry.fromAbsolute(sample, t.syllable.vowel + " " + s.endAlias, e, lengthMs, negativeCutoff = true)
        }
        return out
    }
}
