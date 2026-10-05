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

enum class OtoMarker { Left, Overlap, Preutterance, Consonant, Right }

fun OtoAbsolute.get(m: OtoMarker) = when (m) {
    OtoMarker.Left -> left
    OtoMarker.Overlap -> overlap
    OtoMarker.Preutterance -> preutterance
    OtoMarker.Consonant -> consonant
    OtoMarker.Right -> right
}

object OtoEdits {
    /**
     * Moves one marker to [valueMs] (absolute). With [locked] all markers move by the same amount
     * and stay inside the file. Single moves keep left ≤ preutterance, consonant ≤ right and nothing below 0.
     */
    fun move(a: OtoAbsolute, m: OtoMarker, valueMs: Double, lengthMs: Double, locked: Boolean): OtoAbsolute {
        if (locked) {
            val all = listOf(a.left, a.overlap, a.preutterance, a.consonant, a.right)
            val d = (valueMs - a.get(m)).coerceIn(-all.min(), lengthMs - all.max())
            return OtoAbsolute(a.left + d, a.overlap + d, a.preutterance + d, a.consonant + d, a.right + d)
        }
        val v = valueMs.coerceIn(0.0, lengthMs)
        return when (m) {
            // moving the left edge keeps the other markers where they are on the timeline
            OtoMarker.Left -> a.copy(left = v.coerceAtMost(minOf(a.preutterance, a.consonant, a.right)))
            OtoMarker.Overlap -> a.copy(overlap = v)
            OtoMarker.Preutterance -> a.copy(preutterance = v.coerceAtLeast(a.left))
            OtoMarker.Consonant -> a.copy(consonant = v.coerceIn(a.left, a.right))
            OtoMarker.Right -> a.copy(right = v.coerceAtLeast(maxOf(a.left, a.consonant)))
        }
    }

    /** Writes absolute positions back; the cutoff keeps its style (negative = length, positive = from the end). */
    fun set(e: OtoEntry, a: OtoAbsolute, lengthMs: Double): OtoEntry =
        OtoEntry.fromAbsolute(e.sample, e.alias, a, lengthMs, negativeCutoff = e.cutoff < 0)
}

/** How far two oto.ini files are apart, for entries with the same sample and alias (ms, absolute positions). */
data class OtoDiff(val matched: Int, val onlyHere: Int, val onlyThere: Int, val meanMs: Map<OtoMarker, Double>)

object OtoCompare {
    fun diff(mine: List<OtoEntry>, other: List<OtoEntry>): OtoDiff {
        val key = { e: OtoEntry -> e.sample.lowercase() + "|" + e.alias }
        val theirs = other.associateBy(key)
        val sums = mutableMapOf<OtoMarker, Double>()
        val counts = mutableMapOf<OtoMarker, Int>()
        var matched = 0
        for (e in mine) {
            val o = theirs[key(e)] ?: continue
            matched++
            // absolute positions; a positive cutoff needs the file length, so the end is compared only when both are lengths
            val a = e.absolute(0.0)
            val b = o.absolute(0.0)
            for (m in OtoMarker.entries) {
                if (m == OtoMarker.Right && (e.cutoff >= 0 || o.cutoff >= 0)) continue
                sums[m] = (sums[m] ?: 0.0) + kotlin.math.abs(a.get(m) - b.get(m))
                counts[m] = (counts[m] ?: 0) + 1
            }
        }
        val keysMine = mine.map(key).toSet()
        return OtoDiff(
            matched = matched,
            onlyHere = mine.count { key(it) !in theirs },
            onlyThere = other.count { key(it) !in keysMine },
            meanMs = sums.mapValues { (m, s) -> s / (counts[m] ?: 1) },
        )
    }
}
