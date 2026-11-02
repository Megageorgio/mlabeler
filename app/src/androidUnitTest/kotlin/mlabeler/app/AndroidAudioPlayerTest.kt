package mlabeler.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mlabeler.core.audio.Audio
import kotlin.test.*

class AndroidAudioPlayerTest {
    private class Device(
        override val bufferFrames: Int = 4096,
        val maxWrite: Int = Int.MAX_VALUE,
        val stall: Boolean = false,
        val failure: Boolean = false,
        val brokenStop: Boolean = false,
    ) : PlaybackTrack {
        val samples = ArrayList<Short>()
        val writes = AtomicInteger()
        val released = CountDownLatch(1)
        val releases = AtomicInteger()
        @Volatile var accepted = 0L
        @Volatile var playedOverride: Int? = null
        override fun play() {}
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            val call = writes.incrementAndGet()
            if (failure) return -6
            if (stall || call % 3 == 0) return 0
            val n = minOf(count, maxWrite)
            // Model a non-blocking partial write. Capture only a bounded prefix for loop tests.
            synchronized(samples) { for (i in offset until offset+n) if (samples.size < 100_000) samples.add(data[i]) }
            accepted += n
            return n
        }
        override fun head(): Int = playedOverride ?: if (accepted < bufferFrames) 0 else accepted.toInt()
        override fun pause() {}
        override fun flush() {}
        override fun stop() { if (brokenStop) error("device gone") }
        override fun release() { releases.incrementAndGet(); released.countDown() }
        fun waitForRelease() { assertTrue(released.await(4, TimeUnit.SECONDS), "Track was not released") }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) { assertTrue(System.nanoTime() < deadline, "Timed out"); Thread.sleep(2) }
    }

    @Test fun shortSelectionStartsWithoutLeadingSilenceAndWaitsForTheDevice() {
        val device = Device(maxWrite = 31)
        val errors = CopyOnWriteArrayList<Exception>()
        val player = AndroidAudioPlayer({ device }, errors::add)
        val audio = Audio(48000, FloatArray(20) { (it+1)/100f })
        device.playedOverride = 0
        player.play(audio, 5, 12, false)
        waitUntil { device.accepted >= device.bufferFrames + 7 }
        assertTrue(player.isPlaying, "Writing the selection is not the same as playing it")
        device.playedOverride = 7
        device.waitForRelease()
        assertFalse(player.isPlaying)
        assertEquals(-1, player.position())
        assertTrue(errors.isEmpty())
        assertEquals((5 until 12).map { (audio.samples[it]*32767).toInt().toShort() }, device.samples.take(7))
        assertTrue(device.samples.drop(7).all { it == 0.toShort() })
        assertTrue(device.accepted >= device.bufferFrames)
        assertEquals(1, device.releases.get())
    }

    @Test fun partialWritesDoNotTruncateOrDuplicateLongRecordings() {
        val device = Device(bufferFrames = 64, maxWrite = 127)
        val errors = CopyOnWriteArrayList<Exception>()
        val player = AndroidAudioPlayer({ device }, errors::add)
        val samples = FloatArray(48_000) { ((it % 211)-105)/110f } // Three seconds, not a one-second cap.
        player.play(Audio(16000, samples), 0, samples.size, false)
        device.waitForRelease()
        assertTrue(errors.isEmpty())
        assertEquals(samples.map { (it*32767).toInt().toShort() }, device.samples.take(samples.size))
        assertEquals(samples.size + device.bufferFrames.toLong(), device.accepted)
    }

    @Test fun oneFrameLoopRepeatsContinuouslyAndStopsWithoutJoiningTheWriter() {
        val device = Device(bufferFrames = 64, maxWrite = 13)
        val player = AndroidAudioPlayer({ device })
        player.play(Audio(44100, floatArrayOf(0.25f)), 0, 1, true)
        waitUntil { device.accepted >= 4096 }
        assertTrue(player.isPlaying)
        assertEquals(0, player.position())
        val before = System.nanoTime()
        player.stop()
        assertTrue(System.nanoTime() - before < 100_000_000L)
        device.waitForRelease()
        assertTrue(device.samples.all { it == (0.25f*32767).toInt().toShort() })
        assertFalse(player.isPlaying)
    }

    @Test fun emptyAndInvalidSelectionsDoNotOpenAudioHardware() {
        val created = AtomicInteger()
        val player = AndroidAudioPlayer({ created.incrementAndGet(); Device() })
        val a = Audio(16000, FloatArray(10))
        player.play(a, 4, 4, true)
        assertFalse(player.isPlaying)
        assertFailsWith<IllegalArgumentException> { player.play(a, -1, 3, false) }
        assertFailsWith<IllegalArgumentException> { player.play(a, 0, 11, false) }
        assertFailsWith<IllegalArgumentException> { player.play(a, 6, 2, false) }
        assertEquals(0, created.get())
    }

    @Test fun rapidReplacementCannotReviveOldWritersOrClearTheNewSession() {
        val devices = CopyOnWriteArrayList<Device>()
        val player = AndroidAudioPlayer({ Device(stall = true).also(devices::add) })
        try {
            repeat(40) { i ->
                player.play(Audio(16000, FloatArray(100)), i, 100, true)
                assertEquals(i, player.position())
            }
            for (old in devices.dropLast(1)) { old.waitForRelease(); assertEquals(1, old.releases.get()) }
            assertTrue(player.isPlaying)
            assertEquals(39, player.position())
            player.stop()
            devices.last().waitForRelease()
            assertEquals(-1, player.position())
        } finally { player.stop() }
    }

    @Test fun writeFailureAndStopFailureStillReleaseTheTrack() {
        val device = Device(failure = true, brokenStop = true)
        val errors = CopyOnWriteArrayList<Exception>()
        val player = AndroidAudioPlayer({ device }, errors::add)
        player.play(Audio(48000, FloatArray(100)), 0, 100, false)
        device.waitForRelease()
        assertFalse(player.isPlaying)
        assertEquals(1, errors.size)
        assertEquals(1, device.releases.get())
    }

    @Test fun loopCursorTracksUnsignedPlaybackCounterAcrossWraparound() {
        val device = Device(stall = true)
        val player = AndroidAudioPlayer({ device })
        try {
            player.play(Audio(48000, FloatArray(200)), 7, 107, true)
            device.playedOverride = -16
            assertEquals(7 + (0xfffffff0L % 100).toInt(), player.position())
            device.playedOverride = 32
            assertEquals(7 + (0x100000020L % 100).toInt(), player.position())
        } finally { player.stop(); device.waitForRelease() }
    }
}
