package mlabeler.app

import mlabeler.core.audio.Audio

/** Non-blocking PCM16 mono output. The device buffer is measured in frames, not bytes. */
internal interface PlaybackTrack {
    val bufferFrames: Int
    fun play()
    fun write(data: ShortArray, offset: Int, count: Int): Int
    fun head(): Int
    fun pause()
    fun flush()
    fun stop()
    fun release()
}

/** Each playback owns its cancellation flag and track, including while a previous track is closing. */
internal class AndroidAudioPlayer(
    private val createTrack: (Int) -> PlaybackTrack,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val control = Any()
    @Volatile private var current: Session? = null

    private class Session(val track: PlaybackTrack, val from: Int, val length: Int, val loop: Boolean) {
        val lock = Any()
        @Volatile var cancelled = false
        lateinit var thread: Thread
        private var lastHead = 0L
        private var wraps = 0L
        // AudioTrack exposes an unsigned 32-bit frame counter which can wrap during a long loop.
        fun played(): Long {
            val head = track.head().toLong() and 0xffffffffL
            if (head < lastHead) wraps += 1L shl 32
            lastHead = head
            return wraps + head
        }
    }

    fun play(audio: Audio, from: Int, to: Int, loop: Boolean) = synchronized(control) {
        require(audio.sampleRate > 0) { "Invalid sample rate" }
        require(from >= 0 && to >= from && to <= audio.samples.size) { "Invalid playback range" }
        stopLocked()
        if (from == to) return@synchronized
        val track = createTrack(audio.sampleRate)
        val session = Session(track, from, to - from, loop)
        session.thread = Thread({ feed(session, audio) }, "mlabeler-audio").apply { isDaemon = true }
        try {
            check(track.bufferFrames > 0) { "Invalid audio buffer size" }
            track.play()
            current = session
            session.thread.start()
        } catch (e: Exception) {
            if (current === session) current = null
            runCatching { track.stop() }
            runCatching { track.release() }
            throw e
        }
    }

    private fun feed(s: Session, audio: Audio) {
        val buffer = ShortArray(2048)
        var source = s.from
        var lastProgress = System.nanoTime()
        fun checkProgress() {
            check(System.nanoTime() - lastProgress < 10_000_000_000L) { "Audio output stopped responding" }
        }
        fun write(count: Int) {
            var offset = 0
            while (offset < count && !s.cancelled) {
                val written = synchronized(s.lock) {
                    if (s.cancelled) return
                    s.track.write(buffer, offset, count - offset)
                }
                check(written in 0..(count - offset)) { "AudioTrack.write failed: $written" }
                if (written > 0) { offset += written; lastProgress = System.nanoTime() }
                else { checkProgress(); Thread.sleep(2) }
            }
        }
        try {
            do {
                val count = if (s.loop) buffer.size else minOf(buffer.size, s.from + s.length - source)
                for (i in 0 until count) {
                    buffer[i] = (audio.samples[source].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    source++
                    if (s.loop && source == s.from + s.length) source = s.from
                }
                write(count)
            } while (!s.cancelled && (s.loop || source < s.from + s.length))

            if (!s.cancelled) {
                // A streaming AudioTrack may not start/resume until its whole device buffer is filled.
                // Pad AFTER the selection, never before it: short sounds start and the cursor has no offset.
                // Also re-prime a final partial buffer after an underrun. Only one small scratch buffer is used.
                buffer.fill(0)
                var tail = s.track.bufferFrames
                while (tail > 0 && !s.cancelled) {
                    val count = minOf(tail, buffer.size)
                    write(count)
                    tail -= count
                }
                var previous = -1L
                while (!s.cancelled) {
                    val played = synchronized(s.lock) { if (s.cancelled) return; s.played() }
                    if (played >= s.length) break
                    if (played != previous) { previous = played; lastProgress = System.nanoTime() }
                    checkProgress()
                    Thread.sleep(5)
                }
            }
        } catch (_: InterruptedException) {
            // stop() wakes a writer waiting for space without blocking the UI on join().
        } catch (e: Exception) {
            if (!s.cancelled) onFailure(e)
        } finally {
            synchronized(control) { if (current === s) current = null }
            synchronized(s.lock) {
                runCatching { s.track.stop() }
                // release must still happen if stop throws (e.g. the audio device disappeared).
                runCatching { s.track.release() }
            }
        }
    }

    fun stop() = synchronized(control) { stopLocked() }
    private fun stopLocked() {
        val s = current ?: return
        current = null
        s.cancelled = true
        synchronized(s.lock) {
            runCatching { s.track.pause() }
            runCatching { s.track.flush() }
        }
        s.thread.interrupt()
    }

    val isPlaying: Boolean get() = current != null
    fun position(): Int {
        val s = current ?: return -1
        return synchronized(s.lock) {
            if (s.cancelled || current !== s) return -1
            val played = runCatching { s.played() }.getOrNull() ?: return -1
            s.from + (if (s.loop) played % s.length else minOf(played, s.length.toLong())).toInt()
        }
    }
}
