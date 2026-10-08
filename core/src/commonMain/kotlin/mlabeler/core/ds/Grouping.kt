package mlabeler.core.ds

import kotlinx.serialization.Serializable
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

enum class PhonemeKind { Vowel, Consonant, Semi, Special, Rest }

/**
 * What kind of sound each phoneme is, for grouping phonemes into notes (DiffSinger's ph_num).
 * Saved as JSON: lists of phonemes per kind. Phonemes missing from the lists are guessed from their letters.
 */
@Serializable
data class PhonemeDict(
    val name: String = "",
    val vowels: List<String> = emptyList(),
    val consonants: List<String> = emptyList(),
    /** Glides like y, w: part of the next vowel's group, or a note of their own between consonants. */
    val semivowels: List<String> = emptyList(),
    /** Sounds that are a note of their own when no vowel is next to them (syllabic n, m…). */
    val special: List<String> = emptyList(),
    /** Silence and breath. */
    val rests: List<String> = DEFAULT_RESTS,
) {
    private val lookup: Map<String, PhonemeKind> by lazy {
        buildMap {
            for (p in consonants) put(p, PhonemeKind.Consonant)
            for (p in semivowels) put(p, PhonemeKind.Semi)
            for (p in special) put(p, PhonemeKind.Special)
            for (p in vowels) put(p, PhonemeKind.Vowel)
            for (p in rests) put(p, PhonemeKind.Rest)
        }
    }

    fun kindOf(phoneme: String): PhonemeKind {
        val p = phoneme.trim()
        if (p.isEmpty()) return PhonemeKind.Rest
        lookup[p]?.let { return it }
        // "ja/a" style names of multi-language datasets
        val bare = p.substringAfterLast('/')
        lookup[bare]?.let { return it }
        lookup[bare.lowercase()]?.let { return it }
        return guess(bare)
    }

    companion object {
        val DEFAULT_RESTS = listOf("SP", "AP", "pau", "sil", "br", "breath", "R", "-")
        private const val VOWEL_LETTERS = "aeiouyæøœɑɒɔəɘɛɜɞɤɨɪɯɵʉʊʌʏɐаеёиоуыэюяáéíóúàèìòùâêîôûäëïöü"

        /** Without a dictionary: a phoneme with a vowel letter is a vowel. */
        fun guess(p: String): PhonemeKind {
            val l = p.lowercase()
            if (l in DEFAULT_RESTS.map { it.lowercase() }) return PhonemeKind.Rest
            return if (l.any { it in VOWEL_LETTERS }) PhonemeKind.Vowel else PhonemeKind.Consonant
        }

        val japanese = PhonemeDict(
            "Japanese",
            vowels = listOf("a", "i", "u", "e", "o", "N", "A", "I", "U", "E", "O"),
            consonants = listOf("k", "ky", "g", "gy", "s", "sh", "z", "j", "t", "ts", "ty", "ch", "d", "dy", "n", "ny",
                "h", "hy", "f", "b", "by", "p", "py", "m", "my", "r", "ry", "ng", "v", "cl", "q"),
            semivowels = listOf("y", "w"),
        )
        val chinese = PhonemeDict(
            "Chinese",
            vowels = listOf("a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "i", "ia", "ian", "iang", "iao",
                "ie", "in", "ing", "iong", "iu", "ix", "iy", "i0", "ir", "o", "ong", "ou", "u", "ua", "uai", "uan", "uang",
                "ui", "un", "uo", "v", "van", "ve", "vn", "E", "En"),
            consonants = listOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh",
                "r", "z", "c", "s"),
            semivowels = listOf("y", "w"),
        )
        val english = PhonemeDict(
            "English",
            vowels = listOf("aa", "ae", "ah", "ao", "aw", "ax", "ay", "eh", "er", "ey", "ih", "iy", "ow", "oy", "uh", "uw"),
            consonants = listOf("b", "ch", "d", "dh", "dx", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "r",
                "s", "sh", "t", "th", "v", "z", "zh", "q"),
            semivowels = listOf("w", "y"),
        )
        /** The phoneme set of Russian DiffSinger datasets (soft consonants with "y", й = j, reduced vowels ax, x, ex). */
        val russian = PhonemeDict(
            "Russian",
            vowels = listOf("a", "i", "u", "e", "o", "y", "ax", "x", "ex", "exh"),
            consonants = listOf("b", "v", "g", "d", "z", "k", "l", "m", "n", "p", "r", "s", "t", "f", "h", "sh", "ts", "zh",
                "by", "vy", "gy", "dy", "zy", "ky", "ly", "my", "ny", "py", "ry", "sy", "ty", "fy", "hy", "shy", "ch", "cl", "vf"),
            semivowels = listOf("j"),
        )
        /** Guesses only. */
        val auto = PhonemeDict("Auto")
        val builtIn = listOf(auto, japanese, chinese, english, russian)
    }
}

