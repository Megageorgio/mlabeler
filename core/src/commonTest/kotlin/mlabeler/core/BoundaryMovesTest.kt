package mlabeler.core

import mlabeler.core.edit.BoundaryMoves
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundaryMovesTest {
    @Test
    fun movesPhonemesAndWordsTogether() {
        val words = IntervalTier("words", listOf(0.0, 0.2, 0.6, 1.0), listOf("SP", "ka", "SP"))
        val phones = IntervalTier("phones", listOf(0.0, 0.2, 0.3, 0.6, 1.0), listOf("SP", "k", "a", "SP"))
        val doc = LabelDoc(listOf(words, phones))
        val r = BoundaryMoves.inside(phones, 0.0, 1.0)
        assertEquals(0..3, r)
        // 0.2 -> 0.21, 0.3 -> 0.29, 0.6 -> 0.62; the first start stays whatever is given
        val out = BoundaryMoves.apply(doc, 1, r, listOf(5.0, 0.21, 0.29, 0.62))
        assertEquals(listOf(0.0, 0.21, 0.29, 0.62, 1.0), (out.tiers[1] as IntervalTier).bounds)
        assertEquals(listOf(0.0, 0.21, 0.62, 1.0), (out.tiers[0] as IntervalTier).bounds)
        // a move across a neighbour is left out
        val bad = BoundaryMoves.apply(doc, 1, r, listOf(0.0, 0.35, 0.3, 0.6))
        assertEquals(phones.bounds, (bad.tiers[1] as IntervalTier).bounds)
        assertEquals(1..2, BoundaryMoves.inside(phones, 0.15, 0.65))
    }
}
