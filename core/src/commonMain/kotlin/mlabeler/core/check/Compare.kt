package mlabeler.core.check

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.math.abs

/** How a reference labelling differs from the one being edited. */
data class CompareStats(
    val bounds: Int,
    val meanMs: Double,
    val medianMs: Double,
    /** Share of boundaries closer than 20 ms. */
    val within20: Double,
    val textMismatches: Int,
    val intervals: Int,
)

object Compare {
    /** The tier of [doc] to compare [ref] with: same name, else the phoneme tier. */
    fun counterpart(doc: LabelDoc, ref: IntervalTier): IntervalTier? {
        val k = doc.tierIndex(ref.name).takeIf { it >= 0 && doc.tiers[it] is IntervalTier } ?: doc.phonemeTierIndex()
        return doc.tiers.getOrNull(k) as? IntervalTier
    }

    /** Distance in seconds from each boundary of [ref] to the nearest boundary of [main]. */
    fun boundDeltas(main: IntervalTier, ref: IntervalTier): DoubleArray =
        DoubleArray(ref.bounds.size) { i -> val t = ref.bounds[i]; abs(main.bounds[main.nearestBound(t)] - t) }

    /** For each interval of [ref]: true when [main] has another text at its middle. */
    fun textMismatch(main: IntervalTier, ref: IntervalTier): BooleanArray = BooleanArray(ref.size) { i ->
        val mid = (ref.startOf(i) + ref.endOf(i)) / 2
        val k = main.indexAt(mid)
        k < 0 || main.texts[k] != ref.texts[i]
    }

    /**
     * Times (seconds, in order) worth a look: boundaries of [ref] at least [minMs] from any of [main], and the
     * middles of intervals with another text. The outer edges are left out.
     */
    fun differences(main: IntervalTier, ref: IntervalTier, minMs: Double = 30.0): List<Double> {
        val d = boundDeltas(main, ref)
        val out = mutableListOf<Double>()
        for (i in 1 until d.size - 1) if (d[i] * 1000 >= minMs) out += ref.bounds[i]
        val mism = textMismatch(main, ref)
        for (i in mism.indices) if (mism[i]) out += (ref.startOf(i) + ref.endOf(i)) / 2
        return out.sorted().fold(mutableListOf()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 0.005) acc += t; acc }
    }

    /** [stats] over many files at once: the boundaries of all of them pooled. */
    fun pooled(pairs: List<Pair<IntervalTier, IntervalTier>>): CompareStats {
        val d = pairs.flatMap { (main, ref) -> innerDeltasMs(main, ref) }.sorted()
        return CompareStats(
            bounds = d.size,
            meanMs = if (d.isEmpty()) 0.0 else d.average(),
            medianMs = if (d.isEmpty()) 0.0 else d[d.size / 2],
            within20 = if (d.isEmpty()) 1.0 else d.count { it < 20 }.toDouble() / d.size,
            textMismatches = pairs.sumOf { (main, ref) -> textMismatch(main, ref).count { it } },
            intervals = pairs.sumOf { it.second.size },
        )
    }

    // the outer edges say little about alignment quality
    private fun innerDeltasMs(main: IntervalTier, ref: IntervalTier): List<Double> =
        boundDeltas(main, ref).let { if (it.size > 2) it.copyOfRange(1, it.size - 1) else it }.map { it * 1000 }

    fun stats(main: IntervalTier, ref: IntervalTier): CompareStats {
        val d = innerDeltasMs(main, ref).sorted()
        val mism = textMismatch(main, ref).count { it }
        return CompareStats(
            bounds = d.size,
            meanMs = if (d.isEmpty()) 0.0 else d.average(),
            medianMs = if (d.isEmpty()) 0.0 else d[d.size / 2],
            within20 = if (d.isEmpty()) 1.0 else d.count { it < 20 }.toDouble() / d.size,
            textMismatches = mism,
            intervals = ref.size,
        )
    }
}
