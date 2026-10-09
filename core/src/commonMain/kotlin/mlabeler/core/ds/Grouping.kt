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
    /** ISO code of the language ("ja", "zh"…); empty for dictionaries of no particular language. */
    val language: String = "",
    /** Where the set comes from ("DiffSinger", "OpenUtau"…): tells apart variants of one language. */
    val source: String = "",
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

        /** The Japanese dictionary of DiffSinger alignment (japanese_dict_full.txt of HubertFA): っ (cl) and ん (N) are notes of their own. */
        val japanese = PhonemeDict(
            "Japanese", language = "ja", source = "DiffSinger",
            vowels = listOf("a", "i", "u", "e", "o", "N", "cl"),
            consonants = listOf("b", "by", "ch", "d", "dy", "f", "g", "gw", "gy", "h", "hy", "j", "k", "kw", "ky", "m", "my", "n", "ny", "p", "py", "r", "ry", "s", "sh", "t", "ts", "ty", "v", "w", "y", "z"),
        )
        /** DiffSinger's opencpop-extension.txt with the additions of ds-zh-pinyin-lite.txt (io, ueng, y0). */
        val chinese = PhonemeDict(
            "Chinese", language = "zh", source = "DiffSinger",
            vowels = listOf("a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "i", "i0", "ia", "ian", "iang", "iao", "ie", "in", "ing", "io", "iong", "ir", "iu", "o", "ong", "ou", "u", "ua", "uai", "uan", "uang", "ueng", "ui", "un", "uo", "v", "van", "ve", "vn", "E", "En"),
            consonants = listOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w", "y0"),
        )
        /** ARPAbet without stress marks, as in ds_cmudict-07b.txt of DiffSinger alignment (HubertFA). */
        val english = PhonemeDict(
            "English", language = "en", source = "DiffSinger",
            vowels = listOf("aa", "ae", "ah", "ao", "aw", "ax", "ay", "eh", "er", "ey", "ih", "iy", "ow", "oy", "uh", "uw"),
            consonants = listOf("b", "ch", "d", "dh", "dx", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "r", "s", "sh", "t", "th", "v", "w", "y", "z", "zh", "_r"),
        )
        /** The dictionary of tigermeat's English SOFA models (dict.txt of tgm_en_v100): ARPAbet with dr, tr, cl, q and vf. */
        val englishTigermeat = PhonemeDict(
            "English (tigermeat)", language = "en", source = "tigermeat",
            vowels = listOf("aa", "ae", "ah", "ao", "aw", "ax", "ay", "eh", "er", "ey", "ih", "iy", "ow", "oy", "uh", "uw"),
            consonants = listOf("b", "ch", "cl", "d", "dh", "dr", "dx", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "q", "r", "s", "sh", "t", "th", "tr", "v", "vf", "w", "y", "z", "zh"),
        )
        /** The set of OpenUtau's DiffSinger English phonemizer (ArpabetG2p). */
        val englishOpenUtau = PhonemeDict(
            "English (OpenUtau)", language = "en", source = "OpenUtau",
            vowels = listOf("aa", "ae", "ah", "ao", "aw", "ay", "eh", "er", "ey", "ih", "iy", "ow", "oy", "uh", "uw"),
            consonants = listOf("b", "ch", "d", "dh", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "r", "s", "sh", "t", "th", "v", "w", "y", "z", "zh"),
        )
        /** The phoneme set of Russian DiffSinger datasets (soft consonants with "y", й = j, reduced vowels ax, x, ex). */
        val russian = PhonemeDict(
            "Russian", language = "ru", source = "DiffSinger",
            vowels = listOf("a", "i", "u", "e", "o", "y", "ax", "x", "ex", "exh"),
            consonants = listOf("b", "v", "g", "d", "z", "k", "l", "m", "n", "p", "r", "s", "t", "f", "h", "sh", "ts", "zh",
                "by", "vy", "gy", "dy", "zy", "ky", "ly", "my", "ny", "py", "ry", "sy", "ty", "fy", "hy", "shy", "ch", "cl", "vf"),
            semivowels = listOf("j"),
        )
        /** Jyutping, as in jyutping_dict.txt of DiffSinger alignment (HubertFA). */
        val cantonese = PhonemeDict(
            "Cantonese", language = "yue", source = "DiffSinger",
            vowels = listOf("aa", "aai", "aak", "aam", "aan", "aang", "aap", "aat", "aau", "ai", "ak", "am", "an", "ang", "ap", "at", "au", "e", "ei", "ek", "em", "eng", "eoi", "eon", "eot", "ep", "eu", "i", "ik", "im", "in", "ing", "ip", "it", "iu", "o", "oe", "oek", "oeng", "oi", "ok", "on", "ong", "ot", "ou", "u", "ui", "uk", "un", "ung", "ut", "yu", "yun", "yut"),
            consonants = listOf("b", "c", "d", "f", "g", "gw", "h", "j", "k", "kw", "l", "m", "n", "ng", "p", "s", "t", "w", "z"),
        )
        /** The set of OpenUtau's DiffSinger Spanish phonemizer. */
        val spanish = PhonemeDict(
            "Spanish (OpenUtau)", language = "es", source = "OpenUtau",
            vowels = listOf("a", "e", "i", "o", "u"),
            consonants = listOf("b", "B", "ch", "d", "D", "f", "g", "G", "gn", "I", "k", "l", "ll", "m", "n", "p", "r", "rr", "s", "t", "U", "w", "x", "y", "Y", "z"),
        )
        /** The set of OpenUtau's DiffSinger Portuguese phonemizer. */
        val portuguese = PhonemeDict(
            "Portuguese (OpenUtau)", language = "pt", source = "OpenUtau",
            vowels = listOf("E", "O", "a", "a~", "e", "e~", "i", "i~", "o", "o~", "u", "u~"),
            consonants = listOf("J", "L", "R", "S", "X", "Z", "b", "d", "dZ", "f", "g", "j", "j~", "k", "l", "m", "n", "p", "r", "s", "t", "tS", "v", "w", "w~", "z"),
        )
        /** The set of OpenUtau's DiffSinger Italian phonemizer. */
        val italian = PhonemeDict(
            "Italian (OpenUtau)", language = "it", source = "OpenUtau",
            vowels = listOf("a", "e", "EE", "i", "o", "OO", "u"),
            consonants = listOf("b", "d", "dz", "dZZ", "f", "g", "j", "JJ", "k", "l", "LL", "m", "n", "nf", "ng", "p", "r", "s", "SS", "t", "ts", "tSS", "v", "w", "z"),
        )
        /** The set of OpenUtau's DiffSinger German phonemizer. */
        val german = PhonemeDict(
            "German (OpenUtau)", language = "de", source = "OpenUtau",
            vowels = listOf("aa", "ae", "ah", "ao", "aw", "ax", "ay", "ee", "eh", "er", "ex", "ih", "iy", "oe", "ohh", "ooh", "oy", "ue", "uh", "uw", "yy"),
            consonants = listOf("b", "cc", "ch", "d", "dh", "f", "g", "hh", "jh", "k", "l", "m", "n", "ng", "p", "pf", "q", "r", "rr", "s", "sh", "t", "th", "ts", "v", "w", "x", "y", "z", "zh"),
        )
        /** The set of OpenUtau's DiffSinger French Millefeuille phonemizer. */
        val french = PhonemeDict(
            "French (Millefeuille, OpenUtau)", language = "fr", source = "OpenUtau, Millefeuille",
            vowels = listOf("ah", "eh", "ae", "ee", "oe", "ih", "oh", "oo", "ou", "uh", "en", "in", "on"),
            consonants = listOf("y", "w", "f", "k", "p", "s", "sh", "t", "h", "b", "d", "g", "l", "m", "n", "r", "v", "z", "j", "ng", "q", "uy", "vf", "cl"),
        )
        /**
         * Korean as in the dictionary of colstone's SOFA model (and OpenUtau's DiffSinger Korean): K, L, M, N, NG, P, T
         * are the final consonants of a syllable, so they stay with the vowel before them.
         */
        val korean = PhonemeDict(
            "Korean", language = "ko", source = "DiffSinger",
            vowels = listOf("a", "e", "eo", "eu", "i", "o", "u", "ae", "oe", "ui"),
            consonants = listOf("b", "ch", "d", "g", "h", "j", "jj", "k", "kk", "m", "n", "p", "pp", "r", "s", "ss", "t", "tt", "K", "L", "M", "N", "NG", "P", "T"),
            semivowels = listOf("w", "y"),
        )
        /** Guesses only. */
        val auto = PhonemeDict("Auto")
        val builtIn = listOf(auto, japanese, chinese, english, englishTigermeat, englishOpenUtau, russian, cantonese, korean, spanish, portuguese, italian, german, french)
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
