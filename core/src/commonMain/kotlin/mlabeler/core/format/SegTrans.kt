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
 * The transitions of a .trans (the bracketed units: changes between neighbouring phonemes, or single held phonemes)
 * as a lane next to the phonemes, drawn as their articulation files have them: a transition of n phonemes is n
 * intervals named after its phonemes, from where the change starts, through each change of phoneme, to where it
 * ends; transitions are apart (an unnamed gap between them) or touch. The k-th transition of the .trans has its
 * articulation in the file with the extension .as<k> (.as0, .as1…).
 */
object SegUnits {
    const val TIER = "transitions"

    /** A transition on the lane: its phonemes, the phoneme of the .seg it starts in, its boundaries (seconds). */
    data class Unit(val phonemes: List<String>, val index: Int, val bounds: List<Double>)

    /** Widths of a new transition: how far before and after the change of phoneme it reaches. */
    data class Widths(val beforeMs: Double = 35.0, val afterMs: Double = 23.0)

    private fun names(ph: IntervalTier) = ph.texts.map { it.trim().ifEmpty { SegFile.SILENCE } }

    /** The phoneme names of [ph] as the .seg writes them (an unnamed interval is a silence). */
    fun phonemeNames(ph: IntervalTier): List<String> = names(ph)

    /** Where a new transition of [size] phonemes from phoneme [i] of [ph] goes: a little around each change. */
    fun defaultBounds(ph: IntervalTier, i: Int, size: Int, w: Widths = Widths()): List<Double> {
        if (size == 1) {
            val s = ph.startOf(i); val d = ph.durationOf(i)
            return listOf(s + d / 4, s + d * 3 / 4)
        }
        val mids = (1 until size).map { ph.startOf(i + it) }
        val first = ph.startOf(i); val last = ph.endOf(i + size - 1)
        val b0 = maxOf(mids.first() - w.beforeMs / 1000, first + (mids.first() - first) / 4)
        val bn = minOf(mids.last() + w.afterMs / 1000, last - (last - mids.last()) / 4)
        return listOf(b0) + mids + bn
    }

    /** The lane for [units] (phonemes, and their boundaries when known), each at the first free place it fits. */
    fun lane(ph: IntervalTier, units: List<Pair<List<String>, List<Double>?>>): IntervalTier {
        val names = names(ph)
        val taken = mutableSetOf<Int>()
        val items = mutableListOf<Triple<Double, Double, String>>()
        for ((u, known) in units) {
            if (u.isEmpty()) continue
            val bounds = known?.takeIf { it.size == u.size + 1 } ?: run {
                val places = (0..names.size - u.size).filter { i -> names.subList(i, i + u.size) == u }
                val i = places.firstOrNull { it !in taken } ?: places.firstOrNull() ?: return@run null
                taken += i
                defaultBounds(ph, i, u.size)
            } ?: continue
            known?.let { b -> ph.indexAt((b.first() + b.last()) / 2).let { taken += it } }
            for (k in u.indices) items += Triple(bounds[k], bounds[k + 1], u[k])
        }
        return nonOverlapping(TIER, items, ph.start, ph.end)
    }

    /**
     * The transitions on [lane]: runs of named intervals. A run goes on while each next interval is named after the
     * phoneme of the .seg that follows the one before; otherwise (two transitions that touch) a new one starts.
     */
    fun parse(lane: IntervalTier, ph: IntervalTier): List<Unit> {
        val names = names(ph)
        val out = mutableListOf<Unit>()
        var cur = mutableListOf<Int>()
        var start = -1
        var idx = -1
        // the phoneme an interval named [text] stands for: of that name, the nearest to it
        fun phonemeOf(j: Int): Int {
            val text = lane.texts[j].trim()
            val a = lane.startOf(j); val b = lane.endOf(j)
            // the named intervals that follow it without a gap
            val ahead = mutableListOf(text)
            var k = j + 1
            while (k < lane.size && lane.texts[k].isNotBlank()) { ahead += lane.texts[k].trim(); k++ }
            fun match(i: Int): Int { var m = 0; while (m < ahead.size && names.getOrNull(i + m) == ahead[m]) m++; return m }
            // where most of them follow in the .seg, then the most overlap with the interval
            fun overlap(i: Int) = minOf(b, ph.endOf(i)) - maxOf(a, ph.startOf(i))
            return names.indices.filter { names[it] == text }.maxWithOrNull(compareBy<Int>({ minOf(match(it), 2) }, { overlap(it) }))
                ?: ph.indexAt((a + b) / 2)
        }
        fun flush() {
            if (cur.isEmpty()) return
            out += Unit(cur.map { lane.texts[it].trim() }, start, listOf(lane.startOf(cur.first())) + cur.map { lane.endOf(it) })
            cur = mutableListOf()
        }
        for (j in 0 until lane.size) {
            val text = lane.texts[j].trim()
            if (text.isEmpty()) { flush(); continue }
            if (cur.isNotEmpty() && names.getOrNull(idx + 1) == text) { cur += j; idx++; continue }
            flush()
            cur += j
            start = phonemeOf(j)
            idx = start
        }
        flush()
        return out
    }

