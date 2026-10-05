package mlabeler.app

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

actual class AudioIn actual constructor() {
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var thread: Thread? = null

    actual fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)

    actual fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit) {
        stop()
        val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val l = AudioSystem.getTargetDataLine(fmt)
        l.open(fmt, sampleRate / 10 * 2)
        l.start()
        line = l
        thread = Thread {
            val buf = ByteArray(2048)
            try {
                while (line === l) {
                    val n = l.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val out = FloatArray(n / 2) { i -> ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
                    onChunk(out)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "mlabeler-rec"; start() }
    }

    actual fun stop() {
        val l = line ?: return
        line = null
        runCatching { l.stop(); l.close() }
        thread?.join(300)
        thread = null
    }

    actual val isRecording: Boolean get() = line != null
}
