package mlabeler.core

import mlabeler.core.format.OtoCompare
import mlabeler.core.format.OtoEntry
import mlabeler.core.format.OtoMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtoCompareTest {
    private fun e(alias: String, offset: Double, preu: Double) = OtoEntry("a.wav", alias, offset, 100.0, -300.0, preu, 20.0)

    @Test
    fun differencesListTheMostDifferentFirst() {
        val mine = listOf(e("a", 100.0, 50.0), e("b", 200.0, 60.0), e("c", 300.0, 70.0), e("mine", 0.0, 10.0))
        val theirs = listOf(e("a", 100.0, 50.0), e("b", 205.0, 60.0), e("c", 260.0, 70.0), e("theirs", 0.0, 10.0))
        val d = OtoCompare.differences(mine, theirs, minMs = 10.0)
        // b is 5 ms off: not a difference at 10 ms
        assertEquals(listOf("c", "mine", "theirs"), d.map { it.alias })
        assertEquals(40.0, d[0].deltas[OtoMarker.Left])
        assertNull(d[1].theirs)
        assertNull(d[2].mine)
        assertEquals(listOf("b", "c", "mine", "theirs"), OtoCompare.differences(mine, theirs, minMs = 1.0).map { it.alias })
    }
}
