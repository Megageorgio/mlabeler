package mlabeler.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import mlabeler.core.audio.Audio

expect object Platform {
    val name: String
    val isMac: Boolean
    /** Phones and tablets: touch first, no hover. */
    val isMobile: Boolean
    /** Two-letter code of the system language. */
    val systemLanguage: String
    /** Folder for settings and caches. */
    fun dataDir(): String
    /** Where the folder browser starts. */
    fun homeDir(): String
    /** Shortcuts to show in the folder browser (name to path). */
    fun places(): List<Pair<String, String>>
    /** Opens the system folder dialog; null when cancelled or not available. */
    fun pickFolderNative(title: String): String?
    val hasNativeFolderPicker: Boolean
    /** Decodes audio formats other than WAV; null if not supported here. */
    fun decodeAudio(path: String): Audio?
    fun openInFileManager(path: String)
    /** Phones: screen orientation ("auto", "landscape", "portrait") and full screen without system bars. */
    fun applyScreen(orientation: String, fullscreen: Boolean)
}

/** Creates an image from ARGB pixels. */
expect fun imageFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap

expect val resizeHorizontalIcon: PointerIcon

/** Audio output for one clip at a time. */
expect class AudioOut() {
    /** Plays samples [from, to) of [audio]; [loop] repeats until stopped. */
    fun play(audio: Audio, from: Int, to: Int, loop: Boolean)
    fun stop()
    val isPlaying: Boolean
    /** Current position as a sample index in the played audio, or -1. */
    fun position(): Int
    fun release()
}

/** Asks for storage access where the platform needs it; shows [content] when access is granted. */
@Composable
expect fun StorageAccess(content: @Composable () -> Unit)

/** Microphone input; [onChunk] is called from a background thread with samples in -1..1. */
expect class AudioIn() {
    /** Asks for microphone access where needed; [onResult] gets true when recording is allowed. */
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit)
    fun stop()
    val isRecording: Boolean
}
