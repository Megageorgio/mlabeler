package mlabeler.core.model

/** A rough kind of a phoneme name of any common set (X-SAMPA, ARPAbet, romaji, DiffSinger dictionaries…). */
object PhonemeKinds {
    enum class Kind { Vowel, Consonant, Pause }

    private val pauses = setOf("sp", "ap", "pau", "sil", "br", "cl", "r", "-", "breath", "q")
    // ARPAbet and other multi-letter vowels
    private val vowelNames = setOf(
        "aa", "ae", "ah", "ao", "aw", "ax", "axr", "ay", "eh", "er", "ey", "ih", "ix", "iy", "ow", "oy", "uh", "uw", "ux",
        "ai", "ei", "ao", "ou", "an", "en", "in", "un", "vn", "ang", "eng", "ing", "ong", "ia", "ie", "iu", "ua", "uo", "ui", "ue", "ve", "er",
        "ex", "exh", "y", "yy",
    )
    private const val vowelStarts = "aeiouyæɑɐɒəɛɪʊʌøœɨʉɯɤ@EIOUVQ{&3196"

    fun of(name: String): Kind {
        val n = name.trim()
        if (n.isEmpty() || n.lowercase() in pauses) return Kind.Pause
        // marks around the name: palatalisation ', length :, stress digits, nasal ~, language prefixes "ja/"
        val core = n.substringAfterLast('/').trimEnd('\'', ':', '~', '0', '1', '2', '3', '4', '5', '_').lowercase()
        if (core.isEmpty()) return Kind.Consonant
        if (core in vowelNames) return Kind.Vowel
        if (core.length <= 2 && core.first() in vowelStarts.lowercase() + vowelStarts) return Kind.Vowel
        return Kind.Consonant
    }
}
