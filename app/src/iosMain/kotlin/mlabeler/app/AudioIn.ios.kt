@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive

actual class AudioIn actual constructor() {
    private var engine: AVAudioEngine? = null

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { ok -> onResult(ok) }
    }

    actual fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit) {
        stop()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, null)
        session.setActive(true, null)
        val e = AVAudioEngine()
        val input = e.inputNode
        val format = input.outputFormatForBus(0u)
        input.installTapOnBus(0u, 1024u, format) { buffer, _ ->
            val b = buffer ?: return@installTapOnBus
            val n = b.frameLength.toInt()
            val ch = b.floatChannelData?.get(0) ?: return@installTapOnBus
            onChunk(FloatArray(n) { ch[it] })
        }
        e.prepare()
        e.startAndReturnError(null)
        engine = e
    }

    actual fun stop() {
        val e = engine ?: return
        engine = null
        e.inputNode.removeTapOnBus(0u)
        e.stop()
    }

    actual val isRecording: Boolean get() = engine != null
}
