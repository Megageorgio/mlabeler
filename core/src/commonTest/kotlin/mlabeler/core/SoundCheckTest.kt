package mlabeler.core

import mlabeler.core.audio.Audio
import mlabeler.core.audio.SoundCheck
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundCheckTest {
    private val rate = 16000
    private fun tone(seconds: Double, amp: Float) = FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toFloat() }

    @Test
    fun cleanRecordingHasNothing() {
        val x = tone(0.3, 0f) + tone(2.0, 0.5f) + tone(0.3, 0f)
        assertEquals(emptyList(), SoundCheck.analyze(Audio(rate, x)).findings)
    }

    @Test
    fun findsClippingQuietAndSilence() {
        val clipped = tone(1.0, 2f).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        val kinds = SoundCheck.analyze(Audio(rate, clipped)).findings.map { it.kind }
        assertTrue(SoundCheck.Kind.Clipping in kinds)
        val quiet = SoundCheck.analyze(Audio(rate, tone(1.0, 0.05f))).findings.map { it.kind }
        assertTrue(SoundCheck.Kind.Quiet in quiet)
        val long = SoundCheck.analyze(Audio(rate, tone(3.0, 0f) + tone(1.0, 0.5f) + tone(2.5, 0f))).findings
        assertTrue(long.any { it.kind == SoundCheck.Kind.SilenceAtStart && it.value in 2.9..3.1 })
        assertTrue(long.any { it.kind == SoundCheck.Kind.SilenceAtEnd && it.value in 2.4..2.6 })
    }

    @Test
    fun findsNoiseAndOffset() {
        val r = kotlin.random.Random(1)
        val noisy = (tone(1.0, 0.5f) + FloatArray(rate) { (r.nextFloat() - 0.5f) * 0.05f })
        // a take without pauses: no noise level to judge
        kotlin.test.assertTrue(SoundCheck.analyze(Audio(rate, tone(2.0, 0.5f))).findings.none { it.kind == SoundCheck.Kind.Noisy })
        assertTrue(SoundCheck.analyze(Audio(rate, noisy)).findings.any { it.kind == SoundCheck.Kind.Noisy })
        val dc = tone(1.0, 0.3f).map { it + 0.05f }.toFloatArray()
        assertTrue(SoundCheck.analyze(Audio(rate, dc)).findings.any { it.kind == SoundCheck.Kind.DcOffset })
    }
}
