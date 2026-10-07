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

enum class RecStyle { Auto, CV, VCV, CVVC }

data class AutoOtoSettings(
    val style: RecStyle = RecStyle.Auto,
    /** Beats per minute of the recording; 0 = find syllables from the audio only. */
    val bpm: Double = 0.0,
    /** Offset this far before the consonant, ms. */
    val leftMarginMs: Double = 60.0,
    /** Consonant (fixed) part reaches this far into the vowel, ms. */
    val fixedMs: Double = 50.0,
    /** Overlap for syllables without a consonant, ms before the vowel. */
    val vowelOverlapMs: Double = 25.0,
    /** End of an entry this far before the next consonant, ms. */
    val endMarginMs: Double = 40.0,
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
    fun fromName(stem: String): List<Syllable> {
        val name = stem.trim().trimStart('_', '-', ' ')
        if (name.any { Kana.isKana(it) }) {
            return Kana.split(name).map { k ->
                val r = Kana.romaji(k) ?: k
                val (c, v) = parts(r)
                Syllable(k, c, v.take(1).ifEmpty { v })
            }
        }
        return name.split('_', '-', ' ').filter { it.isNotEmpty() }.map { t ->
            val (c, v) = parts(t)
            Syllable(t, c, v.take(1).ifEmpty { v })
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
        val first = sm.indexOfFirst { it > gate }.coerceAtLeast(0)
        val last = sm.indexOfLast { it > gate }.coerceAtLeast(first + 1)
        val voiced = BooleanArray(n) { i -> i < f0.size && f0[i] > 0f }
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
            if (bpm > 0) {
                // one syllable per beat from the first sound; look for the dip near each beat
                val beat = 60.0 / bpm / hop
                for (k in 1 until count) {
                    val centre = (first + k * beat).toInt()
                    val w = (beat * 0.35).toInt()
                    var best = centre
                    var bestScore = -1e9f
                    for (i in max(first + 1, centre - w)..min(last - 1, centre + w)) if (score[i] > bestScore) { bestScore = score[i]; best = i }
                    cuts += best
                }
            } else {
                val order = (first + minGap until last - minGap).sortedByDescending { score[it] }
                for (i in order) {
                    if (cuts.size == count - 1) break
                    if (cuts.all { abs(it - i) >= minGap }) cuts += i
                }
                // not enough dips: split the rest evenly
                while (cuts.size < count - 1) cuts += first + (last - first) * (cuts.size + 1) / count
                cuts.sort()
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
        syllables.size <= 1 -> RecStyle.CV
        else -> RecStyle.VCV
    }

    /** oto entries for one sample from syllable timings. */
    fun entries(sample: String, timings: List<SyllableTiming>, lengthMs: Double, s: AutoOtoSettings): List<OtoEntry> {
        val style = styleOf(timings.map { it.syllable }, s)
        val out = mutableListOf<OtoEntry>()
        for ((k, t) in timings.withIndex()) {
            val c = t.cStart * 1000
            val v = t.vStart * 1000
            val next = timings.getOrNull(k + 1)?.cStart?.times(1000)
            val vowelEnd = (next ?: (t.vEnd * 1000)) - s.endMarginMs
            val prev = timings.getOrNull(k - 1)
            val hasC = t.syllable.consonant.isNotEmpty()
            val preu = if (hasC) v else v
            val overlap = if (hasC) c + min(20.0, (v - c) / 3) else v - s.vowelOverlapMs
            val leftLimit = prev?.let { it.vStart * 1000 + 20 } ?: 0.0
            val left = (min(c, overlap) - s.leftMarginMs).coerceAtLeast(leftLimit).coerceAtLeast(0.0)
            val fixed = (v + s.fixedMs).coerceAtMost(vowelEnd)
            val right = max(vowelEnd, fixed + 10).coerceAtMost(lengthMs)
            val a = OtoAbsolute(left, overlap, preu, fixed, right)
            val alias = when {
                style == RecStyle.CV -> (if (k == 0 && s.cvWithHead) s.headPrefix else "") + t.syllable.text
                style == RecStyle.CVVC -> (if (k == 0 && s.cvWithHead) s.headPrefix else "") + t.syllable.text
                k == 0 -> s.headPrefix + t.syllable.text
                else -> (prev?.syllable?.vowel ?: "") + " " + t.syllable.text
            }
            out += OtoEntry.fromAbsolute(sample, alias, a, lengthMs, negativeCutoff = true)
            // CVVC: vowel-to-consonant part before the next consonant
            if (style == RecStyle.CVVC && next != null) {
                val nt = timings[k + 1]
                if (nt.syllable.consonant.isNotEmpty()) {
                    val nc = nt.cStart * 1000
                    val nv = nt.vStart * 1000
                    val vcLeft = max(v + s.fixedMs, nc - 150)
                    val vc = OtoAbsolute(vcLeft, nc - 40, nc, nc + min(30.0, (nv - nc) / 2), min(nv, nc + 90))
                    out += OtoEntry.fromAbsolute(sample, t.syllable.vowel + " " + nt.syllable.consonant, vc, lengthMs, negativeCutoff = true)
                }
            }
        }
        return out
    }
}
