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

    /** The mark a checked segmentation gets on its first line. */
    private const val REVISED = "REVISED!"

    fun looksLike(text: String): Boolean =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it != REVISED }.firstOrNull()?.startsWith("nPhonemes") == true

    /** Whether the segmentation is marked as checked. */
    fun revised(text: String): Boolean = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } == REVISED

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
        // an unnamed sliver shorter than the precision of the file (the end of the labels a hair before the end of
        // the sound) is not written
        val keep = kept(tier)
        if (old != null && revised(old)) line(REVISED)
        line("nPhonemes ${keep.size}")
        flags(old).forEach(::line)
        line(HEADER)
        // the rule line as it was (its length differs between tools)
        line(old?.lines()?.map { it.trimEnd('\r') }?.firstOrNull { it.trim().startsWith("===") } ?: RULE)
        // the last phoneme may have ended after the end of the sound (cut to it when read): its end stays as it was
        val oldLast = old?.let { runCatching { (read(it).tiers[0] as IntervalTier) }.getOrNull() }?.let { t -> Triple(t.startOf(t.size - 1), t.end, name(t.texts.last())) }
        for (i in keep) {
            var end = tier.endOf(i)
            if (i == keep.last() && oldLast != null && name(tier.texts[i]) == oldLast.third && fixed6(tier.startOf(i)) == fixed6(oldLast.first) &&
                oldLast.second > end && kotlin.math.abs(end - tier.end) < 1e-9) end = oldLast.second
            line(name(tier.texts[i]) + "\t\t" + fixed6(tier.startOf(i)) + "\t\t" + fixed6(end))
        }
        return out.toString()
    }

    fun phonemes(doc: LabelDoc, tierIndex: Int = doc.phonemeTierIndex()): List<String> {
        val tier = doc.tiers.getOrNull(tierIndex) as? IntervalTier ?: return emptyList()
        return kept(tier).map { name(tier.texts[it]) }
    }

    /**
     * The intervals written: not an unnamed sliver shorter than a millisecond, nor the unnamed rest after the last
     * named phoneme (the labels may end before the sound; it is not labelled, so it is left out as it was).
     */
    private fun kept(tier: IntervalTier): List<Int> {
        var last = tier.size - 1
        while (last > 0 && tier.texts[last].isBlank()) last--
        return (0..last).filter { i -> tier.texts[i].isNotBlank() || tier.durationOf(i) >= 0.001 }
    }

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

    /** The units of a transcription, each as its phonemes. */
    fun units(text: String): List<List<String>> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("[") }
            .map { it.removeSurrounding("[", "]").trim().split(Regex("\\s+")).filter { p -> p.isNotEmpty() } }.toList()

    /**
     * The transcription for [phonemes]. [units] given: exactly these units. Otherwise, with [old] given: unchanged
     * when its sequence is the same; else its kind of units (pairs or single phonemes) is kept. The line ending at
     * the end of the file stays as it was.
     */
    fun write(phonemes0: List<String>, old: String? = null, silence: String = SegFile.SILENCE, units: List<List<String>>? = null): String {
        // a transcription may leave out the silences at the start and end (the phonemes of the .seg have them)
        val oldSeq = old?.let { sequence(it) }
        val phonemes = if (oldSeq != null && oldSeq.isNotEmpty() && oldSeq.first() != silence && oldSeq.last() != silence)
            phonemes0.dropWhile { it == silence }.dropLastWhile { it == silence } else phonemes0
        if (units != null) {
            val lines = listOf(phonemes.joinToString(" ")) + units.map { "[" + it.joinToString(" ") + "]" }
            val text = lines.joinToString("\r\n") + if (old?.endsWith("\n") ?: false) "\r\n" else ""
            return if (old != null && old.replace("\r\n", "\n").trimEnd() == text.replace("\r\n", "\n").trimEnd()) old else text
        }
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

/**
 * The articulation segmentation next to a .seg file (.as0): for each unit of the transcription, the part of the
 * recording cut for it and where in that part the phonemes change:
 * ```
 * nphone art segmentation
 * {
 * 	phns: ["b'", "a"];
 * 	cut offset: 2816;          (samples)
 * 	cut length: 35072;         (samples)
 * 	boundaries: [0.348299320, 0.383129252, 0.406349206];   (seconds from the cut: start, each change, end)
 * 	revised: true;
 * 	voiced: [true, true];
 * };
 * ```
 */
object ArticulationFile {
    private const val HEADER = "nphone art segmentation"

    data class Block(
        val phonemes: List<String>,
        val cutOffset: Long,
        val cutLength: Long,
        val boundaries: List<Double>,
        val revised: Boolean,
        val voiced: List<Boolean>,
        /** Lines of other keys, kept as they were. */
        val other: List<String> = emptyList(),
    ) {
        /** The boundaries in seconds from the start of the recording. */
        fun times(sampleRate: Int): List<Double> = boundaries.map { cutOffset.toDouble() / sampleRate + it }
    }

    fun looksLike(text: String) = text.trimStart().startsWith(HEADER)

    fun read(text: String): List<Block> {
        if (!looksLike(text)) throw FormatException("Not an articulation segmentation file")
        val blocks = mutableListOf<Block>()
        for (m in Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            var phns = emptyList<String>(); var offset = 0L; var length = 0L; var bounds = emptyList<Double>()
            var revised = false; var voiced = emptyList<Boolean>(); val other = mutableListOf<String>()
            for (raw in m.groupValues[1].lines()) {
                val line = raw.trim().removeSuffix(";").trim()
                if (line.isEmpty()) continue
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                fun list() = value.removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                when (key) {
                    "phns" -> phns = list().map { it.removeSurrounding("\"") }
                    "cut offset" -> offset = value.toLongOrNull() ?: 0
                    "cut length" -> length = value.toLongOrNull() ?: 0
                    "boundaries" -> bounds = list().mapNotNull { it.toDoubleOrNull() }
                    "revised" -> revised = value == "true"
                    "voiced" -> voiced = list().map { it == "true" }
                    else -> other += raw.trim()
                }
            }
            blocks += Block(phns, offset, length, bounds, revised, voiced, other)
        }
        return blocks
    }

    fun write(blocks: List<Block>): String {
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append("\r\n") }
        line(HEADER)
        for (b in blocks) {
            line("{")
            line("\tphns: [" + b.phonemes.joinToString(", ") { "\"$it\"" } + "];")
            line("\tcut offset: ${b.cutOffset};")
            line("\tcut length: ${b.cutLength};")
            line("\tboundaries: [" + b.boundaries.joinToString(", ") { fixed9(it) } + "];")
            line("\trevised: ${b.revised};")
            line("\tvoiced: [" + b.voiced.joinToString(", ") + "];")
            for (o in b.other) line("\t" + o.removeSuffix(";") + ";")
            line("};")
        }
        return out.toString()
    }

    private fun fixed9(v: Double): String {
        val scaled = kotlin.math.round(kotlin.math.abs(v) * 1_000_000_000).toLong()
        val body = "${scaled / 1_000_000_000}." + (scaled % 1_000_000_000).toString().padStart(9, '0')
        return if (v < 0 && scaled != 0L) "-$body" else body
    }
}

