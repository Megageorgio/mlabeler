package mlabeler.core

import mlabeler.core.dsp.AutoTune
import mlabeler.core.dsp.Pitch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoTuneTest {
    private fun voice(sr: Int, seconds: Double, f: (Double) -> Double): FloatArray {
        var ph = 0.0
        return FloatArray((sr * seconds).toInt()) { i ->
            val t = i.toDouble() / sr
            ph += 2 * PI * f(t) / sr
            // a voice-like buzz: many harmonics, falling off (sharp pulses once per period)
            var v = 0.0
            for (h in 1..15) v += sin(h * ph) / h
            (0.3 * v).toFloat()
        }
    }

    private fun median(x: FloatArray) = x.filter { it > 0f }.sorted().let { it[it.size / 2] }

    @Test
    fun pullsToTheNearestNote() {
        val sr = 22050
        // 447 Hz with a little vibrato: above A4 (440), well below A#4 (466)
        val x = voice(sr, 1.5) { t -> 447.0 + 4 * sin(2 * PI * 5 * t) }
        val y = AutoTune.process(x, sr)
        assertEquals(x.size, y.size)
        val before = median(Pitch.yin(x, sr).values)
        val after = Pitch.yin(y, sr).values.filter { it > 0f }
        assertTrue(before > 443f, "before $before")
        // most frames now sit on A4
        val near = after.count { kotlin.math.abs(it - 440f) < 4f }
        assertTrue(near > after.size * 0.8, "near A4: $near of ${after.size}, median ${median(after.toFloatArray())}")
    }

    @Test
    fun keepsTheScale() {
        val sr = 22050
        // C#5 (554 Hz) in C major goes to C5 or D5
        val y = AutoTune.process(voice(sr, 1.0) { 554.0 }, sr, scale = setOf(0, 2, 4, 5, 7, 9, 11))
        val m = median(Pitch.yin(y, sr).values)
        assertTrue(kotlin.math.abs(m - 523f) < 6f || kotlin.math.abs(m - 587f) < 6f, "got $m")
    }

    @Test
    fun followsTheGuide() {
        val sr = 22050
        // sung at 447 Hz (MIDI 69.2), the guide says D: the nearest D is D5 (74, 587 Hz)
        val y = AutoTune.process(voice(sr, 1.0) { 447.0 }, sr, guide = { 62.0 })
        val m = median(Pitch.yin(y, sr).values)
        assertTrue(kotlin.math.abs(m - 587f) < 8f, "got $m")
    }
}
