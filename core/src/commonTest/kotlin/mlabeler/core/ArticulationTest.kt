package mlabeler.core

import mlabeler.core.format.ArticulationFile
import mlabeler.core.format.SegUnits
import mlabeler.core.format.TransFile
import mlabeler.core.format.UnitPitchFile
import mlabeler.core.model.IntervalTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticulationTest {
    private val as0 = "nphone art segmentation\r\n{\r\n\tphns: [\"b'\", \"b'\"];\r\n\tcut offset: 2816;\r\n\tcut length: 35072;\r\n" +
        "\tboundaries: [0.348299320, 0.383129252, 0.406349206];\r\n\trevised: true;\r\n\tvoiced: [true, false];\r\n};\r\n"

    @Test
    fun readsAndWritesTheSameBytes() {
        val b = ArticulationFile.read(as0)
        assertEquals(1, b.size)
        assertEquals(listOf("b'", "b'"), b[0].phonemes)
        assertEquals(2816L, b[0].cutOffset)
        assertEquals(listOf(true, false), b[0].voiced)
        assertEquals(2816.0 / 44100 + 0.383129252, b[0].times(44100)[1], 1e-9)
        assertEquals(as0, ArticulationFile.write(b))
        assertEquals(440.0 * kotlin.math.exp(kotlin.math.ln(2.0) * -564.490845 / 1200), UnitPitchFile.hz("pitch -564.490845 0.600000 90.000000")!!, 1e-6)
        assertNull(UnitPitchFile.hz("pitch 0.000000 0.600000 90.000000"))
    }

    private val ph = IntervalTier("phones", listOf(0.0, 0.4, 0.5, 0.8, 1.2), listOf("Sil", "b", "a", "Sil"))

    @Test
    fun transitionsAreShortAndApart() {
        val lane = SegUnits.lane(ph, listOf(listOf("b", "a") to null))
        // a little around the change of phoneme, an unnamed gap on both sides
        val u = SegUnits.parse(lane, ph).single()
        assertEquals(listOf("b", "a"), u.phonemes)
        assertEquals(1, u.index)
        assertEquals(0.5, u.bounds[1], 1e-9)
        assertTrue(u.bounds[0] > 0.4 && u.bounds[2] < 0.8)
        assertTrue(lane.texts.first().isEmpty() && lane.texts.last().isEmpty())
        // only the transition from the silence wanted
        var l = SegUnits.toggle(lane, ph, 1, 2)
        assertEquals(emptyList(), SegUnits.units(l, ph))
        l = SegUnits.toggle(l, ph, 0, 2)
        assertEquals(listOf(listOf("Sil", "b")), SegUnits.units(l, ph))
        assertEquals("Sil b a Sil\r\n[Sil b]", TransFile.write(listOf("Sil", "b", "a", "Sil"), "Sil b a Sil\r\n[b a]", units = SegUnits.units(l, ph)))
        assertEquals(listOf(listOf("b", "a")), TransFile.units("Sil b a Sil\r\n[b a]\r\n"))
        // two that touch are still two
        val both = SegUnits.lane(ph, listOf(listOf("Sil", "b") to listOf(0.3, 0.4, 0.45), listOf("b", "a") to listOf(0.45, 0.5, 0.55)))
        assertEquals(listOf(listOf("Sil", "b"), listOf("b", "a")), SegUnits.units(both, ph))
    }

    @Test
    fun articulationFromTheLane() {
        val u = SegUnits.parse(SegUnits.lane(ph, listOf(listOf("b", "a") to null)), ph).single()
        val b = SegUnits.block(u, ph, null, 44100, { 44100 * 2 }, { _, _ -> true })
        assertEquals(0L, b.cutOffset % 256)
        assertTrue(b.cutOffset / 44100.0 <= 0.4 - 0.29)
        val t = b.times(44100)
        assertEquals(0.5, t[1], 256.0 / 44100)
        assertTrue(t.zip(u.bounds).all { (x, y) -> kotlin.math.abs(x - y) <= 128.0 / 44100 })
        assertEquals(listOf(true, true), b.voiced)
        // unchanged: the old one as it was
        val again = SegUnits.parse(SegUnits.lane(ph, listOf(listOf("b", "a") to b.times(44100))), ph).single()
        assertTrue(SegUnits.block(again, ph, b, 44100, { error("not needed") }, { _, _ -> error("not needed") }) === b)
    }
}
