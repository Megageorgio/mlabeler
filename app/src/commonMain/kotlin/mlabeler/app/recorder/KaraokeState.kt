package mlabeler.app.recorder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.app.AudioIn
import mlabeler.app.AudioOut
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.toolkit.ToolkitException
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.ALL_AUDIO_EXTENSIONS
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.decodeGuess

/** One line of lyrics: when it starts (s) and what is sung. */
data class LyricLine(val time: Double, val text: String)

private val loadingT = L("Reading {0}…", "Чтение {0}…")
private val cantRead = L("Can't read {0}", "Не удалось прочитать {0}")
private val uploadingT = L("Sending the song to the toolkit…", "Песня передаётся тулкиту на этом компьютере…")
private val separatingT = L("Separating the voice and the music…", "Голос отделяется от музыки…")
private val recognisingT = L("Recognising the words…", "Распознавание слов…")
private val fetchingT = L("Taking the results…", "Получение результатов…")
private val separatedT = L("The backing track is ready", "Минус готов")
private val recognisedT = L("{0} lines recognised; check them, the times are approximate", "Распознано строк: {0}; проверьте их, время примерное")
private val nothingHeard = L("No words were recognised", "Слов не распознано")
private val addedT = L("Added to the songs: {0}", "Добавлено в песни: {0}")
private val noMic = L("No access to the microphone", "Нет доступа к микрофону")
private val micError = L("Recording failed: {0}", "Не удалось записать: {0}")
private val takeSaved = L("Saved {0} with its lyrics in {1}", "Сохранено: {0} и текст к нему, в {1}")

/**
 * Recording one's own singing over a song: the backing track plays (in headphones), the lyrics go line by line, the
 * microphone is recorded into the dataset folder. The songs, their lyrics and backing tracks are kept apart from the
 * dataset, in the program's data folder. Separation and word recognition run only on request, in the toolkit.
 */
