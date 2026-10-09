package mlabeler.core.edit

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/** Moving boundaries found by a boundary refiner back into a labelling. */
object BoundaryMoves {
    private const val EPS = 1e-6

    /** The intervals of [tier] lying inside [from]..[to]: their index range (empty when none). */
    fun inside(tier: IntervalTier, from: Double, to: Double): IntRange {
        val idx = (0 until tier.size).filter { i -> tier.startOf(i) >= from - EPS && tier.endOf(i) <= to + EPS }
        return if (idx.isEmpty()) IntRange.EMPTY else idx.first()..idx.last()
    }

    /**
     * [doc] with the inner boundaries of the intervals [range] of tier [k] moved to [newStarts] (the new start of each
     * interval of the range, in order; the first one, the start of the range, stays). A boundary of another interval
     * tier that sat at a moved one moves with it. A move that would cross a neighbouring boundary is left out.
     */
    fun apply(doc: LabelDoc, k: Int, range: IntRange, newStarts: List<Double>): LabelDoc {
        val tier = doc.tiers.getOrNull(k) as? IntervalTier ?: return doc
        if (range.isEmpty() || newStarts.size != range.count()) return doc
        val bounds = tier.bounds.toMutableList()
        val moves = mutableMapOf<Double, Double>()
        for ((n, i) in range.withIndex()) {
            if (n == 0) continue
            val old = tier.startOf(i)
            val new = newStarts[n]
            if (kotlin.math.abs(new - old) < EPS) continue
            if (new <= bounds[i - 1] + EPS || new >= bounds[i + 1] - EPS) continue
            bounds[i] = new
            moves[old] = new
        }
        if (moves.isEmpty()) return doc
        return doc.copy(tiers = doc.tiers.mapIndexed { j, t ->
            when {
                j == k -> tier.copy(bounds = bounds)
                t is IntervalTier -> {
                    val b = t.bounds.toMutableList()
                    for (x in 1 until b.size - 1) {
                        val to = moves.entries.firstOrNull { kotlin.math.abs(it.key - b[x]) < EPS }?.value ?: continue
                        if (to > b[x - 1] + EPS && to < b[x + 1] - EPS) b[x] = to
                    }
                    t.copy(bounds = b)
                }
                else -> t
            }
        })
    }
}
