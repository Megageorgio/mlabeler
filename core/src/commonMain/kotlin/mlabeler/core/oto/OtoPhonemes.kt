package mlabeler.core.oto

import mlabeler.core.format.OtoEntry

/** What the oto entries of a recording say about its sounds: the syllable of an alias, phonemes in time. */
object OtoPhonemes {
    /**
     * The syllable an alias ends on: "ka", "- ka", "a ka", "か", "_か" all give ka; a vowel alone ("a", "- a") gives
     * a syllable without a consonant. Null for an alias that ends without a vowel ("a k" of CVVC, "a R", "-").
     */
    fun syllable(alias: String): Syllable? {
        val token = alias.trim().split(' ').lastOrNull { it.isNotBlank() }?.trimStart('-', '_')?.trimEnd { it.isDigit() } ?: return null
        if (token.isEmpty()) return null
        val s = Syllables.fromName(token).singleOrNull() ?: return null
        if (s.vowel.isEmpty()) return null
        // kana are named in romaji, as file names of other banks are
        val name = if (token.any { Kana.isKana(it) }) Kana.romaji(token) ?: token else token.lowercase()
        return Syllable(name, s.consonant, s.vowel)
    }

    /**
     * Phonemes of a recording from its [entries] (one sample), in seconds: the consonant of each syllable runs from
     * the overlap to the preutterance, its vowel from there to the cutoff or to the next consonant. Entries without a
     * vowel are left out; the gaps are [pause].
     */
    fun intervals(entries: List<OtoEntry>, lengthMs: Double, pause: String = "SP"): List<Triple<Double, Double, String>> {
        class Part(val start: Double, val vowelAt: Double, val end: Double, val s: Syllable)
        val parts = entries.mapNotNull { e ->
            val s = syllable(e.alias) ?: return@mapNotNull null
            val a = e.absolute(lengthMs)
            val end = a.right.coerceIn(a.left, lengthMs)
            val vowelAt = a.preutterance.coerceIn(a.left, end)
            val start = if (s.consonant.isEmpty()) vowelAt else a.overlap.coerceIn(a.left, vowelAt)
            Part(start, vowelAt, end, s)
        }.sortedBy { it.vowelAt }
        val out = mutableListOf<Triple<Double, Double, String>>()
        var t = 0.0
        for ((k, p) in parts.withIndex()) {
            val start = maxOf(p.start, t)
            val vowelAt = maxOf(p.vowelAt, start)
            val end = minOf(p.end, parts.getOrNull(k + 1)?.let { maxOf(it.start, vowelAt) } ?: p.end).coerceAtLeast(vowelAt)
            if (end <= vowelAt) continue
            if (start - t >= 1.0) out += Triple(t, start, pause)
            if (vowelAt > start) out += Triple(start, vowelAt, p.s.consonant)
            out += Triple(vowelAt, end, p.s.vowel)
            t = end
        }
        if (out.isNotEmpty() && lengthMs - t >= 1.0) out += Triple(t, lengthMs, pause)
        return out.map { Triple(it.first / 1000, it.second / 1000, it.third) }
    }
}
