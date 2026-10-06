package mlabeler.app.recorder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import mlabeler.app.AudioOut
import mlabeler.app.Platform
import mlabeler.core.io.PlatformFs
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.toolkit.ToolkitException
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.ALL_AUDIO_EXTENSIONS
import mlabeler.core.io.Paths
import mlabeler.core.io.decodeGuess

/** One line of lyrics: when it starts (s) and what is sung. */
data class LyricLine(val time: Double, val text: String)

private val loadingT = L("Reading {0}…", "Читаю {0}…")
private val cantRead = L("Can't read {0}", "Не удалось прочитать {0}")
private val uploadingT = L("Sending the song to the toolkit…", "Передаю песню тулкиту…")
private val separatingT = L("Separating the voice and the music…", "Отделяю голос от музыки…")
private val recognisingT = L("Recognising the words…", "Распознаю слова…")
private val fetchingT = L("Taking the results…", "Забираю результаты…")
private val separatedT = L("Voice and music are separated", "Голос и музыка разделены")
private val recognisedT = L("{0} lines recognised; check them, the times are approximate", "Распознано строк: {0}; проверьте их, время примерное")
private val nothingHeard = L("No words were recognised", "Слов не распознано")
private val savedT = L("Lyrics saved: {0}", "Текст сохранён: {0}")

/**
 * Singing along a song: the music (whole, without the voice, or the voice only) and the lyrics shown line by line.
 * Everything heavy (separation, recognition) runs only on request, in the toolkit.
 */
class KaraokeState(val folder: String, private val app: AppState, private val scope: CoroutineScope) {
    private val fs = PlatformFs
    private val output = AudioOut()
    private val stemsDir = Paths.join(Paths.join(folder, ".mlabeler"), "karaoke")

    enum class Source { Song, Music, Voice }