    /** The phonemes of each transition on [lane], in time order (what the .trans lists). */
    fun units(lane: IntervalTier, ph: IntervalTier): List<List<String>> = parse(lane, ph).map { it.phonemes }

    /** The places (first phoneme, number of phonemes) of the transitions on [lane]. */
    fun placesOf(lane: IntervalTier, ph: IntervalTier): List<Pair<Int, Int>> = parse(lane, ph).map { it.index to it.phonemes.size }

    /** Adds the transition of [size] phonemes from phoneme [i] (a little around its changes), or takes it away. */
    fun toggle(lane: IntervalTier?, ph: IntervalTier, i: Int, size: Int): IntervalTier {
        val names = names(ph)
        val have = lane?.let { parse(it, ph) }.orEmpty()
        val there = have.any { it.index == i && it.phonemes.size == size }
        val kept = have.filterNot { it.index == i && it.phonemes.size == size }.map { it.phonemes to it.bounds }
        if (there || i < 0 || i + size > names.size) return rebuild(kept, ph)
        return rebuild(kept + (names.subList(i, i + size) to defaultBounds(ph, i, size)), ph)
    }

    /** The lane with only the transitions at [places]: those already on [old] stay as they are, new ones are added. */
    fun tierAt(ph: IntervalTier, places: List<Pair<Int, Int>>, old: IntervalTier?): IntervalTier {
        val names = names(ph)
        val have = old?.let { parse(it, ph) }.orEmpty()
        val want = places.toSet()
        val kept = have.filter { (it.index to it.phonemes.size) in want }
        val added = want.filter { p -> kept.none { it.index == p.first && it.phonemes.size == p.second } }
            .filter { (i, n) -> i >= 0 && n >= 1 && i + n <= names.size }
            .map { (i, n) -> names.subList(i, i + n) to defaultBounds(ph, i, n) }
        return rebuild(kept.map { it.phonemes to it.bounds } + added, ph)
    }

    private fun rebuild(units: List<Pair<List<String>, List<Double>>>, ph: IntervalTier): IntervalTier {
        val items = units.flatMap { (u, b) -> u.indices.map { k -> Triple(b[k], b[k + 1], u[k]) } }
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

    /**
     * The articulation of transition [u] for its file: [old] (the file as it was) when the transition is where it
     * was; otherwise the boundaries of the lane on the grid of 256 samples, in a cut from 300 ms before its first
     * phoneme (of [ph]) to 300 ms after its last (the old cut while it still holds them); [voiced] tells whether a
     * stretch has pitch.
     */
    fun block(u: Unit, ph: IntervalTier, old: ArticulationFile.Block?, sampleRate: Int, samples: () -> Int, voiced: (Double, Double) -> Boolean): ArticulationFile.Block {
        if (old != null && old.phonemes == u.phonemes) {
            val t = old.times(sampleRate)
            if (t.size == u.bounds.size && t.zip(u.bounds).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 }) return old
        }
        val g = 256L
        val sr = sampleRate
        fun near(t: Double) = kotlin.math.round(t * sr / g).toLong() * g
        val grid = u.bounds.map { near(it) }.toMutableList()
        for (k in 1 until grid.size) if (grid[k] <= grid[k - 1]) grid[k] = grid[k - 1] + g
        val maxEnd = samples().toLong() / g * g
        val i = u.index.coerceIn(0, ph.size - 1)
        val last = (i + u.phonemes.size - 1).coerceIn(0, ph.size - 1)
        var off = kotlin.math.floor(maxOf(0.0, ph.startOf(i) - 0.3) * sr / g).toLong() * g
        var end = minOf(maxEnd, kotlin.math.ceil((ph.endOf(last) + 0.3) * sr / g).toLong() * g)
        if (old != null && old.cutOffset <= grid.first() && old.cutOffset + old.cutLength >= grid.last()) { off = old.cutOffset; end = old.cutOffset + old.cutLength }
        off = minOf(off, grid.first())
        end = maxOf(end, grid.last())
        val voicedList = u.phonemes.indices.map { k -> voiced(ph.startOf((i + k).coerceAtMost(ph.size - 1)), ph.endOf((i + k).coerceAtMost(ph.size - 1))) }
        return ArticulationFile.Block(
            phonemes = u.phonemes, cutOffset = off, cutLength = end - off,
            boundaries = grid.map { (it - off).toDouble() / sr }, revised = true, voiced = voicedList,
            other = old?.other ?: emptyList(),
        )
    }
}