object Grouping {
    /**
     * Splits [phonemes] into groups that are sung on one note: a group starts at a vowel or a rest, consonants
     * belong to the group before them (they are sung before the next note starts). Semivowels and special sounds
     * start a group only when no vowel is next to them. Returns the size of each group (DiffSinger's ph_num).
     */
    fun phNum(phonemes: List<String>, dict: PhonemeDict): List<Int> {
        if (phonemes.isEmpty()) return emptyList()
        val kinds = phonemes.map { dict.kindOf(it) }
        fun vowelAt(i: Int) = kinds.getOrNull(i) == PhonemeKind.Vowel
        val starts = BooleanArray(phonemes.size)
        for (i in phonemes.indices) {
            starts[i] = when (kinds[i]) {
                PhonemeKind.Vowel, PhonemeKind.Rest -> true
                PhonemeKind.Consonant -> false
                PhonemeKind.Semi, PhonemeKind.Special -> !vowelAt(i - 1) && !vowelAt(i + 1)
            }
        }
        starts[0] = true
        val out = ArrayList<Int>()
        var n = 0
        for (i in phonemes.indices) {
            if (starts[i] && n > 0) { out += n; n = 0 }
            n++
        }
        out += n
        return out
    }

    /** A tier of groups over the phoneme tier: each interval spans the phonemes of one note. */
    fun groupTier(phones: IntervalTier, dict: PhonemeDict, name: String = "words"): IntervalTier {
        val texts = phones.texts.map { it.ifBlank { "SP" } }
        val nums = phNum(texts, dict)
        val bounds = ArrayList<Double>(nums.size + 1)
        val labels = ArrayList<String>(nums.size)
        bounds += phones.bounds[0]
        var k = 0
        for (c in nums) {
            val group = phones.texts.subList(k, k + c)
            labels += groupName(group, dict)
            k += c
            bounds += phones.bounds[k]
        }
        return IntervalTier(name, bounds, labels)
    }

    /** The group's text: its phonemes, or the rest name when it starts with a rest (consonants after it come before the next note). */
    private fun groupName(group: List<String>, dict: PhonemeDict): String {
        if (dict.kindOf(group[0]) == PhonemeKind.Rest) return group[0].ifBlank { "SP" }
        return group.filter { dict.kindOf(it) != PhonemeKind.Rest }.joinToString("")
    }

    /** Adds the group tier to [doc] or replaces the existing word tier with it. */
    fun withGroups(doc: LabelDoc, dict: PhonemeDict): LabelDoc {
        val pi = doc.phonemeTierIndex()
        val ph = doc.tiers.getOrNull(pi) as? IntervalTier ?: return doc
        val wi = doc.wordTierIndex()
        val tier = groupTier(ph, dict, if (wi >= 0) doc.tiers[wi].let { (it as IntervalTier).name } else "words")
        return if (wi >= 0) doc.replace(wi, tier) else doc.copy(tiers = doc.tiers.toMutableList().also { it.add(pi, tier) })
    }
}