class KaraokeState(
    /** Where the recorded takes go (the dataset). */
    val folder: String,
    private val app: AppState,
    private val scope: CoroutineScope,
) {
    private val fs = PlatformFs
    private val output = AudioOut()
    private val input = AudioIn()

    /** The songs: their files, lyrics (.lrc) and separated parts, outside any dataset. */
    val songsDir: String = Paths.join(Platform.dataDir(), "songs")
    private val partsDir = Paths.join(songsDir, "parts")

    var songs by mutableStateOf<List<String>>(emptyList())
        private set
    var song by mutableStateOf<String?>(null)
        private set
    var audio by mutableStateOf<Audio?>(null)
        private set
    /** The song without its voice (after separation). */
    var music by mutableStateOf<Audio?>(null)
        private set
    /** The song's own voice (after separation): can be mixed in quietly as a guide. */
    var voice by mutableStateOf<Audio?>(null)
        private set
    /** How loud the song's own voice is in the headphones, 0..1 (only with a separated backing track). */
    var guideLevel by mutableFloatStateOf(0f)
    var lines by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    var dirty by mutableStateOf(false)
        private set
    var position by mutableDoubleStateOf(0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Seconds before a line to start from when jumping to it. */
    var lead by mutableDoubleStateOf(3.0)
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableDoubleStateOf(-1.0)
        private set
    /** What the toolkit reports it is doing (installing, loading the model…). */
    var stageText by mutableStateOf("")
        private set
    /** Language of the lyrics for recognition (Whisper guesses wrong on singing over music, so it is set, not detected). */
    var language by mutableStateOf("")
        private set
    private val languagePath = Paths.join(songsDir, "language.txt")

    fun updateLanguage(code: String) {
        language = code.trim().lowercase()
        runCatching { fs.write(languagePath, language.encodeToByteArray()) }
    }

    // recording
    var recording by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    /** Name of the next take (without .wav). */
    var takeName by mutableStateOf("")
    /** The last recorded take, to listen to. */
    var lastTake by mutableStateOf<Audio?>(null)
        private set
    var lastTakeName by mutableStateOf("")
        private set
    private var recStart = 0.0
    private var chunks = kotlinx.coroutines.channels.Channel<FloatArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val recRate = 44100

    private var playJob: Job? = null
    private var workJob: Job? = null

    // ---------- pitch: the singer's against the song's ----------

    /** Pitch of the song's own voice (from the separated voice), MIDI per [PITCH_HOP]; NaN = unvoiced. */
    var refPitch by mutableStateOf<FloatArray?>(null)
        private set
    private var refJob: Job? = null
    /** The singer's pitch heard while recording, MIDI per [PITCH_HOP] of song time; NaN = nothing. */
    var livePitch: FloatArray = FloatArray(0)
        private set
    /** Bumped while recording so the pitch picture redraws. */
    var liveTick by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    /** The note heard now (MIDI), 0 when silent. */
    var liveNote by mutableFloatStateOf(0f)
        private set
    private val ring = FloatArray(2048)
    private var ringPos = 0
    private var sinceYin = 0

    /** How well the last take followed the song's melody. */
    data class Score(val inTune: Double, val meanCents: Double, val perLine: Map<Int, Double>, val octaveShift: Int)
    var score by mutableStateOf<Score?>(null)
        private set

    /** Key of the backing track, semitones (the melody line and the score follow it). */
    var semitones by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    /** Tempo of the backing track: 1 = as recorded, 0.75 = slower. */
    var speed by mutableDoubleStateOf(1.0)
        private set
    /** Sound card delay (output + input), ms: the voice in a take comes this much after the music. */
    var latencyMs by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    /** Singing with the pitch shown but nothing saved. */
    var practice by mutableStateOf(false)
        private set
    /** Each line of the take goes to its own file. */
    var splitLines by mutableStateOf(false)
    /** Takes of this session with their scores, to compare. */
    data class TakeInfo(val name: String, val audio: Audio, val score: Score?, val leak: Boolean)
    var takes by mutableStateOf<List<TakeInfo>>(emptyList())
        private set
    /** Set when the last take seems to contain the backing track (sung without headphones). */
    var leaked by mutableStateOf(false)
        private set
    /** True while the backing track is being made slower or higher. */
    var preparing by mutableStateOf(false)
        private set
    private var prepared: Pair<List<Any>, Audio>? = null
    private val latencyPath = Paths.join(songsDir, "latency.txt")

    fun updateKey(st: Int) { semitones = st.coerceIn(-12, 12); prepared = null; if (playing && !recording) play() }
    fun updateSpeed(v: Double) { speed = v.coerceIn(0.5, 1.0); prepared = null; if (playing && !recording) play() }
    fun updateLatency(ms: Int) {
        latencyMs = ms.coerceIn(0, 1000)
        runCatching { fs.write(latencyPath, latencyMs.toString().encodeToByteArray()) }
    }

    /** While playing (not recording), go back to the start of the current line when the next one begins. */
    var loopLine by mutableStateOf(false)
    private var loopFrom = -1

    private fun analyseVoice(v: Audio?) {
        refJob?.cancel()
        refPitch = null
        if (v == null) return
        refJob = scope.launch {
            val c = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(v.samples, v.sampleRate, hop = PITCH_HOP, fmin = 65.0, fmax = 1100.0) }
            refPitch = FloatArray(c.values.size) { i -> val hz = c.values[i]; if (hz > 0f) mlabeler.core.dsp.Pitch.hzToMidi(hz.toDouble()).toFloat() else Float.NaN }
        }
    }

    /** Called from the microphone thread: the pitch of the last ~46 ms, written at the current song time. */
    private fun hearPitch(chunk: FloatArray) {
        for (v in chunk) { ring[ringPos] = v; ringPos = (ringPos + 1) % ring.size }
        sinceYin += chunk.size
        if (sinceYin < 441) return
        sinceYin = 0
        val buf = FloatArray(ring.size) { ring[(ringPos + it) % ring.size] }
        var peak = 0f
        for (v in buf) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        val hz = if (peak < 0.02f) 0f else mlabeler.core.dsp.Pitch.yin(buf, recRate, hop = 1.0, fmin = 65.0, fmax = 1100.0).values.lastOrNull { it > 0f } ?: 0f
        val m = if (hz > 0f) mlabeler.core.dsp.Pitch.hzToMidi(hz.toDouble()).toFloat() else 0f
        liveNote = m
        val i = ((position - latencyMs / 1000.0) / PITCH_HOP).toInt()
        val arr = livePitch
        if (i in arr.indices) {
            arr[i] = if (m > 0f) m else Float.NaN
            // fill the 10 ms steps between two readings
            if (i > 0 && arr[i - 1] == 0f) arr[i - 1] = arr[i]
        }
        liveTick++
    }

    /** Share of sung frames within half a semitone of the song's voice; an octave up or down counts as right. */
    private fun scoreTake(from: Double, to: Double): Score? {
        val ref0 = refPitch ?: return null
        val ref = FloatArray(ref0.size) { ref0[it] + semitones }
        val live = livePitch
        val a = (from / PITCH_HOP).toInt().coerceAtLeast(0)
        val b = minOf((to / PITCH_HOP).toInt(), ref.size, live.size)
        // the octave the singer chose: the most common whole-octave offset
        val shifts = IntArray(5)
        for (i in a until b) {
            val r = ref[i]; val l = live[i]
            if (r.isNaN() || l.isNaN() || l == 0f) continue
            val o = kotlin.math.round((l - r) / 12.0).toInt().coerceIn(-2, 2)
            shifts[o + 2]++
        }
        val octave = shifts.indices.maxByOrNull { shifts[it] }!! - 2
        var n = 0; var good = 0; var cents = 0.0
        val lineN = HashMap<Int, Int>(); val lineGood = HashMap<Int, Int>()
        for (i in a until b) {
            val r = ref[i]; val l = live[i]
            if (r.isNaN() || l.isNaN() || l == 0f) continue
            val d = kotlin.math.abs(l - r - 12 * octave)
            n++; cents += d * 100
            val t = i * PITCH_HOP
            val line = lines.indexOfLast { it.time <= t }
            lineN[line] = (lineN[line] ?: 0) + 1
            if (d <= 0.5) { good++; lineGood[line] = (lineGood[line] ?: 0) + 1 }
        }
        if (n < 50) return null
        return Score(good.toDouble() / n, cents / n, lineN.filter { it.value >= 20 }.mapValues { (k, v) -> (lineGood[k] ?: 0).toDouble() / v }, octave)
    }

    init {
        fs.mkdirs(songsDir)
        latencyMs = runCatching { fs.read(latencyPath).decodeToString().trim().toInt() }.getOrDefault(0)
        language = runCatching { fs.read(languagePath).decodeToString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: mlabeler.app.i18n.Lang.current
        reloadSongs()
        songs.firstOrNull()?.let { open(it) }
    }

    fun reloadSongs() {
        songs = runCatching { fs.list(songsDir).filter { Paths.ext(it).lowercase() in ALL_AUDIO_EXTENSIONS }.map { Paths.name(it) }.sorted() }.getOrDefault(emptyList())
    }

    val duration: Double get() = audio?.duration ?: 0.0
    private fun stem(name: String) = Paths.stem(name)
    private fun lrcPath(name: String) = Paths.join(songsDir, stem(name) + ".lrc")
    private fun partPath(name: String, part: String) = Paths.join(partsDir, "${stem(name)}.$part.wav")

    private fun decode(path: String): Audio? = runCatching {
        val bytes = fs.read(path)
        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(path)
    }.getOrNull()

    /** Copies a song file into the songs and opens it. */
    fun addSong(path: String) {
        val p = path.trim().trim('"')
        if (p.isEmpty() || !fs.exists(p)) { app.message(cantRead.format(p), error = true); return }
        val name = Paths.name(p)
        scope.launch {
            busy = loadingT.format(name)
            runCatching { withContext(Dispatchers.Default) { fs.copy(p, Paths.join(songsDir, name)) } }
                .onFailure { busy = null; app.message(it.message ?: it.toString(), error = true); return@launch }
            // lyrics lying next to it come along
            val lrc = Paths.join(Paths.parent(p), stem(name) + ".lrc")
            if (fs.exists(lrc) && !fs.exists(lrcPath(name))) runCatching { fs.copy(lrc, lrcPath(name)) }
            busy = null
            reloadSongs()
            open(name)
            app.message(addedT.format(name))
        }
    }

    fun open(name: String) {
        if (recording) stopRecording()
        if (dirty) save()
        stop()
        song = name
        audio = null; music = null; voice = null
        analyseVoice(null)
        score = null
        position = 0.0
        lines = readLrc(lrcPath(name))
        dirty = false
        takeName = nextTakeName(name)
        workJob?.cancel()
        workJob = scope.launch {
            busy = loadingT.format(name)
            val a = withContext(Dispatchers.Default) { decode(Paths.join(songsDir, name)) }
            if (a == null) app.message(cantRead.format(name), error = true)
            audio = a
            music = withContext(Dispatchers.Default) { partPath(name, "music").takeIf { fs.exists(it) }?.let { decode(it) } }
            voice = withContext(Dispatchers.Default) { partPath(name, "voice").takeIf { fs.exists(it) }?.let { decode(it) } }
            analyseVoice(voice)
            busy = null
        }
    }

    /** First free "<song>_NN" in the dataset folder. */
    private fun nextTakeName(song: String): String {
        val base = stem(song).map { if (it.isLetterOrDigit() || it in "-_") it else '_' }.joinToString("").ifEmpty { "take" }
        var n = 1
        while (fs.exists(Paths.join(folder, "${base}_${n.toString().padStart(2, '0')}.wav"))) n++
        return "${base}_${n.toString().padStart(2, '0')}"
    }

    /** What plays in the headphones: the backing track with some of the song's voice, or the whole song. */
    private fun mixed(): Audio? {
        val m = music ?: return audio
        val v = voice
        val g = guideLevel
        if (v == null || g <= 0.001f || v.sampleRate != m.sampleRate) return m
        val out = FloatArray(m.samples.size) { i -> (m.samples[i] + g * v.samples.getOrElse(i) { 0f }).coerceIn(-1f, 1f) }
        return Audio(m.sampleRate, out)
    }

    /** The backing as heard: mixed, then in the chosen key and tempo (made once per setting). */
    private suspend fun backing(): Audio? {
        val key = listOf(music ?: audio ?: return null, voice ?: 0, guideLevel, semitones, speed)
        prepared?.let { (k, a) -> if (k == key) return a }
        val base = mixed() ?: return null
        val a = if (semitones == 0 && speed == 1.0) base else {
            preparing = true
            try { withContext(Dispatchers.Default) { Audio(base.sampleRate, mlabeler.core.dsp.Stretch.process(base.samples, base.sampleRate, speed, semitones.toDouble())) } }
            finally { preparing = false }
        }
        prepared = key to a
        return a
    }

    /** Song time of a sample of the played (maybe slower) backing, and back. */
    private fun songTime(sample: Int, a: Audio) = sample.toDouble() / a.sampleRate * speed
    private fun sampleAt(t: Double, a: Audio) = (t / speed * a.sampleRate).toInt()

    // ---------- playback ----------

    private var playing0: Audio? = null

    fun play(from: Double = position) {
        playJob?.cancel()
        playJob = scope.launch {
            val a = backing() ?: return@launch
            val start = sampleAt(from.coerceIn(0.0, duration), a)
            if (a.samples.size - start < a.sampleRate / 20) { if (recording) stopRecording(); return@launch }
            output.stop()
            runCatching { output.play(a, start, a.samples.size, false) }.onFailure { return@launch }
            playing0 = a
            position = from
            playing = true
            delay(30)
            loopFrom = if (loopLine && (!recording || practice)) current else -1
            while (isActive && output.isPlaying) {
                val p = output.position()
                if (p >= 0) position = songTime(p, a)
                val lf = loopFrom
                if (loopLine && (!recording || practice) && lf >= 0 && lf + 1 < lines.size && position >= lines[lf + 1].time) {
                    val back = (lines[lf].time - minOf(lead, 1.0)).coerceAtLeast(0.0)
                    output.stop()
                    runCatching { output.play(a, sampleAt(back, a), a.samples.size, false) }
                    position = back
                    delay(60)
                    continue
                }
                delay(30)
            }
            playing = false
            if (recording) stopRecording()
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        output.stop()
        playing = false
    }

    fun toggle() = if (recording) stopRecording() else if (playing) stop() else play()

    fun seek(t: Double) {
        if (recording) return
        val v = t.coerceIn(0.0, duration)
        if (playing) play(v) else position = v
    }

    /** Index of the line being sung now (the last one started), -1 before the first. */
    val current: Int get() {
        val p = position
        var i = -1
        for ((k, l) in lines.withIndex()) if (l.time <= p + 0.05) i = k else break
        return i
    }

    fun toFirstWords() {
        val t = lines.firstOrNull()?.time ?: return seek(0.0)
        seek((t - lead).coerceAtLeast(0.0))
    }

    fun toLine(i: Int, withLead: Boolean = true) {
        val l = lines.getOrNull(i) ?: return
        loopFrom = i
        seek((l.time - if (withLead) lead else 0.0).coerceAtLeast(0.0))
    }

    fun stepLine(d: Int) {
        if (lines.isEmpty()) return
        val cur = current
        val target = if (d < 0 && cur >= 0 && position - lines[cur].time > 1.5) cur else cur + d
        toLine(target.coerceIn(0, lines.size - 1))
    }

    // ---------- recording ----------

    /** Starts the backing track from the current place and records the microphone until stopped (or the song ends). */
    fun record(practiceOnly: Boolean = false) {
        if (recording || audio == null) return
        practice = practiceOnly
        stop()
        input.requestPermission { ok ->
            if (!ok) { app.message(noMic(), error = true); return@requestPermission }
            scope.launch {
                chunks.close()
                chunks = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val sink = chunks
                try {
                    input.start(recRate) { chunk ->
                        sink.trySend(chunk.copyOf())
                        hearPitch(chunk)
                        var p = 0f
                        for (v in chunk) { val a = kotlin.math.abs(v); if (a > p) p = a }
                        level = p
                    }
                } catch (e: Exception) {
                    app.message(micError.format(e.message ?: e.toString()), error = true)
                    return@launch
                }
                recStart = position
                livePitch = FloatArray((duration / PITCH_HOP).toInt() + 2)
                score = null
                leaked = false
                recording = true
                play(position)
            }
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        input.stop()
        val end = position
        stop()
        level = 0f
        liveNote = 0f
        score = scoreTake(recStart, end)
        val parts = ArrayList<FloatArray>()
        while (true) parts += chunks.tryReceive().getOrNull() ?: break
        val data = FloatArray(parts.sumOf { it.size }).also { out -> var p = 0; for (c in parts) { c.copyInto(out, p); p += c.size } }
        if (data.size < recRate / 2 || practice) { practice = false; return }
        val played = playing0
        val name = takeName.trim().removeSuffix(".wav").ifEmpty { song?.let { nextTakeName(it) } ?: "take" }
        // the words sung in the recorded part go next to the take (autolabel can use them)
        val words = lines.filterIndexed { i, l ->
            val until = lines.getOrNull(i + 1)?.time ?: duration
            until > recStart + 0.3 && l.time < end - 0.3
        }.joinToString("\n") { it.text }.trim()
        val take = Audio(recRate, data)
        val start = recStart
        val sc = score
        val split = splitLines
        val lat = latencyMs / 1000.0
        scope.launch {
            val leak = withContext(Dispatchers.Default) { played != null && leakage(take, played, sampleAt(start, played)) }
            withContext(Dispatchers.Default) {
                fun save(n: String, a: Audio, text: String) {
                    val path = Paths.join(folder, "$n.wav")
                    if (fs.exists(path)) runCatching { fs.copy(path, Paths.join(Paths.join(Paths.join(folder, ".mlabeler"), "takes"), "$n.${mlabeler.core.io.Workspace.timestamp()}.wav")) }
                    fs.write(path, Wav.encode16(a))
                    if (text.isNotEmpty()) fs.write(Paths.join(folder, "$n.txt"), (text + "\n").encodeToByteArray())
                }
                if (!split) save(name, take, words)
                else {
                    // each line from a little before it starts (in the take: later by the sound card delay) to the next
                    val rate = take.sampleRate.toDouble() / speed
                    for ((i, l) in lines.withIndex()) {
                        val until = lines.getOrNull(i + 1)?.time ?: duration
                        val a = ((l.time - start + lat - 0.15) * rate).toInt().coerceAtLeast(0)
                        val b = ((until - start + lat - 0.05) * rate).toInt().coerceAtMost(take.samples.size)
                        if (b - a < take.sampleRate / 3) continue
                        save("${name}_${(i + 1).toString().padStart(2, '0')}", Audio(take.sampleRate, take.samples.copyOfRange(a, b)), l.text)
                    }
                }
            }
            lastTake = take
            lastTakeName = name
            leaked = leak
            takes = (takes + TakeInfo(name, take, sc, leak)).takeLast(12)
            app.message(takeSaved.format(if (split) "${name}_NN.wav" else "$name.wav", Paths.name(folder)))
            song?.let { takeName = nextTakeName(it) }
        }
    }

    fun playLastTake() { lastTake?.let { playTake(it) } }

    fun playTake(t: Audio) {
        stop()
        runCatching { output.play(t, 0, t.samples.size, false) }
    }

    /**
     * Whether the take has the backing track in it: the loudness of the take follows the loudness of what was played
     * (at a delay of up to 0.3 s) much more closely than a voice would.
     */
    private fun leakage(take: Audio, played: Audio, from: Int): Boolean {
        val step = take.sampleRate / 100
        fun env(x: FloatArray, start: Int, n: Int) = DoubleArray(n) { k ->
            var e = 0.0
            for (j in 0 until step) { val v = x.getOrElse(start + k * step + j) { 0f }; e += v * v }
            kotlin.math.ln(1e-6 + e / step)
        }
        val n = minOf(take.samples.size / step, (played.samples.size - from) / step) - 40
        if (n < 300) return false
        val t = env(take.samples, 0, n + 30)
        val p = env(played.samples, from, n)
        fun corr(lag: Int): Double {
            var sa = 0.0; var sb = 0.0
            for (i in 0 until n) { sa += t[i + lag]; sb += p[i] }
            val ma = sa / n; val mb = sb / n
            var c = 0.0; var va = 0.0; var vb = 0.0
            for (i in 0 until n) { val a = t[i + lag] - ma; val b = p[i] - mb; c += a * b; va += a * a; vb += b * b }
            return if (va <= 0 || vb <= 0) 0.0 else c / kotlin.math.sqrt(va * vb)
        }
        return (0..30).maxOf { corr(it) } > 0.6
    }

    // ---------- editing the lyrics ----------

    private fun change(new: List<LyricLine>) { lines = new.sortedBy { it.time }; dirty = true }

    fun setText(i: Int, text: String) {
        val l = lines.getOrNull(i) ?: return
        lines = lines.toMutableList().also { it[i] = l.copy(text = text) }
        dirty = true
    }

    fun setTime(i: Int, t: Double) {
        val l = lines.getOrNull(i) ?: return
        change(lines.toMutableList().also { it[i] = l.copy(time = t.coerceIn(0.0, duration)) })
    }

    fun nudge(i: Int, d: Double) { lines.getOrNull(i)?.let { setTime(i, it.time + d) } }

    fun addLineAt(t: Double) = change(lines + LyricLine(t.coerceIn(0.0, duration), ""))

    /** Drops invented credits and cuts long lines (each keeps its start; the next line's start is its end). */
    fun tidyLines() {
        val out = lines.flatMapIndexed { i, l ->
            val end = lines.getOrNull(i + 1)?.time ?: duration
            mlabeler.core.format.Lyrics.split(l.time, end, mlabeler.core.format.Lyrics.clean(l.text)).map { (t, x) -> LyricLine(t, x) }
        }
        change(out)
    }

    fun remove(i: Int) { if (i in lines.indices) change(lines.filterIndexed { k, _ -> k != i }) }

    /** Replaces the lyrics with [text]: times kept for the first lines, the rest spread to the end. */
    fun replaceText(text: String) {
        val texts = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val old = lines
        val lastT = old.lastOrNull()?.time ?: 0.0
        val rest = texts.size - old.size
        val step = if (rest > 0) ((duration - lastT) / (rest + 1)).coerceAtLeast(1.0) else 0.0
        lines = texts.mapIndexed { i, t -> LyricLine(old.getOrNull(i)?.time ?: (lastT + step * (i - old.size + 1)).coerceAtMost(duration), t) }
        dirty = true
    }

    fun save() {
        val name = song ?: return
        val text = buildString {
            for (l in lines) {
                val cs = kotlin.math.round(l.time * 100).toLong()
                val m = cs / 6000; val s = (cs % 6000) / 100; val c = cs % 100
                append("[${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${c.toString().padStart(2, '0')}]${l.text}\n")
            }
        }
        runCatching { fs.write(lrcPath(name), text.encodeToByteArray()) }
            .onSuccess { dirty = false }
            .onFailure { app.message(it.message ?: it.toString(), error = true) }
    }

    private fun readLrc(path: String): List<LyricLine> {
        if (!fs.exists(path)) return emptyList()
        val text = runCatching { decodeGuess(fs.read(path), "UTF-8").first }.getOrNull() ?: return emptyList()
        val tag = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
        return text.lines().flatMap { raw ->
            val times = tag.findAll(raw).toList()
            if (times.isEmpty()) return@flatMap emptyList()
            val words = raw.substring(times.last().range.last + 1).trim()
            times.map { m -> LyricLine(m.groupValues[1].toInt() * 60 + m.groupValues[2].replace(':', '.').toDouble(), words) }
        }.sortedBy { it.time }
    }

    // ---------- toolkit ----------

    private fun work(what: String, block: suspend (mlabeler.app.toolkit.ToolkitClient) -> Unit) {
        if (busy != null) return
        workJob = scope.launch {
            busy = what
            progress = -1.0
            stageText = ""
            try {
                if (!app.toolkit.ensure()) throw ToolkitException(app.toolkit.statusText())
                block(app.toolkit.client())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                busy = null
                progress = -1.0
            }
        }
    }

    fun cancelWork() { workJob?.cancel(); busy = null; progress = -1.0 }

    private suspend fun upload(client: mlabeler.app.toolkit.ToolkitClient, name: String, a: Audio?): String {
        busy = uploadingT()
        val bytes = withContext(Dispatchers.Default) { if (a == null) fs.read(Paths.join(songsDir, name)) else Wav.encode16(a) }
        return client.upload(if (a == null) name else stem(name) + ".wav", bytes)
    }

    private fun itemOf(result: JsonObject): JsonObject {
        val item = result["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ToolkitException("no result")
        if ((item["ok"] as? JsonPrimitive)?.content == "false") throw ToolkitException((item["error"] as? JsonPrimitive)?.content ?: "failed")
        return item
    }

    /** Makes the backing track (the song without its voice); the voice is kept too, as an optional guide. */
    fun separate() {
        val name = song ?: return
        work(separatingT()) { client -> separateIn(client, name) }
    }

    private suspend fun separateIn(client: mlabeler.app.toolkit.ToolkitClient, name: String) {
        run {
            // a decoded WAV: the separator keeps the input's encoding and fails on MP3 and the like
            val id = upload(client, name, audio)
            busy = separatingT()
            val job = client.separate(id)
            val res = try { client.await(job) { p, stage -> progress = p; stageText = stage } } catch (e: kotlinx.coroutines.CancellationException) { client.cancel(job); throw e }
            val files = itemOf(res)["files"]?.jsonObject ?: throw ToolkitException("no files in the result")
            busy = fetchingT()
            progress = -1.0
            val voiceKey = files.keys.firstOrNull { it.contains("vocal", true) } ?: throw ToolkitException("no voice in the result: ${files.keys}")
            val musicKey = files.keys.firstOrNull { it != voiceKey } ?: throw ToolkitException("no backing track in the result: ${files.keys}")
            fs.mkdirs(partsDir)
            suspend fun fetch(key: String, part: String): Audio? {
                val bytes = client.download(files[key]!!.jsonPrimitive.content)
                return withContext(Dispatchers.Default) {
                    val a = if (Wav.isWav(bytes)) Wav.decode(bytes) else null
                    if (a != null) fs.write(partPath(name, part), Wav.encode16(a))
                    a
                }
            }
            val m = fetch(musicKey, "music")
            val v = fetch(voiceKey, "voice")
            if (song == name) { music = m; voice = v; analyseVoice(v) }
            app.message(separatedT())
        }
    }

    /** Recognises the words (on the song's voice when separated): phrases with approximate times become the lines. */
    fun recognise() {
        val name = song ?: return
        work(recognisingT()) { client ->
            // on the whole song the music confuses the recognizer: the voice is separated first
            if (voice == null) separateIn(client, name)
            val id = upload(client, name, voice)
            busy = recognisingT()
            val job = client.transcribe(id, language.trim().ifEmpty { null }, lines.joinToString(" ") { it.text }.take(400).ifBlank { null })
            val res = try { client.await(job) { p, stage -> progress = p; stageText = stage } } catch (e: kotlinx.coroutines.CancellationException) { client.cancel(job); throw e }
            val segs = itemOf(res)["data"]?.jsonObject?.get("transcription")?.jsonObject?.get("segments")?.jsonArray.orEmpty()
            // credits the recognizer invents over music are dropped, long phrases are cut into screen lines
            val got = segs.flatMap { s ->
                val o = s.jsonObject
                val t = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@flatMap emptyList()
                val e = o["end"]?.jsonPrimitive?.doubleOrNull ?: t
                val text = mlabeler.core.format.Lyrics.clean((o["text"] as? JsonPrimitive)?.content.orEmpty())
                mlabeler.core.format.Lyrics.split(t, e, text).map { (at, line) -> LyricLine(at, line) }
            }
            if (got.isEmpty()) { app.message(nothingHeard(), error = true); return@work }
            if (song == name) {
                lines = got.sortedBy { it.time }
                dirty = true
                save()
                app.message(recognisedT.format(got.size))
            }
        }
    }

    companion object {
        const val PITCH_HOP = 0.01
    }

    fun close() {
        if (recording) stopRecording()
        if (dirty) save()
        workJob?.cancel()
        stop()
        input.stop()
        output.release()
    }
}
