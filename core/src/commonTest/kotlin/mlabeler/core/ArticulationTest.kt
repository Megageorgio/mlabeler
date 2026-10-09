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
    fun unitsAreChosen() {
        val t = SegUnits.tier(ph, listOf(listOf("b", "a")))
        assertEquals(listOf(listOf("b", "a")), SegUnits.units(t))
        // only the transition from the silence wanted: the old one away, the new one in
        var u = SegUnits.toggle(t, ph, 1, 2)
        assertEquals(emptyList(), SegUnits.units(u))
        u = SegUnits.toggle(u, ph, 0, 2)
        assertEquals(listOf(listOf("Sil", "b")), SegUnits.units(u))
        assertEquals("Sil b a Sil\r\n[Sil b]", TransFile.write(listOf("Sil", "b", "a", "Sil"), "Sil b a Sil\r\n[b a]", units = SegUnits.units(u)))
        assertEquals(listOf(listOf("b", "a")), TransFile.units("Sil b a Sil\r\n[b a]\r\n"))
    }

    @Test
    fun articulationsMadeFromThePhonemes() {
        val units = SegUnits.tier(ph, listOf(listOf("b", "a")))
        val made = SegUnits.blocksFor(emptyList(), units, ph, null, 44100, 44100 * 2, { _, _ -> true })
        assertEquals(1, made.size)
        val b = made[0]
        assertEquals(0L, b.cutOffset % 256)
        assertEquals(0L, b.cutLength % 256)
        assertTrue(b.cutOffset / 44100.0 <= 0.4 - 0.29)
        val t = b.times(44100)
        assertEquals(0.5, t[1], 256.0 / 44100)
        assertTrue(t[0] < t[1] && t[1] < t[2] && t[0] >= 0.4 && t[2] <= 0.8)
        assertEquals(true, b.revised)
        // kept while the phonemes stay; made again when they move
        val kept = SegUnits.blocksFor(listOf(b.copy(boundaries = b.boundaries.map { it + 0.001 })), units, ph, ph, 44100, 88200, { _, _ -> true })
        assertEquals(b.boundaries[1] + 0.001, kept[0].boundaries[1], 1e-9)
        val moved = IntervalTier("phones", listOf(0.0, 0.4, 0.55, 0.8, 1.2), ph.texts)
        val again = SegUnits.blocksFor(kept, SegUnits.tier(moved, listOf(listOf("b", "a"))), moved, ph, 44100, 88200, { _, _ -> false })
        assertEquals(0.55, again[0].times(44100)[1], 256.0 / 44100)
        assertEquals(listOf(false, false), again[0].voiced)
    }
}
