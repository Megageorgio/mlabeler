@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import mlabeler.core.audio.Audio
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeBufferLoops
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.NSApplicationSupportDirectory

actual object Platform {
    actual val name: String = "iOS"
    actual val isMac: Boolean = false
    actual val isMobile: Boolean = true
    actual val systemLanguage: String get() = NSLocale.currentLocale.languageCode

    private fun dir(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String) ?: "/tmp"

    actual fun dataDir(): String {
        val d = dir(NSApplicationSupportDirectory) + "/mLabeler"
        NSFileManager.defaultManager.createDirectoryAtPath(d, true, null, null)
        return d
    }

    /** The app's Documents folder; it is visible in the Files app. */
    actual fun homeDir(): String = dir(NSDocumentDirectory)
    actual fun places(): List<Pair<String, String>> = listOf("Documents" to homeDir())
    actual val hasNativeFolderPicker: Boolean = false
    actual fun pickFolderNative(title: String, start: String?): String? = null
    actual fun decodeAudio(path: String): Audio? = null
    actual fun openInFileManager(path: String) = Unit
    actual fun applyScreen(orientation: String, fullscreen: Boolean) = Unit
}

actual val resizeHorizontalIcon: PointerIcon = PointerIcon.Hand

actual class AudioOut actual constructor() {
    private var engine: AVAudioEngine? = null
    private var node: AVAudioPlayerNode? = null
    private var start = 0
    private var length = 0
    private var loopMode = false
    private var sampleRate = 44100.0

    actual fun play(audio: Audio, from: Int, to: Int, loop: Boolean) {
        stop()
        runCatching {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
            AVAudioSession.sharedInstance().setActive(true, null)
        }
        val n = to - from
        val format = AVAudioFormat(standardFormatWithSampleRate = audio.sampleRate.toDouble(), channels = 1u)
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = n.toUInt()) ?: return
        buffer.frameLength = n.toUInt()
        val ch = buffer.floatChannelData!![0]!!
        for (i in 0 until n) ch[i] = audio.samples[from + i]
        val e = AVAudioEngine()
        val p = AVAudioPlayerNode()
        e.attachNode(p)
        e.connect(p, e.mainMixerNode, format)
        e.prepare()
        e.startAndReturnError(null)
        p.scheduleBuffer(buffer, null, if (loop) AVAudioPlayerNodeBufferLoops else 0u) {
            if (!loop) node = null
        }
        p.play()
        engine = e
        node = p
        start = from
        length = n
        loopMode = loop
        sampleRate = audio.sampleRate.toDouble()
    }

    actual fun stop() {
        node?.stop()
        engine?.stop()
        node = null
        engine = null
    }

    actual val isPlaying: Boolean get() = node != null

    actual fun position(): Int {
        val p = node ?: return -1
        val nodeTime = p.lastRenderTime ?: return -1
        val t = p.playerTimeForNodeTime(nodeTime) ?: return -1
        val played = t.sampleTime
        if (played < 0) return start
        val pos = if (loopMode) (played % length).toInt() else minOf(played, length.toLong()).toInt()
        if (!loopMode && played >= length) node = null
        return start + pos
    }

    actual fun release() = stop()
}

@Composable
actual fun StorageAccess(content: @Composable () -> Unit) = content()
