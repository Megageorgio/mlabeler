package mlabeler.app.recorder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mlabeler.app.AudioIn
import mlabeler.app.AudioOut
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.AUDIO_EXTENSIONS
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace
import mlabeler.core.io.decodeGuess
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class RecorderSettings(
    val sampleRate: Int = 44100,
    /** Click track tempo; 0 = no click. */
    val bpm: Int = 0,
    val countInBeats: Int = 4,
    /** A WAV file in the folder played while recording (guide BGM); empty = none. */
    val guide: String = "",
    /** Go to the next item after a take is saved. */
    val autoNext: Boolean = true,
    /** Longest take, seconds. */
    val maxSeconds: Int = 30,
)

private val savedTake = L("Saved {0}", "Сохранено: {0}")
private val noMic = L("No access to the microphone", "Нет доступа к микрофону")
private val micError = L("Recording failed: {0}", "Не удалось записать: {0}")

/** Recording samples from a list (reclist) into a folder, one WAV per line. */
class RecorderState(val folder: String, private val app: AppState, private val scope: CoroutineScope) {
    private val fs = PlatformFs
    private val listPath = Paths.join(folder, "reclist.txt")
    private val settingsPath = Paths.join(Paths.join(folder, ".mlabeler"), "recorder.json")

    var settings by mutableStateOf(loadSettings())
        private set
    var names by mutableStateOf<List<String>>(emptyList())
        private set
    var comments by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var index by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
        private set
    var countIn by mutableIntStateOf(0)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    /** The take of the current item (just recorded or read from disk). */
    var take by mutableStateOf<Audio?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set

    private val input = AudioIn()
    private val output = AudioOut()
    private var chunks = kotlinx.coroutines.channels.Channel<FloatArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var stopJob: Job? = null

    init {
        loadList()
        loadTake()
    }

    private fun loadSettings(): RecorderSettings = runCatching {
        Workspace.json.decodeFromString(RecorderSettings.serializer(), fs.read(settingsPath).decodeToString())
    }.getOrDefault(RecorderSettings())

    fun updateSettings(t: (RecorderSettings) -> RecorderSettings) {
        settings = t(settings)
        runCatching { fs.write(settingsPath, Workspace.json.encodeToString(RecorderSettings.serializer(), settings).encodeToByteArray()) }
    }

    /** reclist.txt: one name per line, optional comment after a tab or space; else the WAV files already there. */
    fun loadList() {
        if (fs.exists(listPath)) {
            val text = decodeGuess(fs.read(listPath), "Shift_JIS").first
            val parsed = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { l ->
                val name = l.substringBefore('\t').substringBefore(' ').removeSuffix(".wav")
                name to l.drop(name.length).trim()
            }
            names = parsed.map { it.first }.distinct()
            comments = parsed.filter { it.second.isNotEmpty() }.toMap()
        } else {
            names = fs.list(folder).filter { Paths.ext(it) in AUDIO_EXTENSIONS }.map { Paths.stem(it) }.sorted()
        }
        index = index.coerceIn(0, (names.size - 1).coerceAtLeast(0))
        version++
    }

    fun listText(): String = if (fs.exists(listPath)) decodeGuess(fs.read(listPath), "Shift_JIS").first else names.joinToString("\n")

    fun saveList(text: String) {
        fs.write(listPath, text.encodeToByteArray())
        loadList()
        loadTake()
    }

    val current: String? get() = names.getOrNull(index)
    fun pathOf(name: String) = Paths.join(folder, "$name.wav")
    fun isRecorded(name: String): Boolean { version; return fs.exists(pathOf(name)) }

    fun select(i: Int) {
        if (recording) return
        index = i.coerceIn(0, (names.size - 1).coerceAtLeast(0))
        loadTake()
    }

    fun step(d: Int) = select(index + d)

    private fun loadTake() {
        val n = current
        take = if (n != null && fs.exists(pathOf(n))) runCatching { Wav.decode(fs.read(pathOf(n))) }.getOrNull() else null
    }

    fun toggle() = if (recording) stop() else record()

