package mlabeler.core

import mlabeler.core.check.Compare
import mlabeler.core.model.IntervalTier
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareTest {
    private val main = IntervalTier("phones", listOf(0.0, 0.5, 1.0, 1.5, 2.0), listOf("SP", "a", "k", "SP"))

    @Test
    fun differencesAreFarBoundariesAndOtherTexts() {
        // 1.0 is 40 ms away, 0.5 only 10 ms; the third interval has another text
        val ref = IntervalTier("phones", listOf(0.0, 0.51, 1.04, 1.5, 2.0), listOf("SP", "a", "g", "SP"))
        val d = Compare.differences(main, ref)
        assertEquals(2, d.size)
        assertEquals(1.04, d[0], 1e-9)
        assertEquals((1.04 + 1.5) / 2, d[1], 1e-9)
        assertEquals(0, Compare.differences(main, main).size)
    }

    @Test
    fun pooledCountsAllBoundaries() {
        val ref = IntervalTier("phones", listOf(0.0, 0.52, 1.0, 1.5, 2.0), listOf("SP", "a", "k", "SP"))
        val st = Compare.pooled(listOf(main to ref, main to main))
        assertEquals(6, st.bounds)
        assertEquals(20.0 / 6, st.meanMs, 1e-6)
        assertEquals(5.0 / 6, st.within20, 1e-9)
    }
}
