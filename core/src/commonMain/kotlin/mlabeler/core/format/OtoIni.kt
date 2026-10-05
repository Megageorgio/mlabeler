package mlabeler.core.format

/**
 * One line of an UTAU oto.ini: `sample=alias,offset,consonant,cutoff,preutterance,overlap`, values in ms.
 *
 * - offset: from the file start; the other values are relative to it.
 * - consonant (fixed): end of the fixed part, from offset.
 * - cutoff: negative = length from offset; zero or positive = distance from the end of the file.
 * - preutterance, overlap: from offset; overlap may be negative.
 */
data class OtoEntry(
    val sample: String,
    val alias: String,
    val offset: Double,
    val consonant: Double,
    val cutoff: Double,
    val preutterance: Double,
    val overlap: Double,
) {
    /** Absolute end in ms given the sample length (ms). */
    fun endMs(sampleLengthMs: Double): Double = if (cutoff < 0) offset - cutoff else sampleLengthMs - cutoff

    /** Absolute positions in ms: left, overlap, preutterance, consonant end, right. */
    fun absolute(sampleLengthMs: Double) = OtoAbsolute(
        left = offset,
        overlap = offset + overlap,
        preutterance = offset + preutterance,
        consonant = offset + consonant,
        right = endMs(sampleLengthMs),
    )

    companion object {
        /** Builds an entry from absolute positions; [negativeCutoff] chooses how the end is written. */
        fun fromAbsolute(
            sample: String,
            alias: String,
            a: OtoAbsolute,
            sampleLengthMs: Double,
            negativeCutoff: Boolean = true,
        ) = OtoEntry(
            sample = sample,
            alias = alias,
            offset = a.left,
            consonant = a.consonant - a.left,
            cutoff = if (negativeCutoff) -(a.right - a.left) else sampleLengthMs - a.right,
            preutterance = a.preutterance - a.left,
            overlap = a.overlap - a.left,
        )
    }
}

data class OtoAbsolute(val left: Double, val overlap: Double, val preutterance: Double, val consonant: Double, val right: Double)

object OtoIni {
    fun read(text: String): List<OtoEntry> {
        val out = ArrayList<OtoEntry>()
        for ((n, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) throw FormatException("Line ${n + 1}: expected sample=alias,...")
            val sample = line.substring(0, eq)
            val parts = line.substring(eq + 1).split(',')
            fun num(i: Int) = parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
            out += OtoEntry(sample, parts.getOrElse(0) { "" }, num(1), num(2), num(3), num(4), num(5))
        }
        return out
    }

    fun write(entries: List<OtoEntry>, decimals: Int = 3, lineEnding: String = "\r\n"): String = buildString {
        for (e in entries) {
            append(e.sample).append('=').append(e.alias).append(',')
            append(formatNumber(e.offset, decimals)).append(',')
            append(formatNumber(e.consonant, decimals)).append(',')
            append(formatNumber(e.cutoff, decimals)).append(',')
            append(formatNumber(e.preutterance, decimals)).append(',')
            append(formatNumber(e.overlap, decimals)).append(lineEnding)
        }
    }
}
