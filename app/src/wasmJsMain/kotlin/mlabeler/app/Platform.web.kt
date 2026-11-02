@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package mlabeler.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import mlabeler.core.audio.Audio
import mlabeler.core.io.toBinary
import mlabeler.core.io.binaryBytes
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@JsName("MLabelerWeb")
external object Browser {
    fun mobile(): Boolean
    fun mac(): Boolean
    fun language(): String
    fun date(epoch: Double): String
    fun play(binary: String, sampleRate: Int, from: Int, to: Int, loop: Boolean)
    fun stop()
    fun position(): Int
    fun playing(): Boolean
    fun microphonePermission(result: (Boolean) -> Unit)
    fun record(sampleRate: Int, chunk: (String) -> Unit)
    fun stopRecording()
    fun recording(): Boolean
    fun bind(imported: (String) -> Unit, save: ((Boolean) -> Unit) -> Unit, beforeImport: ((Boolean) -> Unit) -> Unit)
    fun setLanguage(code: String)
    fun setTheme(json: String)
    fun setBusy(busy: Boolean)
    fun setUnsaved(unsaved: Boolean)
    fun asset(path: String): String?
    fun pickFile(extensions: String, done: (String?) -> Unit)
    fun importProject()
    fun font(name: String): String
}

actual object Platform {
    actual val name = "Web"
    actual val isMac get() = Browser.mac()
    actual val isMobile get() = Browser.mobile()
    actual val cores = 1
    actual val systemLanguage get() = Browser.language()
    actual fun dataDir() = "/settings"
    actual val portableDir: String? = null
    actual val legacyWindows = false
    actual fun homeDir() = "/project"
    actual fun places() = listOf("mLabeler" to "/project")
    actual val hasNativeFolderPicker = false
    actual fun pickFolderNative(title: String, start: String?): String? = null
    actual suspend fun pickFileNative(title: String, extensions: List<String>, start: String?): String? = suspendCancellableCoroutine { cont ->
        val supported = if ("wav" in extensions) listOf("wav") else extensions
        Browser.pickFile(supported.joinToString(",") { ".$it" }) { if (cont.isActive) cont.resume(it) }
    }
    actual fun decodeAudio(path: String): Audio? = null
    actual fun openInFileManager(path: String) = Unit
    actual fun applyScreen(orientation: String, fullscreen: Boolean) = Unit
}

actual val resizeHorizontalIcon = PointerIcon.Hand
actual class AudioOut actual constructor() {
    actual fun play(audio: Audio, from: Int, to: Int, loop: Boolean) {
        val bytes = ByteArray((to - from) * 4)
        for (i in 0 until to - from) {
            val bits = audio.samples[from + i].toBits()
            for (k in 0..3) bytes[4 * i + k] = (bits ushr (8 * k)).toByte()
        }
        Browser.play(bytes.toBinary(), audio.sampleRate, from, to, loop)
    }
    actual fun stop() = Browser.stop()
    actual val isPlaying get() = Browser.playing()
    actual fun position() = Browser.position()
    actual fun release() = stop()
}
@Composable actual fun StorageAccess(content: @Composable () -> Unit) = content()
actual class AudioIn actual constructor() {
    actual fun requestPermission(onResult: (Boolean) -> Unit) = Browser.microphonePermission(onResult)
    actual fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit) {
        Browser.record(sampleRate) { binary ->
            val bytes = binary.binaryBytes()
            onChunk(FloatArray(bytes.size / 4) { i ->
                var bits = 0
                for (k in 0..3) bits = bits or ((bytes[4 * i + k].toInt() and 255) shl (8 * k))
                Float.fromBits(bits)
            })
        }
    }
    actual fun stop() = Browser.stopRecording()
    actual val isRecording get() = Browser.recording()
}
actual fun systemFontNames(): List<String> = emptyList()
actual fun systemFontFamily(name: String): androidx.compose.ui.text.font.FontFamily? = null
actual fun formatDateTime(epochMs: Long): String = Browser.date(epochMs.toDouble())
actual fun builtinAsset(path: String): ByteArray? = null
actual fun openBrowserImport() = Browser.importProject()
actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Boolean): Modifier = this

actual object ScreenColor {
    actual val supported = false
    actual fun pick(onPicked: (Int?) -> Unit) = onPicked(null)
}
actual object RichPresence {
    actual val supported = false
    actual fun show(info: PresenceInfo?) = Unit
}
actual object SelfUpdate {
    actual val supported = false
    actual val installsOnClose = false
    actual fun canUse(download: String?) = false
    actual suspend fun prepare(download: String, tag: String, sha256: String?, progress: (Float) -> Unit): Unit = error("Unavailable")
    actual fun prepared(): String? = null
    actual fun discard() = Unit
    actual fun install(restart: Boolean): String? = null
}