    /** Audio files of the folder that can be sung along. */
    var songs by mutableStateOf<List<String>>(emptyList())
        private set
    var song by mutableStateOf<String?>(null)
        private set
    var audio by mutableStateOf<Audio?>(null)
        private set
    var music by mutableStateOf<Audio?>(null)
        private set
    var voice by mutableStateOf<Audio?>(null)
        private set
    var source by mutableStateOf(Source.Song)
        private set
    var lines by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    /** Unsaved changes of the lyrics. */
    var dirty by mutableStateOf(false)
        private set
    var position by mutableDoubleStateOf(0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Seconds before a line to start from when jumping to it. */
    var lead by mutableDoubleStateOf(3.0)
    /** What the toolkit (or reading) is busy with; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableDoubleStateOf(-1.0)
        private set
    /** Language for recognition; empty = detect. */
    var language by mutableStateOf("")

    private var playJob: Job? = null
    private var workJob: Job? = null

    init {
        songs = fs.list(folder).filter { Paths.ext(it).lowercase() in ALL_AUDIO_EXTENSIONS }.map { Paths.name(it) }.sorted()
        songs.firstOrNull()?.let { open(it) }
    }

    val duration: Double get() = audio?.duration ?: 0.0
    private fun stem(name: String) = Paths.stem(name)
    private fun lrcPath(name: String) = Paths.join(folder, stem(name) + ".lrc")
    private fun stemPath(name: String, part: String) = Paths.join(stemsDir, "${stem(name)}.$part.wav")

    private fun decode(path: String): Audio? = runCatching {
        val bytes = fs.read(path)
        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(path)
    }.getOrNull()

    fun open(name: String) {
        if (dirty) save()
        stop()
        song = name
        audio = null; music = null; voice = null
        source = Source.Song
        position = 0.0
        lines = readLrc(lrcPath(name))
        dirty = false
        workJob?.cancel()
        workJob = scope.launch {
            busy = loadingT.format(name)
            val path = Paths.join(folder, name)
            val a = withContext(Dispatchers.Default) { decode(path) }
            if (a == null) app.message(cantRead.format(name), error = true)
            audio = a
            music = withContext(Dispatchers.Default) { stemPath(name, "music").takeIf { fs.exists(it) }?.let { decode(it) } }
            voice = withContext(Dispatchers.Default) { stemPath(name, "voice").takeIf { fs.exists(it) }?.let { decode(it) } }
            busy = null
        }
    }

    private fun playing(): Audio? = when (source) {
        Source.Song -> audio
        Source.Music -> music ?: audio
        Source.Voice -> voice ?: audio
    }

    fun useSource(s: Source) {
        val was = playing
        source = s
        if (was) play(position)
    }

    // ---------- playback ----------

    fun play(from: Double = position) {
        val a = playing() ?: return
        val start = (from.coerceIn(0.0, a.duration) * a.sampleRate).toInt()
        if (a.samples.size - start < a.sampleRate / 20) return
        playJob?.cancel()
        runCatching { output.play(a, start, a.samples.size, false) }.onFailure { return }
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
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        output.stop()
        playing = false
    }

    fun toggle() = if (playing) stop() else play()

    fun seek(t: Double) {
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

    /** Starts [lead] seconds before the first words. */
    fun toFirstWords() {
        val t = lines.firstOrNull()?.time ?: return seek(0.0)
        seek((t - lead).coerceAtLeast(0.0))
        if (!playing) play()
    }

    fun toLine(i: Int, withLead: Boolean = true) {
        val l = lines.getOrNull(i) ?: return
        seek((l.time - if (withLead) minOf(lead, 1.0) else 0.0).coerceAtLeast(0.0))
    }

    fun stepLine(d: Int) {
        val cur = current
        val target = if (d < 0 && cur >= 0 && position - lines[cur].time > 1.5) cur else cur + d
        toLine(target.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
    }

    // ---------- editing ----------

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

    /** Replaces the lyrics with [text]: one line per line, times kept for the first lines, the rest spread to the end. */
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
            .onSuccess { dirty = false; app.message(savedT.format(Paths.name(lrcPath(name)))) }
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
        // the song as it is (any format the toolkit reads), or a WAV of the decoded part
        val bytes = withContext(Dispatchers.Default) { if (a == null) fs.read(Paths.join(folder, name)) else Wav.encode16(a) }
        return client.upload(if (a == null) name else stem(name) + ".wav", bytes)
    }

    private fun itemOf(result: JsonObject): JsonObject {
        val item = result["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ToolkitException("no result")
        if ((item["ok"] as? JsonPrimitive)?.content == "false") throw ToolkitException((item["error"] as? JsonPrimitive)?.content ?: "failed")
        return item
    }

    /** Separates the voice from the music; both are kept in .mlabeler/karaoke for the next time. */
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
            val musicKey = files.keys.firstOrNull { it != voiceKey }
            fs.mkdirs(stemsDir)
            suspend fun fetch(key: String, part: String): Audio? {
                val bytes = client.download(files[key]!!.jsonPrimitive.content)
                return withContext(Dispatchers.Default) {
                    val a = if (Wav.isWav(bytes)) Wav.decode(bytes) else null
                    // stored as WAV so the next time needs no toolkit
                    if (a != null) fs.write(stemPath(name, part), Wav.encode16(a))
                    a
                }
            }
            voice = fetch(voiceKey, "voice")
            music = musicKey?.let { fetch(it, "music") }
            if (song == name && music != null) useSource(Source.Music)
            app.message(separatedT())
        }
    }

    /** Recognises the words (on the voice when it is separated): phrases with approximate times become the lines. */
    fun recognise() {
        val name = song ?: return
        work(recognisingT()) { client ->
            val id = upload(client, name, voice)
            busy = recognisingT()
            val job = client.transcribe(id, language.trim().ifEmpty { null })
            val res = try { client.await(job) { p, _ -> progress = p } } catch (e: kotlinx.coroutines.CancellationException) { client.cancel(job); throw e }
            val item = itemOf(res)
            val segs = item["data"]?.jsonObject?.get("transcription")?.jsonObject?.get("segments")?.jsonArray.orEmpty()
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
        if (dirty) save()
        workJob?.cancel()
        stop()
        output.release()
    }
}
