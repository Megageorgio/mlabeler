package mlabeler.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import mlabeler.app.i18n.S
import mlabeler.app.theme.T
import mlabeler.app.ui.Btn
import mlabeler.core.audio.Audio
import java.io.File
import java.nio.ByteOrder
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object AndroidContext {
    lateinit var context: Context
        private set

    fun init(c: Context) {
        context = c
    }

    /** The running activity (for orientation and full screen). */
    var activity: android.app.Activity? = null

    /** Set by the activity: asks for the microphone permission. */
    var askMic: ((callback: (Boolean) -> Unit) -> Unit)? = null
}

actual object Platform {
    actual val name: String = "Android"
    actual val isMac: Boolean = false
    actual val isMobile: Boolean = true
    actual val systemLanguage: String get() = Locale.getDefault().language

    actual fun dataDir(): String = AndroidContext.context.filesDir.path
    actual fun homeDir(): String = Environment.getExternalStorageDirectory().path

    actual fun places(): List<Pair<String, String>> {
        val root = Environment.getExternalStorageDirectory()
        val list = mutableListOf("Internal" to root.path)
        for (n in listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOCUMENTS)) {
            val f = File(root, n)
            if (f.isDirectory) list += n to f.path
        }
        // SD cards and USB drives
        AndroidContext.context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
            val volume = f.path.substringBefore("/Android/")
            list += File(volume).name to volume
        }
        return list
    }

    actual val hasNativeFolderPicker: Boolean = false
    actual fun pickFolderNative(title: String): String? = null

    actual fun decodeAudio(path: String): Audio? = MediaDecoder.decode(path)

    actual fun openInFileManager(path: String) = Unit

    actual fun applyScreen(orientation: String, fullscreen: Boolean) {
        val a = AndroidContext.activity ?: return
        a.runOnUiThread {
            a.requestedOrientation = when (orientation) {
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
            val ctl = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (fullscreen) {
                ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

actual fun imageFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

actual val resizeHorizontalIcon: PointerIcon = PointerIcon.Hand

actual class AudioOut actual constructor() {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var start = 0
    @Volatile private var length = 0
    @Volatile private var loopMode = false

    actual fun play(audio: Audio, from: Int, to: Int, loop: Boolean) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        start = from
        length = to - from
        loopMode = loop
        stopFlag = false
        t.play()
        thread = Thread {
            val buf = ShortArray(2048)
            var written = 0L
            try {
                do {
                    var pos = from
                    while (pos < to && !stopFlag) {
                        val n = minOf(to - pos, buf.size)
                        for (i in 0 until n) buf[i] = (audio.samples[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                        t.write(buf, 0, n)
                        written += n
                        pos += n
                    }
                } while (loop && !stopFlag)
                // wait until the written audio has been played
                while (!stopFlag && t.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written) Thread.sleep(10)
            } catch (_: Exception) {
            } finally {
                runCatching { t.stop(); t.release() }
                if (track === t) track = null
            }
        }.apply { isDaemon = true; start() }
    }

    actual fun stop() {
        stopFlag = true
        track?.let { runCatching { it.pause(); it.flush() } }
        thread?.join(300)
        thread = null
        track = null
    }

    actual val isPlaying: Boolean get() = track != null

    actual fun position(): Int {
        val t = track ?: return -1
        if (length <= 0) return -1
        val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val p = if (loopMode) (played % length).toInt() else minOf(played, length.toLong()).toInt()
        return start + p
    }

    actual fun release() = stop()
}

private fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

@Composable
actual fun StorageAccess(content: @Composable () -> Unit) {
    val context = AndroidContext.context
    var granted by remember { mutableStateOf(hasAccess(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasAccess(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasAccess(context) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { granted = hasAccess(context) }
    if (granted) return content()
    val c = T.c
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(S.allowFilesHint(), color = c.text, fontSize = 15.sp)
        Btn(S.allowFiles(), primary = true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
                runCatching { settings.launch(intent) }.onFailure { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else {
                legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
    }
}

/** Decodes mp3, flac, ogg, m4a… with MediaCodec into a mono mix. */
private object MediaDecoder {
    fun decode(path: String): Audio? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val out = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var acc = 0f
                                repeat(channels) { acc += fb.get() }
                                out.add(acc / channels)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var acc = 0f
                                repeat(channels) { acc += sb.get() / 32768f }
                                out.add(acc / channels)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            return Audio(rate, out.toArray(), channels)
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}

private class FloatArrayBuilder {
    private var data = FloatArray(1 shl 16)
    private var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun toArray() = data.copyOf(size)
}
