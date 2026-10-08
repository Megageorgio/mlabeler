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
    /** Note to sing at (e.g. "C4"): a line on the pitch and the deviation from it; empty = none. */
    val targetNote: String = "",
    /** Play the take once right after it is recorded. */
    val listenBack: Boolean = false,
)

private val savedTake = L("Saved {0}", "Сохранено: {0}")
private val noMic = L("No access to the microphone", "Нет доступа к микрофону")
private val trimmed = L("Only the selected part is kept; the previous take is in .mlabeler/takes", "Оставлен только выделенный фрагмент; прежний дубль — в .mlabeler/takes")
private val cutDone = L("The selected part is cut out; the previous take is in .mlabeler/takes", "Выделенный фрагмент вырезан; прежний дубль — в .mlabeler/takes")
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
    /** The part of the take chosen by dragging, seconds; null when none. */
    var selection by mutableStateOf<Pair<Double, Double>?>(null)
    var version by mutableIntStateOf(0)
        private set
    /** Pitch of the take, Hz per frame (0 = unvoiced). */
    var takePitch by mutableStateOf<mlabeler.core.dsp.Curve?>(null)
        private set
    /** Where playback of the take is now, seconds; -1 when stopped. */
    var playhead by mutableStateOf(-1.0)
        private set
    /** Where Space starts playback, seconds (set by clicking the take). */
    var cursor by mutableStateOf(0.0)

    /** Live input while recording, one value per [LIVE_STEP] seconds: peak level and pitch (MIDI, 0 = none). */
    var liveFrames by mutableIntStateOf(0)
        private set
    var liveWave = FloatArray(0)
        private set
    var livePitch = FloatArray(0)
        private set
    /** Current input pitch, MIDI note number with fraction; 0 when there is none. */
    var liveNote by mutableFloatStateOf(0f)
        private set
    private val ring = FloatArray(4096)
    private var ringPos = 0
    private var binPeak = 0f
    private var binFill = 0
    private var binSize = 441
    private var playJob: Job? = null

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

    /** Adds [line] (a name, optionally a comment after a space) to the list after the current one, and selects it. */
    fun addLine(line: String) {
        val l = line.trim()
        if (l.isEmpty()) return
        val name = l.substringBefore('\t').substringBefore(' ').removeSuffix(".wav")
        if (name in names) { select(names.indexOf(name)); return }
        val lines = listText().lines().toMutableList().let { ls -> if (ls.lastOrNull()?.isBlank() == true) ls.dropLast(1).toMutableList() else ls }
        // after the current line of the file (comments and blank lines kept where they are)
        val cur = current
        val at = lines.indexOfFirst { it.trim().substringBefore('\t').substringBefore(' ').removeSuffix(".wav") == cur }
        if (at >= 0) lines.add(at + 1, l) else lines.add(l)
        fs.write(listPath, (lines.joinToString("\n") + "\n").encodeToByteArray())
        loadList()
        select(names.indexOf(name).coerceAtLeast(0))
    }

    // ---------- editing a take ----------


    private fun backupAndWrite(name: String, audio: Audio) {
        val path = pathOf(name)
        if (fs.exists(path)) {
            runCatching { fs.copy(path, Paths.join(Paths.join(Paths.join(folder, ".mlabeler"), "takes"), "$name.${Workspace.timestamp()}.wav")) }
        }
        fs.write(path, Wav.encode16(audio))
    }

    private fun piece(a: Audio, from: Double, to: Double): Audio {
        val s0 = (from * a.sampleRate).toInt().coerceIn(0, a.samples.size)
        val s1 = (to * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
        return Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))
    }

    /** Keeps only the selected part of the take (the previous take stays in .mlabeler/takes). */
    fun trimToSelection() {
        val t = take ?: return
        val (a, b) = selection ?: return
        val name = current ?: return
        val out = piece(t, a, b)
        scope.launch {
            withContext(Dispatchers.Default) { backupAndWrite(name, out) }
            selection = null
            useTake(out)
            version++
            app.message(trimmed())
        }
    }

    /** Removes the selected part from the take. */
    fun cutSelection() {
        val t = take ?: return
        val (a, b) = selection ?: return
        val name = current ?: return
        val s0 = (a * t.sampleRate).toInt().coerceIn(0, t.samples.size)
        val s1 = (b * t.sampleRate).toInt().coerceIn(s0, t.samples.size)
        val out = Audio(t.sampleRate, t.samples.copyOfRange(0, s0) + t.samples.copyOfRange(s1, t.samples.size))
        scope.launch {
            withContext(Dispatchers.Default) { backupAndWrite(name, out) }
            selection = null
            useTake(out)
            version++
            app.message(cutDone())
        }
    }

    /** Saves the selected part as [newName].wav and adds that name to the list (e.g. "ava" out of "avata"). */
    fun selectionToFile(newName: String) {
        val t = take ?: return
        val (a, b) = selection ?: return
        val n = newName.trim().removeSuffix(".wav")
        if (n.isEmpty()) return
        val out = piece(t, a, b)
        val keep = index
        scope.launch {
            withContext(Dispatchers.Default) { backupAndWrite(n, out) }
            if (n !in names) {
                addLine(n)
                select(keep)
            }
            version++
            app.message(savedTake.format("$n.wav"))
        }
    }

    /** Target note as MIDI, or null. */
    val targetMidi: Double? get() = mlabeler.core.format.NoteNames.parse(settings.targetNote.trim())

    /** A second of the target note as a sine, to hear where to sing. */
    fun playTone() {
        val m = targetMidi ?: liveNote.takeIf { it > 0f }?.toDouble() ?: return
        if (recording) return
        val sr = 44100
        val hz = 440.0 * kotlin.math.exp((m - 69) / 12.0 * kotlin.math.ln(2.0))
        val n = sr
        val tone = FloatArray(n) { i ->
            val env = minOf(1.0, i / 2000.0, (n - i) / 4000.0)
            (0.25 * env * sin(2 * PI * hz * i / sr)).toFloat()
        }
        stopPlayback()
        runCatching { output.play(Audio(sr, tone), 0, n, false) }
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
        stopPlayback()
        useTake(if (n != null && fs.exists(pathOf(n))) runCatching { Wav.decode(fs.read(pathOf(n))) }.getOrNull() else null)
    }

    private var pitchJob: Job? = null

    private fun useTake(a: Audio?) {
        take = a
        selection = null
        cursor = 0.0
        takePitch = null
        pitchJob?.cancel()
        if (a == null) return
        pitchJob = scope.launch {
            val mono = if (a.channels > 1) FloatArray(a.samples.size / a.channels) { a.samples[it * a.channels] } else a.samples
            val c = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(mono, a.sampleRate, hop = 0.01) }
            if (take === a) takePitch = c
        }
    }

    /** Input from the microphone: level, the scrolling picture and the pitch. Runs on the audio thread. */
    private fun onInput(chunk: FloatArray, sr: Int) {
        var e = 0.0
        for (v in chunk) {
            e += v * v
            ring[ringPos] = v
            ringPos = (ringPos + 1) % ring.size
            val a = kotlin.math.abs(v)
            if (a > binPeak) binPeak = a
            if (++binFill >= binSize) {
                val f = liveFrames
                if (f < liveWave.size) {
                    liveWave[f] = binPeak
                    // pitch every few bins over the last ~90 ms
                    livePitch[f] = if (f % 3 == 0 || f == 0) liveNow(sr) else livePitch[f - 1]
                    liveNote = livePitch[f]
                    liveFrames = f + 1
                }
                binPeak = 0f
                binFill = 0
            }
        }
        level = sqrt(e / chunk.size.coerceAtLeast(1)).toFloat()
    }

    private fun liveNow(sr: Int): Float {
        val n = ring.size
        val m = 2048
        val buf = FloatArray(m) { ring[(ringPos + n - m + it) % n] }
        val c = mlabeler.core.dsp.Pitch.yin(buf, sr, hop = 1.0, fmin = 65.0, fmax = 1100.0)
        val hz = c.values.lastOrNull { it > 0f } ?: 0f
        return if (hz > 0f) mlabeler.core.dsp.Pitch.hzToMidi(hz.toDouble()).toFloat() else 0f
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
            val bins = (s.maxSeconds + 2 * s.countInBeats + 2) * (1.0 / LIVE_STEP).toInt()
            liveWave = FloatArray(bins)
            livePitch = FloatArray(bins)
            liveFrames = 0
            liveNote = 0f
            binSize = (sr * LIVE_STEP).toInt()
            binPeak = 0f; binFill = 0
            ring.fill(0f)
            stopPlayback()
            input.start(sr) { chunk ->
                sink.trySend(chunk)
                onInput(chunk, sr)
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
        liveNote = 0f
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
            useTake(audio)
            version++
            app.message(savedTake.format("$name.wav"))
            if (settings.listenBack) playRange(0.0, audio.duration)
            else if (settings.autoNext && index < names.size - 1) select(index + 1)
        }
    }

    /** Space: plays from the cursor, or stops. */
    fun playTake() {
        val t = take ?: return
        if (playhead >= 0) stopPlayback() else playRange(cursor, t.duration)
    }

    /** Plays the take from [from] to [to] seconds and moves the playhead. */
    fun playRange(from: Double, to: Double) {
        val t = take ?: return
        if (recording) return
        val a = (from * t.sampleRate).toInt().coerceIn(0, t.samples.size)
        val b = (to * t.sampleRate).toInt().coerceIn(0, t.samples.size)
        if (b - a < t.sampleRate / 50) return
        playJob?.cancel()
        runCatching { output.play(t, a, b, false) }.onFailure { return }
        playhead = from
        playJob = scope.launch {
            delay(30)
            while (isActive && output.isPlaying) {
                val p = runCatching { output.position() }.getOrDefault(-1)
                if (p >= 0) playhead = p.toDouble() / t.sampleRate
                delay(30)
            }
            playhead = -1.0
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        output.stop()
        playhead = -1.0
    }

    fun close() {
        if (recording) stop()
        input.stop()
        output.release()
    }

    companion object {
        /** Seconds per value of the live picture. */
        const val LIVE_STEP = 0.01

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
