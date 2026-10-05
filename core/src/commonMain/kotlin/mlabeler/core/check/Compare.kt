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

    fun stats(main: IntervalTier, ref: IntervalTier): CompareStats {
        // the outer edges say little about alignment quality
        val d = boundDeltas(main, ref).let { if (it.size > 2) it.copyOfRange(1, it.size - 1) else it }.map { it * 1000 }.sorted()
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