    fun record() {
        val name = current ?: return
        output.stop()
        input.requestPermission { ok ->
            if (!ok) { app.message(noMic(), error = true); return@requestPermission }
            scope.launch { startRecording(name) }
        }
    }

    private suspend fun startRecording(name: String) {
        val s = settings
        val sr = s.sampleRate
        chunks.close()
        chunks = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val sink = chunks
        // click track with a count-in, mixed with the guide when there is one
        val guide = s.guide.takeIf { it.isNotEmpty() }?.let { g ->
            runCatching { Wav.decode(fs.read(Paths.join(folder, g))) }.getOrNull()
        }
        val click = if (s.bpm > 0) clickTrack(sr, s.bpm, s.countInBeats, s.maxSeconds) else null
        val mix = when {
            click != null && guide != null && guide.sampleRate == sr -> FloatArray(maxOf(click.size, guide.samples.size)) { i ->
                (click.getOrElse(i) { 0f } + guide.samples.getOrElse(i) { 0f }).coerceIn(-1f, 1f)
            }
            click != null -> click
            guide != null -> guide.samples
            else -> null
        }
        try {
            input.start(sr) { chunk ->
                sink.trySend(chunk)
                var e = 0.0
                for (v in chunk) e += v * v
                level = sqrt(e / chunk.size.coerceAtLeast(1)).toFloat()
            }
        } catch (e: Exception) {
            app.message(micError.format(e.message ?: e.toString()), error = true)
            return
        }
        recording = true
        if (mix != null) runCatching { output.play(Audio(guide?.sampleRate ?: sr, mix), 0, mix.size, false) }
        val beat = if (s.bpm > 0) 60.0 / s.bpm else 0.0
        stopJob = scope.launch {
            if (s.bpm > 0) for (b in s.countInBeats downTo 1) { countIn = b; delay((beat * 1000).toLong()) }
            countIn = 0
            delay(s.maxSeconds * 1000L)
            if (recording) stop()
        }
        currentName = name
    }

    private var currentName: String? = null

    fun stop() {
        if (!recording) return
        recording = false
        stopJob?.cancel()
        countIn = 0
        input.stop()
        output.stop()
        level = 0f
        val name = currentName ?: return
        val parts = ArrayList<FloatArray>()
        while (true) parts += chunks.tryReceive().getOrNull() ?: break
        val data = FloatArray(parts.sumOf { it.size }).also { out -> var p = 0; for (c in parts) { c.copyInto(out, p); p += c.size } }
        if (data.size < settings.sampleRate / 10) return
        scope.launch {
            val audio = Audio(settings.sampleRate, data)
            withContext(Dispatchers.Default) {
                val path = pathOf(name)
                if (fs.exists(path)) {
                    // keep the previous take
                    runCatching { fs.copy(path, Paths.join(Paths.join(Paths.join(folder, ".mlabeler"), "takes"), "$name.${Workspace.timestamp()}.wav")) }
                }
                fs.write(path, Wav.encode16(audio))
            }
            take = audio
            version++
            app.message(savedTake.format("$name.wav"))
            if (settings.autoNext && index < names.size - 1) select(index + 1)
        }
    }

    fun playTake() {
        val t = take ?: return
        if (output.isPlaying) output.stop() else runCatching { output.play(t, 0, t.samples.size, false) }
    }

    fun close() {
        if (recording) stop()
        input.stop()
        output.release()
    }

    companion object {
        /** Clicks on every beat, a higher one on bar starts, for [seconds] after the count-in. */
        fun clickTrack(sr: Int, bpm: Int, countIn: Int, seconds: Int): FloatArray {
            val beat = 60.0 / bpm
            val beats = countIn + (seconds / beat).toInt()
            val out = FloatArray(((beats + 1) * beat * sr).toInt())
            val len = (0.03 * sr).toInt()
            for (b in 0 until beats) {
                val start = (b * beat * sr).toInt()
                val f = if (b % 4 == 0) 1760.0 else 1320.0
                val amp = if (b < countIn) 0.5 else 0.25
                for (i in 0 until len) {
                    if (start + i >= out.size) break
                    out[start + i] += (amp * sin(2 * PI * f * i / sr) * (1 - i.toDouble() / len)).toFloat()
                }
            }
            return out
        }
    }
}
