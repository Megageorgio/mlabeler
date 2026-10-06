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

private val loadingT = L("Reading {0}…", "Читаю {0}…")
private val cantRead = L("Can't read {0}", "Не удалось прочитать {0}")
private val uploadingT = L("Sending the song to the toolkit…", "Передаю песню тулкиту…")
private val separatingT = L("Separating the voice and the music…", "Отделяю голос от музыки…")
private val recognisingT = L("Recognising the words…", "Распознаю слова…")
private val fetchingT = L("Taking the results…", "Забираю результаты…")
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
    /** Language for recognition; empty = detect. */
    var language by mutableStateOf("")

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

    init {
        fs.mkdirs(songsDir)
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
    private fun backing(): Audio? {
        val m = music ?: return audio
        val v = voice
        val g = guideLevel
        if (v == null || g <= 0.001f || v.sampleRate != m.sampleRate) return m
        val out = FloatArray(m.samples.size) { i -> (m.samples[i] + g * v.samples.getOrElse(i) { 0f }).coerceIn(-1f, 1f) }
        return Audio(m.sampleRate, out)
    }

    // ---------- playback ----------

    private var playing0: Audio? = null

    fun play(from: Double = position) {
        val a = backing() ?: return
        val start = (from.coerceIn(0.0, a.duration) * a.sampleRate).toInt()
        if (a.samples.size - start < a.sampleRate / 20) return
        playJob?.cancel()
        runCatching { output.play(a, start, a.samples.size, false) }.onFailure { return }
        playing0 = a
        position = from
        playing = true
        playJob = scope.launch {
            delay(30)
            while (isActive && output.isPlaying) {
                val p = output.position()
                if (p >= 0) position = p.toDouble() / a.sampleRate
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
    fun record() {
        if (recording || audio == null) return
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
                        var p = 0f
                        for (v in chunk) { val a = kotlin.math.abs(v); if (a > p) p = a }
                        level = p
                    }
                } catch (e: Exception) {
                    app.message(micError.format(e.message ?: e.toString()), error = true)
                    return@launch
                }
                recStart = position
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
        val parts = ArrayList<FloatArray>()
        while (true) parts += chunks.tryReceive().getOrNull() ?: break
        val data = FloatArray(parts.sumOf { it.size }).also { out -> var p = 0; for (c in parts) { c.copyInto(out, p); p += c.size } }
        if (data.size < recRate / 2) return
        val name = takeName.trim().removeSuffix(".wav").ifEmpty { song?.let { nextTakeName(it) } ?: "take" }
        // the words sung in the recorded part go next to the take (autolabel can use them)
        val words = lines.filterIndexed { i, l ->
            val until = lines.getOrNull(i + 1)?.time ?: duration
            until > recStart + 0.3 && l.time < end - 0.3
        }.joinToString("\n") { it.text }.trim()
        val take = Audio(recRate, data)
        scope.launch {
            withContext(Dispatchers.Default) {
                val path = Paths.join(folder, "$name.wav")
                if (fs.exists(path)) runCatching { fs.copy(path, Paths.join(Paths.join(Paths.join(folder, ".mlabeler"), "takes"), "$name.${mlabeler.core.io.Workspace.timestamp()}.wav")) }
                fs.write(path, Wav.encode16(take))
                if (words.isNotEmpty()) fs.write(Paths.join(folder, "$name.txt"), (words + "\n").encodeToByteArray())
            }
            lastTake = take
            lastTakeName = name
            app.message(takeSaved.format("$name.wav", Paths.name(folder)))
            song?.let { takeName = nextTakeName(it) }
        }
    }

    fun playLastTake() {
        val t = lastTake ?: return
        stop()
        runCatching { output.play(t, 0, t.samples.size, false) }
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
        work(separatingT()) { client ->
            val id = upload(client, name, null)
            busy = separatingT()
            val job = client.separate(id)
            val res = try { client.await(job) { p, _ -> progress = p } } catch (e: kotlinx.coroutines.CancellationException) { client.cancel(job); throw e }
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
            if (song == name) { music = m; voice = v }
            app.message(separatedT())
        }
    }

    /** Recognises the words (on the song's voice when separated): phrases with approximate times become the lines. */
    fun recognise() {
        val name = song ?: return
        work(recognisingT()) { client ->
            val id = upload(client, name, voice)
            busy = recognisingT()
            val job = client.transcribe(id, language.trim().ifEmpty { null })
            val res = try { client.await(job) { p, _ -> progress = p } } catch (e: kotlinx.coroutines.CancellationException) { client.cancel(job); throw e }
            val segs = itemOf(res)["data"]?.jsonObject?.get("transcription")?.jsonObject?.get("segments")?.jsonArray.orEmpty()
            val got = segs.mapNotNull { s ->
                val o = s.jsonObject
                val t = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val text = (o["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (text.isEmpty()) null else LyricLine(t, text)
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

    fun close() {
        if (recording) stopRecording()
        if (dirty) save()
        workJob?.cancel()
        stop()
        input.stop()
        output.release()
    }
}
