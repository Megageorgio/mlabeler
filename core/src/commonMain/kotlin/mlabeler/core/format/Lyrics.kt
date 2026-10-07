package mlabeler.core.format

/** Cleaning recognised lyrics: phrases the recognizer invents, and long phrases cut into lines that fit a screen. */
object Lyrics {
    /**
     * Whisper was trained on video subtitles and, over music or silence, writes their credits and sign-offs.
     * Such a phrase is dropped from the lyrics.
     */
    private val invented = listOf(
        Regex("""субтитр\p{L}*\s+(сделал|создавал|делал|подготовил|by)""", RegexOption.IGNORE_CASE),
        Regex("""редактор\s+субтитров""", RegexOption.IGNORE_CASE),
        Regex("""корректор\s+[А-ЯA-Z]""", RegexOption.IGNORE_CASE),
        Regex("""продолжение\s+следует""", RegexOption.IGNORE_CASE),
        Regex("""подпис\p{L}+\s+на\s+(канал|наш)""", RegexOption.IGNORE_CASE),
        Regex("""спасибо\s+за\s+просмотр""", RegexOption.IGNORE_CASE),
        Regex("""(thanks|thank\s+you)\s+for\s+watching""", RegexOption.IGNORE_CASE),
        Regex("""subtitles\s+(by|made)""", RegexOption.IGNORE_CASE),
        Regex("""please\s+subscribe""", RegexOption.IGNORE_CASE),
        Regex("""amara\.org""", RegexOption.IGNORE_CASE),
        Regex("""ご視聴ありがとうございました"""),
        Regex("""字幕""", RegexOption.IGNORE_CASE),
    )

    fun isInvented(text: String): Boolean = invented.any { it.containsMatchIn(text) }

    /** Removes invented sentences from [text] (the rest of it is kept). */
    fun clean(text: String): String =
        sentences(text).filterNot { isInvented(it) }.joinToString(" ").trim()

    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in ".!?…") { out += sb.toString().trim(); sb.clear() }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Cuts a recognised phrase into lyric lines: at the end of a sentence, before a capital letter that starts a new
     * line without punctuation ("…пора Шипели злые…"), and at commas or spaces when a line gets longer than [maxChars].
     * Each line gets a start time in proportion to where its text is in the phrase.
     */
    fun split(start: Double, end: Double, text: String, maxChars: Int = 60): List<Pair<Double, String>> {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return emptyList()
        val words = t.split(' ')
        val parts = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) parts += cur.toString().trim(); cur = StringBuilder() }
        for ((i, w) in words.withIndex()) {
            val prev = words.getOrNull(i - 1)
            val newLine = prev != null && cur.isNotEmpty() && (
                prev.last() in ".!?…" ||
                    // a capital after a word that doesn't end a sentence: lyrics lines glued together
                    (w.first().isUpperCase() && prev.last().isLetter() && prev.first().isLowerCase() && cur.length >= 12)
                )
            if (newLine) flush()
            if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) {
                // too long: back to the last comma if it leaves a reasonable line
                val s = cur.toString()
                val comma = s.lastIndexOf(", ")
                if (comma >= maxChars / 3) {
                    parts += s.substring(0, comma + 1).trim()
                    cur = StringBuilder(s.substring(comma + 2))
                } else flush()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        flush()
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        val span = (end - start).coerceAtLeast(0.0)
        var acc = 0
        return parts.map { p ->
            val at = start + span * acc / total
            acc += p.length
            at to p
        }
    }
}