/**
 * The pitch file next to a .seg file (.db0): "pitch <cents from A4> <a> <b>", the average pitch of the unit
 * (0: none found). The other two numbers are kept as they are.
 */
object UnitPitchFile {
    /** The pitch in Hz, or null when there is none. */
    fun hz(text: String): Double? {
        val p = text.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.firstOrNull() == "pitch" } ?: return null
        val cents = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (cents == 0.0) return null
        return 440.0 * kotlin.math.exp(kotlin.math.ln(2.0) * cents / 1200)
    }
}

/**
 * The units of a .trans (pairs of neighbouring phonemes, or single held phonemes) as a tier next to the phonemes,
 * so that only the wanted ones are kept: a pair is drawn from the middle of its first phoneme to the middle of
 * its second, a single phoneme over its middle half; the text is the phonemes with a space between them.
 */
object SegUnits {
    const val TIER = "units"

    /** Where the unit [unit] would be at phoneme [i] of [ph]: its start and end. */
    fun span(ph: IntervalTier, i: Int, size: Int): Pair<Double, Double> =
        if (size == 1) {
            val s = ph.startOf(i); val d = ph.durationOf(i)
            (s + d / 4) to (s + d * 3 / 4)
        } else (ph.startOf(i) + ph.endOf(i)) / 2 to (ph.startOf(i + size - 1) + ph.endOf(i + size - 1)) / 2

