package mlabeler.core.format

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/**
 * Phoneme segmentation in a .seg text file:
 * ```
 * nPhonemes 3
 * <flag lines, kept as read>
 * phoneme		BeginTime		EndTime
 * =================================================
 * Sil		0.000000		0.328000
 * ```
 * Lines end with CRLF, columns are separated by two tabs, times are seconds with six decimals.
 */
object SegFile {
    private const val HEADER = "phoneme\t\tBeginTime\t\tEndTime"
    private const val RULE = "================================================="
    /** The name the format uses for silence; an unnamed interval is written with it. */
    const val SILENCE = "Sil"

    fun looksLike(text: String): Boolean =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.startsWith("nPhonemes") == true

    fun read(text: String, duration: Double? = null): LabelDoc {
        val lines = text.lines().map { it.trimEnd('\r') }
        val rule = lines.indexOfFirst { it.trim().startsWith("===") }
        if (!looksLike(text) || rule < 0) throw FormatException("Not a phoneme segmentation file")
        val items = mutableListOf<Triple<Double, Double, String>>()
        for (n in rule + 1 until lines.size) {
            val line = lines[n].trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) throw FormatException("Line ${n + 1}: expected \"phoneme begin end\"")
            val s = parts[parts.size - 2].toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad begin time")
            val e = parts[parts.size - 1].toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad end time")
            items += Triple(s, e, parts.dropLast(2).joinToString(" "))
        }
        return LabelDoc(listOf(IntervalTier.fromIntervals("phones", items, duration)))
    }

    /** The lines between "nPhonemes" and the column header of [old] (kept as they were); a default when there is none. */
    private fun flags(old: String?): List<String> {
        val lines = old?.lines()?.map { it.trimEnd('\r') } ?: return listOf("articulationsAreStationaries 0")
        val first = lines.indexOfFirst { it.trim().startsWith("nPhonemes") }
        val header = lines.indexOfFirst { it.trim().startsWith("phoneme") }
        if (first < 0 || header <= first) return listOf("articulationsAreStationaries 0")
        return lines.subList(first + 1, header)
    }

    fun write(doc: LabelDoc, old: String? = null, tierIndex: Int = doc.phonemeTierIndex()): String {
        val tier = doc.tiers.getOrNull(tierIndex) as? IntervalTier ?: throw FormatException("No interval tier to write")
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append("\r\n") }
        line("nPhonemes ${tier.size}")
        flags(old).forEach(::line)
        line(HEADER)
        line(RULE)
        for (i in 0 until tier.size) {
            line(name(tier.texts[i]) + "\t\t" + fixed6(tier.startOf(i)) + "\t\t" + fixed6(tier.endOf(i)))
        }
        return out.toString()
    }

    fun phonemes(doc: LabelDoc, tierIndex: Int = doc.phonemeTierIndex()): List<String> =
        (doc.tiers.getOrNull(tierIndex) as? IntervalTier)?.texts?.map(::name) ?: emptyList()

    private fun name(text: String) = text.trim().ifEmpty { SILENCE }.replace(Regex("\\s+"), "_")

    private fun fixed6(v: Double): String {
        val scaled = kotlin.math.round(kotlin.math.abs(v) * 1_000_000).toLong()
        val body = "${scaled / 1_000_000}." + (scaled % 1_000_000).toString().padStart(6, '0')
        return if (v < 0 && scaled != 0L) "-$body" else body
    }
}

/**
 * The transcription next to a .seg file: the phonemes in one line, then the units cut from the recording, one per
 * line in brackets (pairs of neighbouring phonemes, "[k a]", or single held phonemes, "[a]").
 */
object TransFile {
    fun sequence(text: String): List<String> =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()

    /**
     * The transcription for [phonemes]. With [old] given: unchanged when its sequence is the same; otherwise its
     * kind of units (pairs or single phonemes) and its line ending at the end of the file are kept.
     */
    fun write(phonemes: List<String>, old: String? = null, silence: String = SegFile.SILENCE): String {
        if (old != null && sequence(old) == phonemes) return old
        val units = old?.lineSequence()?.map { it.trim() }?.filter { it.startsWith("[") }?.toList() ?: emptyList()
        val single = units.isNotEmpty() && units.all { it.removeSurrounding("[", "]").trim().split(Regex("\\s+")).size == 1 }
        val lines = mutableListOf(phonemes.joinToString(" "))
        if (single) phonemes.filter { it != silence }.forEach { lines += "[$it]" }
        else phonemes.zipWithNext().forEach { (a, b) -> lines += "[$a $b]" }
        val endsWithNewline = old?.endsWith("\n") ?: false
        return lines.joinToString("\r\n") + if (endsWithNewline) "\r\n" else ""
    }
}
