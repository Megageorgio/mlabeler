package mlabeler.core.format

/**
 * Turns a text file someone already has (lyrics, a .lab, subtitles) into what an aligner takes:
 * words separated by spaces, or phonemes separated by spaces.
 */
object TextImport {
    private val labLine = Regex("""^\s*-?\d+(?:\.\d+)?\s+-?\d+(?:\.\d+)?\s+(\S+)""")
    private val lrcTag = Regex("""\[[^\]]*]""")
    private val curly = Regex("""\{[^}]*}""")
    private val srtTime = Regex("""^\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->.*$""")
    private val srtIndex = Regex("""^\s*\d+\s*$""")
    private val repeatMark = Regex("""(?i)(?:^|\s)[x×]\s?\d+(?=\s|$)|\(\s*[x×]\s?\d+\s*\)""")
    private val spaces = Regex("""\s+""")

    /** True when most non-empty lines look like "start end label" (a .lab file). */
    fun looksLikeLab(text: String): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.count { labLine.containsMatchIn(it) } * 2 > lines.size
    }

    /**
     * Cleans [text] for the aligner. [phonemes]: the result is a phoneme list (a .lab gives its labels as they are);
     * otherwise words: timestamps, tags, repeat marks and punctuation are removed, lines are joined.
     */
    fun clean(text: String, phonemes: Boolean): String {
        val body = text.removePrefix("﻿")
        if (looksLikeLab(body)) {
            return body.lines().mapNotNull { labLine.find(it)?.groupValues?.get(1) }.joinToString(" ")
        }
        val lines = body.lines()
            .filterNot { srtTime.matches(it) || srtIndex.matches(it) }
            .map { it.replace(lrcTag, " ").replace(curly, " ") }
        val joined = lines.joinToString(" ")
        if (phonemes) {
            // phonemes keep their own spelling: only separators go
            return joined.replace(',', ' ').replace(';', ' ').replace(spaces, " ").trim()
        }
        return joined.replace(repeatMark, " ")
            .map { if (keeps(it)) it else ' ' }.joinToString("")
            // a dash between spaces is punctuation, inside a word it stays
            .split(spaces).map { it.trim('-', '\'', '’') }.filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    // letters, marks, digits, apostrophes, hyphens and spaces stay; the rest is punctuation.
    // Done by hand: Kotlin/Native regex reads ' and \- inside a character class differently.
    private fun keeps(c: Char): Boolean = c.isLetterOrDigit() || c.isWhitespace() || c == '\'' || c == '’' || c == '-' ||
        c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK ||
        c.category == CharCategory.ENCLOSING_MARK || c.category == CharCategory.LETTER_NUMBER || c.category == CharCategory.OTHER_NUMBER
}
