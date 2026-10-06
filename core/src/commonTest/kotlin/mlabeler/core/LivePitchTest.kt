package mlabeler.core

import mlabeler.core.dsp.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class LivePitchTest {
    /** The recorder reads the pitch from the last 2048 input samples, one frame at a time. */
    @Test
    fun shortBufferGivesOnePitch() {
        for (sr in listOf(44100, 48000)) for (hz in listOf(110.0, 220.0, 523.25)) {
            val buf = FloatArray(2048) { (0.4 * sin(2 * PI * hz * it / sr) + 0.15 * sin(4 * PI * hz * it / sr)).toFloat() }
            val got = Pitch.yin(buf, sr, hop = 1.0, fmin = 65.0, fmax = 1100.0).values.lastOrNull { it > 0f }
            assertTrue(got != null && abs(Pitch.hzToMidi(got.toDouble()) - Pitch.hzToMidi(hz)) < 0.3, "$sr $hz -> $got")
        }
    }
}
