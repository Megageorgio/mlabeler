package mlabeler.core

import mlabeler.core.dsp.Pitch
import mlabeler.core.dsp.Stretch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class WsolaTest {
    private fun tone(sr: Int, hz: Double, sec: Double) = FloatArray((sr * sec).toInt()) { (0.5 * sin(2 * PI * hz * it / sr)).toFloat() }

    private fun medianHz(x: FloatArray, sr: Int): Double {
        val v = Pitch.yin(x, sr, hop = 0.01).values.filter { it > 0f }.sorted()
        return v[v.size / 2].toDouble()
    }

    @Test
    fun slowerKeepsPitch() {
        val sr = 22050
        val x = tone(sr, 220.0, 2.0)
        val y = Stretch.process(x, sr, speed = 0.75)
        assertTrue(abs(y.size - x.size / 0.75) < sr * 0.05, "length ${y.size}")
        assertTrue(abs(medianHz(y, sr) - 220.0) < 4, "pitch ${medianHz(y, sr)}")
    }

    @Test
    fun transposeKeepsLength() {
        val sr = 22050
        val x = tone(sr, 220.0, 2.0)
        val y = Stretch.process(x, sr, semitones = 3.0)
        assertTrue(abs(y.size - x.size) < sr * 0.05, "length ${y.size}")
        val want = 220.0 * kotlin.math.exp(3 / 12.0 * kotlin.math.ln(2.0))
        assertTrue(abs(medianHz(y, sr) - want) < 6, "pitch ${medianHz(y, sr)} want $want")
    }
}
