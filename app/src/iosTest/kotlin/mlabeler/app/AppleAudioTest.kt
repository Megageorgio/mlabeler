package mlabeler.app

import mlabeler.core.audio.Audio
import kotlin.test.*

class AppleAudioTest {
    @Test fun emptyLoopDoesNotStartHardwareOrDivideByZero() {
        val output = AudioOut()
        output.play(Audio(44100, floatArrayOf()), 0, 0, true)
        assertFalse(output.isPlaying)
        assertEquals(-1, output.position())
        output.release()
    }

    @Test fun invalidRangesAreRejectedBeforeStartingHardware() {
        val output = AudioOut()
        assertFailsWith<IllegalArgumentException> { output.play(Audio(44100, floatArrayOf(0f)), -1, 1, false) }
        assertFalse(output.isPlaying)
        output.release()
    }
}
