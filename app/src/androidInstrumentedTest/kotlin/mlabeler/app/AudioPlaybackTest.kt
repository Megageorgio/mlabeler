package mlabeler.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import mlabeler.core.audio.Audio
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AudioPlaybackTest {
    private fun tone(rate: Int, frames: Int) = Audio(rate, FloatArray(frames) { (sin(it * 2 * Math.PI * 440 / rate) * 0.02).toFloat() })
    private fun finished(output: AudioOut, timeout: Long) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (output.isPlaying && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
        assertFalse(output.isPlaying, "Playback is stuck (the stream may not have reached its start threshold)")
    }

    @Test fun subBufferSoundsCompleteAtCommonSampleRates() {
        val errors = CopyOnWriteArrayList<Exception>()
        val played = AtomicLong()
        val output = AndroidAudioPlayer({ rate ->
            val real = AndroidPlaybackTrack(rate)
            object : PlaybackTrack by real {
                override fun head(): Int = real.head().also { played.set(it.toLong() and 0xffffffffL) }
            }
        }, errors::add)
        try {
            for (rate in listOf(16000, 44100, 48000)) for (frames in listOf(1, rate/200, rate/20)) {
                played.set(0)
                val audio = tone(rate, frames)
                output.play(audio, 0, frames, false)
                val deadline = android.os.SystemClock.elapsedRealtime() + 2500
                while (output.isPlaying && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
                assertFalse(output.isPlaying, "Playback is stuck: $rate Hz, $frames frames")
                assertTrue(errors.isEmpty(), errors.toString())
                assertTrue(played.get() >= frames, "The device did not play the whole selection: $rate Hz, $frames frames")
            }
        } finally { output.stop() }
    }

    @Test fun playbackContinuesPastOneSecondAndReachesTheEnd() {
        val output = AudioOut()
        try {
            output.play(tone(48000, 48000*3), 0, 48000*3, false)
            Thread.sleep(1400)
            assertTrue(output.isPlaying, "Three-second recording stopped early")
            assertTrue(output.position() in 4800 until 144000, "Playback cursor did not advance")
            finished(output, 5000)
        } finally { output.release() }
    }

    @Test fun tinyLoopsAndRapidRestartsRemainStoppable() {
        val output = AudioOut()
        try {
            val audio = tone(44100, 44100)
            repeat(20) { i -> output.play(audio, i, i+2, true); Thread.sleep(10) }
            Thread.sleep(200)
            assertTrue(output.isPlaying)
            assertTrue(output.position() in 19..20)
            output.stop()
            assertFalse(output.isPlaying)
            assertEquals(-1, output.position())
            output.play(audio, 0, audio.samples.size, false)
            finished(output, 3500)
        } finally { output.release() }
    }

    @Test fun emptyLoopAndInvalidRangeNeverStartPlayback() {
        val output = AudioOut()
        try {
            val audio = tone(48000, 100)
            output.play(audio, 20, 20, true)
            assertFalse(output.isPlaying)
            assertFailsWith<IllegalArgumentException> { output.play(audio, -1, 100, false) }
            assertFalse(output.isPlaying)
        } finally { output.release() }
    }
}
