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
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

actual object Platform {
    actual val name: String = "iOS"
    actual val isMac: Boolean = false
    actual val isMobile: Boolean = true
    actual val cores: Int get() = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt()
    actual val systemLanguage: String get() = NSLocale.currentLocale.languageCode

    private fun dir(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String) ?: "/tmp"

    actual val portableDir: String? = null
    actual val legacyWindows: Boolean = false
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
    actual fun pickFileNative(title: String, extensions: List<String>, start: String?): String? = null
    actual fun decodeAudio(path: String): Audio? = decodeAppleAudio(path)
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
    private var generation = 0L
    private var observer: NSObjectProtocol? = null

    actual fun play(audio: Audio, from: Int, to: Int, loop: Boolean) {
        stop()
        require(from in 0..audio.samples.size && to in from..audio.samples.size && audio.sampleRate > 0) { "Invalid audio range" }
        val n = to - from
        if (n == 0) return
        val run = ++generation
        try {
        AppleAudioSession.acquire(this, recording = false) { if (generation == run) stop() }
        val format = AVAudioFormat(standardFormatWithSampleRate = audio.sampleRate.toDouble(), channels = 1u)
        val e = AVAudioEngine()
        engine = e
        val p = AVAudioPlayerNode()
        node = p
        e.attachNode(p)
        e.connect(p, e.mainMixerNode, format)
        e.prepare()
        checkAppleAudio("Start playback") { e.startAndReturnError(it) }
        start = from
        length = n
        loopMode = loop
        sampleRate = audio.sampleRate.toDouble()
        observer = AppleAudioSession.watch(e) { if (engine === e && generation == run) stop() }
        // Bound native memory even when playing a long recording. Keep three chunks queued to avoid gaps.
        var cursor = from
        fun enqueue() {
            if (generation != run || node !== p) return
            if (cursor >= to) { if (loop) cursor = from else return }
            val count = minOf(16384, to - cursor)
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = count.toUInt())
            buffer.frameLength = count.toUInt()
            val ch = buffer.floatChannelData!![0]!!
            for (i in 0 until count) ch[i] = audio.samples[cursor + i]
            cursor += count
            val last = !loop && cursor == to
            p.scheduleBuffer(buffer, null, 0u, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
                NSOperationQueue.mainQueue.addOperationWithBlock {
                    if (generation == run && node === p) {
                        try { if (last) stop() else enqueue() } catch (_: Exception) { stop() }
                    }
                }
            }
        }
        repeat(3) { enqueue() }
        p.play()
        } catch (e: Exception) { stop(); throw e }
    }

    actual fun stop() {
        generation++
        AppleAudioSession.unwatch(observer)
        observer = null
        node?.stop()
        engine?.stop()
        node = null
        engine = null
        AppleAudioSession.release(this)
    }

    actual val isPlaying: Boolean get() = node != null

    actual fun position(): Int {
        val p = node ?: return -1
        val nodeTime = p.lastRenderTime ?: return -1
        val t = p.playerTimeForNodeTime(nodeTime) ?: return -1
        val played = t.sampleTime
        if (played < 0) return start
        val pos = if (loopMode) (played % length).toInt() else minOf(played, length.toLong()).toInt()
        return start + pos
    }

    actual fun release() = stop()
}

@Composable
actual fun StorageAccess(content: @Composable () -> Unit) = content()

/** The files of app/builtin are copied into the app bundle as the folder "builtin" (iosApp/project.yml). */
actual fun builtinAsset(path: String): ByteArray? {
    val root = platform.Foundation.NSBundle.mainBundle.resourcePath ?: return null
    val file = "$root/builtin/$path"
    return if (mlabeler.core.io.PlatformFs.exists(file)) runCatching { mlabeler.core.io.PlatformFs.read(file) }.getOrNull() else null
}
