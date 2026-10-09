package mlabeler.core

import mlabeler.core.format.NiaoNiao
import mlabeler.core.model.IntervalTier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

class NiaoNiaoTest {
    @Test
    fun infBecomesAndComesBackFromATier() {
        // a.inf of a real source folder
        val inf = NiaoNiao.read("13870 23834 14866 22838 339.2 1343 3148")
        assertEquals("13870 23834 14866 22838 339.2 1343 3148", inf.write())
        val doc = NiaoNiao.toDoc(inf, 44100, 43680 / 44100.0)
        assertEquals(listOf("", "consonant", "vowel", "decay", ""), (doc.tiers[0] as IntervalTier).texts)
        assertEquals(inf, NiaoNiao.fromDoc(doc, 44100, inf))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun packWritesTheLayoutOfTheTool() {
        val b = NiaoNiao.Sound("ba", NiaoNiao.Inf(10, 14, 11, 13, 347.2, 1134, 2622), shortArrayOf(1, 2, 3, 4))
        val a = NiaoNiao.Sound("a", NiaoNiao.Inf(0, 2, 1, 1, 339.2, 1, 2), shortArrayOf(-1, 256))
        val (voice, infD) = NiaoNiao.pack(listOf(b, a))
        assertEquals(12, voice.size)
        assertEquals(listOf(-1, -1, 0, 1), voice.take(4).map { it.toInt() })
        val lines = infD.trim().lines().map { Base64.decode(it).decodeToString() }
        assertEquals(listOf("v1", "2 0 0 0 0 0 0 0 0 0", "a 0 4 1 1 339.2 1 2\n", "ba 4 8 1 3 347.2 1134 2622\n"), lines)
        val back = NiaoNiao.unpack(infD, voice)
        assertEquals(listOf("a", "ba"), back.map { it.name })
        assertEquals(listOf<Short>(1, 2, 3, 4), back[1].samples.toList())
        assertEquals(NiaoNiao.Inf(0, 4, 1, 3, 347.2, 1134, 2622), back[1].inf)
    }

    @Test
    fun levelsAreMeanAbsoluteValuesAndPitchAWholePeriod() {
        // 4 s of silence-free sound: a 340 Hz sine after a quieter part
        val sr = 44100
        val x = FloatArray(sr) { i -> (if (i < 4410) 0.1 else 0.4).toFloat() * kotlin.math.sin(2 * kotlin.math.PI * 340 * i / sr).toFloat() }
        val m = NiaoNiao.measure(NiaoNiao.Inf(0, sr, 4410, sr - 4410), x, sr)
        // mean |sin| = 2/pi of the amplitude
        assertEquals((0.1 * 32768 * 2 / kotlin.math.PI).toInt().toDouble(), m.consonantLevel.toDouble(), 30.0)
        assertEquals(339.2, m.pitch)
    }

    @Test
    fun otoEntryBecomesMarksAndBack() {
        val samples = FloatArray(44100) { i -> if (i in 4410 until 39690) 0.5f * kotlin.math.sin(i * 0.05f) else 0f }
        val e = mlabeler.core.format.OtoEntry("ba.wav", "ba", 100.0, 60.0, -700.0, 60.0, 20.0)
        val inf = NiaoNiao.fromOto(e, samples, 44100)
        assertEquals(4410, inf.start)
        assertEquals(7056, inf.consonant)
        assertEquals(35280, inf.end)
        kotlin.test.assertTrue(inf.decay in inf.consonant..inf.end)
        val back = NiaoNiao.toOto("ba.wav", "ba", inf, 44100)
        assertEquals(100.0, back.offset)
        assertEquals(60.0, back.preutterance)
        assertEquals(-700.0, back.cutoff)
    }

    @Test
    fun checkFindsMissingSyllablesAndOddPitches() {
        val r = NiaoNiao.check(mapOf("a" to 440.0, "ba" to 446.0, "o" to 600.0, "xx" to 0.0), listOf("a", "ba", "o", "e"), null, null, 2.0)
        assertEquals(listOf("e"), r.missing)
        assertEquals(listOf("xx"), r.extra)
        assertEquals(listOf("o"), r.outOfRange.map { it.first })
        assertEquals(listOf("xx"), r.noPitch)
    }
}
