package mlabeler.core

import mlabeler.core.format.SegFile
import mlabeler.core.format.TransFile
import mlabeler.core.model.IntervalTier
import kotlin.test.Test
import kotlin.test.assertEquals

class SegTransTest {
    private val seg = "nPhonemes 4\r\narticulationsAreStationaries 0\r\nphoneme\t\tBeginTime\t\tEndTime\r\n" +
        "=================================================\r\n" +
        "Sil\t\t0.000000\t\t0.250000\r\nt\t\t0.250000\t\t0.370500\r\no\t\t0.370500\t\t0.900000\r\nSil\t\t0.900000\t\t1.200000\r\n"

    @Test
    fun readsAndWritesTheSameBytes() {
        val doc = SegFile.read(seg)
        val t = doc.tiers[0] as IntervalTier
        assertEquals(listOf("Sil", "t", "o", "Sil"), t.texts)
        assertEquals(0.3705, t.startOf(2), 1e-9)
        assertEquals(seg, SegFile.write(doc, seg))
    }

    @Test
    fun transcriptionFollowsThePhonemes() {
        val pairs = "Sil t o Sil\r\n[Sil t]\r\n[t o]\r\n[o Sil]"
        assertEquals(pairs, TransFile.write(listOf("Sil", "t", "o", "Sil"), pairs))
        assertEquals("Sil d o Sil\r\n[Sil d]\r\n[d o]\r\n[o Sil]", TransFile.write(listOf("Sil", "d", "o", "Sil"), pairs))
        val held = "Sil o Sil\r\n[o]\r\n"
        assertEquals("Sil e Sil\r\n[e]\r\n", TransFile.write(listOf("Sil", "e", "Sil"), held))
        assertEquals(listOf("Sil", "t", "o", "Sil"), TransFile.sequence(pairs))
    }

    @Test
    fun unnamedIntervalsBecomeSilence() {
        val doc = mlabeler.core.model.LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.5, 1.0), listOf("", "a"))))
        assertEquals(listOf("Sil", "a"), SegFile.phonemes(doc))
        kotlin.test.assertTrue(SegFile.write(doc).startsWith("nPhonemes 2\r\narticulationsAreStationaries 0\r\n"))
    }
}