    private fun names(ph: IntervalTier) = ph.texts.map { it.trim().ifEmpty { SegFile.SILENCE } }

    /**
     * The tier for [units] in the order of the .trans; [near]: for each unit, a time it should be at (from the
     * articulation file), else the first place not taken yet.
     */
    fun tier(ph: IntervalTier, units: List<List<String>>, near: List<Double?> = emptyList()): IntervalTier {
        val names = names(ph)
        val taken = mutableSetOf<Int>()
        val items = mutableListOf<Triple<Double, Double, String>>()
        for ((k, u) in units.withIndex()) {
            if (u.isEmpty()) continue
            val places = (0..names.size - u.size).filter { i -> names.subList(i, i + u.size) == u }
            if (places.isEmpty()) continue
            val t = near.getOrNull(k)
            val i = if (t != null) places.minBy { i -> kotlin.math.abs((ph.startOf(i) + ph.endOf(i + u.size - 1)) / 2 - t) }
            else places.firstOrNull { it !in taken } ?: places.first()
            taken += i
            val (s, e) = span(ph, i, u.size)
            items += Triple(s, e, u.joinToString(" "))
        }
        return nonOverlapping(TIER, items, ph.start, ph.end)
    }

    /** The units of [units] (a units tier), in time order. */
    fun units(units: IntervalTier): List<List<String>> =
        units.texts.filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }

    /** The phoneme index each named interval of [units] stands on (its middle), or -1. */
    fun places(units: IntervalTier, ph: IntervalTier): List<Pair<List<String>, Int>> =
        (0 until units.size).filter { units.texts[it].isNotBlank() }.map { j ->
            val u = units.texts[j].trim().split(Regex("\\s+"))
            val first = ph.indexAt(units.startOf(j) + 1e-9)
            u to first
        }

    /**
     * Adds the unit of [size] phonemes starting at phoneme [i] of [ph] to [units] (null: no tier yet), or takes it
     * away when it is there.
     */
    fun toggle(units: IntervalTier?, ph: IntervalTier, i: Int, size: Int): IntervalTier {
        val names = names(ph)
        if (i < 0 || i + size > names.size) return units ?: nonOverlapping(TIER, emptyList(), ph.start, ph.end)
        val text = names.subList(i, i + size).joinToString(" ")
        val (s, e) = span(ph, i, size)
        val items = mutableListOf<Triple<Double, Double, String>>()
        var removed = false
        if (units != null) for (j in 0 until units.size) {
            if (units.texts[j].isBlank()) continue
            val mid = (units.startOf(j) + units.endOf(j)) / 2
            if (units.texts[j].trim() == text && mid > s - 1e-6 && mid < e + 1e-6) { removed = true; continue }
            items += Triple(units.startOf(j), units.endOf(j), units.texts[j])
        }
        if (!removed) items += Triple(s, e, text)
        return nonOverlapping(TIER, items, ph.start, ph.end)
    }

    /** An interval tier from [items] that may overlap: a later start is moved to the end of the one before. */
    private fun nonOverlapping(name: String, items: List<Triple<Double, Double, String>>, start: Double, end: Double): IntervalTier {
        val sorted = items.sortedBy { it.first }
        val bounds = mutableListOf(start)
        val texts = mutableListOf<String>()
        for ((s0, e, t) in sorted) {
            val s = maxOf(s0, bounds.last())
            if (e <= s + 1e-6) continue
            if (s > bounds.last() + 1e-9) { texts += ""; bounds += s }
            texts += t; bounds += e
        }
        if (end > bounds.last() + 1e-9) { texts += ""; bounds += end }
        if (texts.isEmpty()) { texts += ""; bounds += maxOf(end, start + 1e-3) }
        return IntervalTier(name, bounds, texts)
    }

    /** Widths of the change between two phonemes in an articulation: before and after the boundary. */
    data class Widths(val beforeMs: Double = 35.0, val afterMs: Double = 23.0, val marginMs: Double = 300.0)

    /**
     * The blocks of the articulation file for the units now chosen (units of two or more phonemes). A unit that
     * had a block keeps it while its phonemes are where they were in [oldPh] (the labels as last saved); a new unit
     * or one whose phonemes moved gets a new block made from the phonemes: the cut from [Widths.marginMs] before
     * its first phoneme to as long after its last, a boundary at each change of phoneme, the change starting
     * [Widths.beforeMs] before the first one and ending [Widths.afterMs] after the last, all on the grid of 256
     * samples; [voiced] says whether a stretch of time has pitch.
     */
    fun blocksFor(
        old: List<ArticulationFile.Block>, units: IntervalTier, ph: IntervalTier, oldPh: IntervalTier?,
        sampleRate: Int, samples: Int, voiced: (Double, Double) -> Boolean, widths: Widths = Widths(),
    ): List<ArticulationFile.Block> {
        val names = names(ph)
        val oldNames = oldPh?.let { names(it) }
        val used = mutableSetOf<Int>()
        val out = mutableListOf<ArticulationFile.Block>()
        for ((u, i) in places(units, ph)) {
            if (u.size < 2 || i < 0 || i + u.size > names.size || names.subList(i, i + u.size) != u) continue
            val s = ph.startOf(i); val e = ph.endOf(i + u.size - 1)
            val k = old.indices.firstOrNull { k ->
                k !in used && old[k].phonemes == u && old[k].times(sampleRate).let { t -> t.size >= 2 && t[t.size / 2] in (s - 0.05)..(e + 0.05) }
            }
            val unchanged = oldPh != null && oldNames != null && i + u.size <= oldNames.size && oldNames.subList(i, i + u.size) == u &&
                (i..i + u.size).all { j -> kotlin.math.abs(oldPh.bounds[j] - ph.bounds[j]) < 1e-6 }
            // labels not changed here: the block stays as it was, even when it doesn't fit them (it may have been
            // made or checked elsewhere after them)
            val same = if (unchanged) k ?: old.indices.firstOrNull { it !in used && old[it].phonemes == u } else null
            if (same != null) {
                used += same
                out += old[same]
                continue
            }
            if (k != null) used += k
            out += make(ph, i, u, sampleRate, samples, voiced, widths, k?.let { old[it] })
        }
        return out
    }

    private fun make(
        ph: IntervalTier, i: Int, u: List<String>, sr: Int, samples: Int, voiced: (Double, Double) -> Boolean,
        w: Widths, old: ArticulationFile.Block?,
    ): ArticulationFile.Block {
        val g = 256L
        fun down(t: Double) = kotlin.math.floor(t * sr / g).toLong() * g
        fun up(t: Double) = kotlin.math.ceil(t * sr / g).toLong() * g
        fun near(t: Double) = kotlin.math.round(t * sr / g).toLong() * g
        val first = ph.startOf(i)
        val last = ph.endOf(i + u.size - 1)
        val maxEnd = samples.toLong() / g * g
        val off = down(maxOf(0.0, first - w.marginMs / 1000)).coerceAtLeast(0)
        val end = minOf(maxEnd, up(last + w.marginMs / 1000)).coerceAtLeast(off + g)
        // the changes of phoneme, then the start and end of the whole change
        val mids = (1 until u.size).map { near(ph.startOf(i + it)) }.toMutableList()
        val lo = up(first)
        val hi = down(last)
        val b0 = maxOf(mids.first() - (w.beforeMs / 1000 * sr / g).toLong().coerceAtLeast(1) * g, lo).let { if (it >= mids.first()) mids.first() - g else it }
        val bn = minOf(mids.last() + (w.afterMs / 1000 * sr / g).toLong().coerceAtLeast(1) * g, hi).let { if (it <= mids.last()) mids.last() + g else it }
        val all = listOf(b0) + mids + bn
        // voiced: whether each phoneme has pitch
        val voicedList = (u.indices).map { k -> voiced(ph.startOf(i + k), ph.endOf(i + k)) }
        return ArticulationFile.Block(
            phonemes = u, cutOffset = off, cutLength = end - off,
            boundaries = all.map { (it - off).toDouble() / sr },
            revised = true, voiced = voicedList, other = old?.other ?: emptyList(),
        )
    }
}
