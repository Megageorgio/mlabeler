package mlabeler.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

actual class AudioIn actual constructor() {
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        val ctx = AndroidContext.context
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return onResult(true)
        val ask = AndroidContext.askMic ?: return onResult(false)
        ask(onResult)
    }

    @SuppressLint("MissingPermission")
    actual fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit) {
        stop()
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        r.startRecording()
        rec = r
        thread = Thread {
            val buf = ShortArray(1024)
            // a recorder released while reading answers with an error: recording has simply ended
            try {
                while (rec === r) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) onChunk(FloatArray(n) { buf[it] / 32768f })
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    actual fun stop() {
        val r = rec ?: return
        rec = null
        thread?.join(300)
        thread = null
        runCatching { r.stop(); r.release() }
    }

    actual val isRecording: Boolean get() = rec != null
}
