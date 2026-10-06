package mlabeler.core

import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.audio.WavEdit
import mlabeler.core.dsp.Repair
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepairTest {
    private val sr = 44100
    private fun tone(n: Int) = FloatArray(n) { (0.3 * sin(2 * PI * 220 * it / sr) + 0.1 * sin(2 * PI * 660 * it / sr)).toFloat() }

    @Test
    fun findsAndRepairsAClick() {
        val clean = tone(sr)
        val x = clean.copyOf()
        for (i in 20000 until 20012) x[i] += if (i % 2 == 0) 0.6f else -0.5f
        val clicks = Repair.findClicks(x, sr, sensitivity = 5f, maxWidthMs = 5f)
        assertEquals(1, clicks.size, "clicks: $clicks")
        val c = clicks[0]
        assertTrue(c.first <= 20000 && c.last >= 20011)
        val fixed = Repair.interpolate(x, c.first, c.last + 1)
        val err = fixed.indices.maxOf { abs(fixed[it] - clean[c.first + it]) }
        assertTrue(err < 0.02f, "error $err")
    }

    @Test
    fun voicedPulsesAreNotClicks() {
        // a buzzy, voice-like signal: sharp pulse every 5 ms
        val x = FloatArray(sr) { i -> val ph = (i % 220) / 220f; (if (ph < 0.05f) 0.6f * (1 - ph / 0.05f) else 0f) - 0.03f + 0.2f * sin(2 * PI * 200 * i / sr).toFloat() }
        assertEquals(0, Repair.findClicks(x, sr, 5f, 5f).size)
    }

    @Test
    fun noClicksInCleanTone() {
        assertEquals(0, Repair.findClicks(tone(sr), sr, 5f, 5f).size)
    }

    @Test
    fun noiseGateWithoutReductionKeepsTheSignal() {
        val rnd = Random(1)
        val x = FloatArray(sr) { tone(sr)[it] + (rnd.nextFloat() - 0.5f) * 0.01f }
        val prof = Repair.noiseProfile(FloatArray(8192) { (rnd.nextFloat() - 0.5f) * 0.01f })
        val y = Repair.reduceNoise(x, prof, reductionDb = 0f, sensitivityDb = 6f, smoothingBands = 3)
        assertTrue(x.indices.maxOf { abs(x[it] - y[it]) } < 1e-5f)
    }

    @Test
    fun wavEditTouchesOnlyChangedSamples() {
        val bytes = Wav.encode16(Audio(sr, tone(1000)))
        val w = WavEdit(bytes)
        // writing the same values back changes nothing
        for (i in 0 until w.frames) w.set(i, 0, w.get(i, 0))
        val d0 = bytes.indices.filter { bytes[it] != w.bytes[it] }
        assertTrue(d0.isEmpty(), "differs at $d0 of ${bytes.size}: ${d0.map { bytes[it].toString() + "/" + w.bytes[it] }}")
        w.set(10, 0, 0f)
        val diff = bytes.indices.count { bytes[it] != w.bytes[it] }
        assertTrue(diff in 1..2)
    }
}
