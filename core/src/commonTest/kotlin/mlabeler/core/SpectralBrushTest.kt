package mlabeler.core

import mlabeler.core.dsp.Repair
import mlabeler.core.dsp.SpectralMask
import mlabeler.core.dsp.Spectrogram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralBrushTest {
    private val sr = 16000

    /** Level of [hz] in x[from, to) (a single-bin DFT), dB. */
    private fun level(x: FloatArray, hz: Double, from: Int, to: Int): Double {
        var re = 0.0; var im = 0.0
        for (i in from until to) { re += x[i] * cos(2 * PI * hz * i / sr); im += x[i] * sin(2 * PI * hz * i / sr) }
        return 20 * log10(sqrt(re * re + im * im) / (to - from) + 1e-12)
    }

    @Test
    fun paintedToneGoesTheRestStays() {
        val n = 3 * sr
        val x = FloatArray(n) { i -> (0.3 * sin(2 * PI * 440 * i / sr) + 0.3 * sin(2 * PI * 3000 * i / sr)).toFloat() }
        val mask = SpectralMask()
        // a stroke over 3 kHz from 1.0 s to 2.0 s, a dab every 20 ms
        var t = 1.0
        while (t <= 2.0) {
            mask.add(SpectralMask.Dab(t, Spectrogram.hzToMel(3000.0), 0.05, 150.0, 0.5f, erase = false))
            t += 0.02
        }
        val y = Repair.attenuate(x, sr, 0.0, mask, 60f)
        val mid = (1.3 * sr).toInt() to (1.7 * sr).toInt()
        assertTrue(level(x, 3000.0, mid.first, mid.second) - level(y, 3000.0, mid.first, mid.second) > 40, "3 kHz removed in the middle")
        assertTrue(abs(level(x, 440.0, mid.first, mid.second) - level(y, 440.0, mid.first, mid.second)) < 0.5, "440 Hz kept")
        // before the stroke nothing changes
        for (i in 0 until (0.8 * sr).toInt()) assertTrue(abs(x[i] - y[i]) < 1e-4, "sample $i")
    }

    @Test
    fun erasingTakesAwayAndSoftEdgeFalls() {
        val mask = SpectralMask()
        mask.add(SpectralMask.Dab(1.0, 1000.0, 0.1, 100.0, 0.5f, erase = false))
        val mels = doubleArrayOf(800.0, 920.0, 950.0, 1000.0)
        val g = mask.raster(1.0, 0.01, 1, mels)
        assertEquals(1f, g[3]); assertEquals(1f, g[2])   // the hard core
        assertTrue(g[1] > 0f && g[1] < 1f)                // the soft edge
        assertEquals(0f, g[0])                            // outside
        mask.add(SpectralMask.Dab(1.0, 1000.0, 0.1, 30.0, 0f, erase = true))
        assertEquals(0f, mask.raster(1.0, 0.01, 1, mels)[3])
        assertEquals(1.0 to 1.0, mask.span()!!.let { (a, b) -> (a + 0.1) to (b - 0.1) })
    }
}
