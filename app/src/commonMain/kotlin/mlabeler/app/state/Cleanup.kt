package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mlabeler.app.i18n.L
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.audio.WavEdit
import mlabeler.core.dsp.Repair
import mlabeler.core.io.Paths

@Serializable
data class CleanSettings(
    /** 1..10: higher finds weaker clicks. */
    val clickSensitivity: Float = 5f,
    /** Longer events are sound, not clicks. */
    val clickMaxMs: Float = 3f,
    val noiseReductionDb: Float = 12f,
    val noiseSensitivityDb: Float = 6f,
    val noiseSmoothing: Int = 3,
)

/**
 * Cleaning the open WAV recording: clicks repaired by interpolation (only their samples change), a selected short
 * part repaired the same way, or noise lowered with a spectral gate. The file keeps its sample rate, bit depth,
 * channels and every other chunk; untouched samples stay byte-for-byte. The first version of the file is kept in
 * .mlabeler/backup.
 */
class Cleanup(private val ed: EditorState, private val app: AppState) {
    /** Clicks found by the last search, in samples, shown on the timeline. */
    var found by mutableStateOf<List<IntRange>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    /** Noise profile per channel, from a part with noise only. */
    var noiseProfile by mutableStateOf<List<DoubleArray>?>(null)
        private set
    var profileFrom by mutableStateOf<Pair<Double, Double>?>(null)
        private set
    /** Changes of the recording are undone like label changes (Ctrl+Z). */
    val canUndo: Boolean get() = ed.canUndoAudio

    private fun readEdit(): WavEdit? {
        val it = ed.item ?: return null
        val bytes = ed.workspace.fs.read(it.audioPath)
        if (!Wav.isWav(bytes)) throw IllegalStateException(onlyWav())
        return WavEdit(bytes)
    }

    fun clearFound() { found = emptyList() }

    /** Finds clicks in [range] (seconds) or the whole file. */
    fun findClicks(range: Pair<Double, Double>?) = run("find") {
        val a = ed.audio ?: return@run
        val s = app.settings.clean
        val (from, to) = samples(range, a)
        found = withContext(Dispatchers.Default) { Repair.findClicks(a.samples, a.sampleRate, s.clickSensitivity, s.clickMaxMs, from, to) }
        app.message(if (found.isEmpty()) noClicks() else clicksFound.format(found.size))
    }

    /** Repairs the clicks found before. */
    fun repairFound() = run("repair") {
        val list = found
        if (list.isEmpty()) return@run
        val n = modify { w, ch, x -> var c = 0; for (r in list) c += patch(w, ch, x, r.first, r.last + 1); c }
        found = emptyList()
        app.message(repaired.format(list.size, n))
    }

    /** Repairs a short selected part by interpolation from both sides. */
    fun repairRange(range: Pair<Double, Double>) = run("repair") {
        val a = ed.audio ?: return@run
        val (from, to) = samples(range, a)
        if (to - from > a.sampleRate / 10) { app.message(tooLong(), error = true); return@run }
        val n = modify { w, ch, x -> patch(w, ch, x, from, to) }
        app.message(repairedPart.format(n))
    }

    /** Replaces [range] with silence; 5 ms fades at both ends so no click is left. */
    fun silence(range: Pair<Double, Double>) = run("silence") {
        val n = modify { w, ch, _ ->
            val (from, to) = frames(range, w)
            val fade = minOf((w.sampleRate * 0.005).toInt(), (to - from) / 2).coerceAtLeast(1)
            var c = 0
            for (i in from until to) {
                val edge = minOf(i - from, to - 1 - i)
                val v = if (edge < fade) w.get(i, ch) * (1f - (edge + 1).toFloat() / (fade + 1)) else 0f
                if (w.set(i, ch, v)) c++
            }
            c
        }
        if (n > 0) app.message(silenced.format(formatSeconds(range.second - range.first)))
    }

    private fun formatSeconds(s: Double) = "${kotlin.math.round(s * 1000).toInt()} ms"

    fun takeNoiseProfile(range: Pair<Double, Double>) = run("profile") {
        val w = withContext(Dispatchers.Default) { readEdit() } ?: return@run
        val from = (range.first * w.sampleRate).toInt().coerceIn(0, w.frames)
        val to = (range.second * w.sampleRate).toInt().coerceIn(from, w.frames)
        if (to - from < w.sampleRate / 20) { app.message(profileShort(), error = true); return@run }
        noiseProfile = withContext(Dispatchers.Default) { (0 until w.channels).map { Repair.noiseProfile(w.channel(it, from, to)) } }
        profileFrom = range
        app.message(profileTaken())
    }

    fun reduceNoise(range: Pair<Double, Double>?) = run("noise") {
        val prof = noiseProfile ?: return@run
        val s = app.settings.clean
        val n = modify { w, ch, _ ->
            val (from, to) = frames(range, w)
            val src = w.channel(ch, from, to)
            val out = Repair.reduceNoise(src, prof[ch.coerceAtMost(prof.size - 1)], s.noiseReductionDb, s.noiseSensitivityDb, s.noiseSmoothing)
            var c = 0
            for (i in out.indices) if (w.set(from + i, ch, out[i])) c++
            c
        }
        app.message(noiseDone.format(n))
    }

    /** Plays [range] as it would sound after noise reduction, without changing anything. */
    fun previewNoise(range: Pair<Double, Double>) = run("preview") {
        val prof = noiseProfile ?: return@run
        val a = ed.audio ?: return@run
        val s = app.settings.clean
        val (from, to) = samples(range, a)
        val out = withContext(Dispatchers.Default) {
            Repair.reduceNoise(a.samples.copyOfRange(from, to), prof[0], s.noiseReductionDb, s.noiseSensitivityDb, s.noiseSmoothing)
        }
        ed.playBuffer(Audio(a.sampleRate, out))
    }

    fun undo() = run("undo") {
        if (ed.undoAudio()) app.message(undone())
    }

    /**
     * Removes [range] from the recording: the file gets shorter, labels after it move back, labels inside go.
     * The join is crossfaded over 5 ms.
     */
    fun cut(range: Pair<Double, Double>) = run("cut") {
        val it = ed.item ?: return@run
        if (ed.mode == Mode.Oto) { app.message(cutOto(), error = true); return@run }
        val w = withContext(Dispatchers.Default) { readEdit() } ?: return@run
        val (from, to) = frames(range, w)
        if (to - from < 1) return@run
        if (to - from >= w.frames) { app.message(cutAll(), error = true); return@run }
        val before = w.bytes.copyOf()
        val after = withContext(Dispatchers.Default) { w.withoutFrames(from, to, (w.sampleRate * 0.005).toInt()) }
        val newDuration = (w.frames - (to - from)).toDouble() / w.sampleRate
        val labels = ed.doc?.let { d ->
            mlabeler.core.edit.Edits.fitToDuration(mlabeler.core.edit.Edits.removeTime(d, from.toDouble() / w.sampleRate, to.toDouble() / w.sampleRate), newDuration)
        }
        keepOriginal(it.audioPath, before)
        ed.range = null
        ed.selection = Selection.None
        ed.applyAudioEdit(it.audioPath, before, after, labels)
        app.message(cutDone.format(formatSeconds((to - from).toDouble() / w.sampleRate)))
    }

    private suspend fun keepOriginal(path: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        val b = backupPath(path)
        if (!ed.workspace.fs.exists(b)) {
            ed.workspace.fs.mkdirs(Paths.parent(b))
            ed.workspace.fs.write(b, bytes)
        }
    }

    /** Puts back the file as it was before the first cleaning. */
    fun restoreOriginal() = run("restore") {
        val it = ed.item ?: return@run
        val b = backupPath(it.audioPath)
        if (!ed.workspace.fs.exists(b)) { app.message(noOriginal(), error = true); return@run }
        val now = withContext(Dispatchers.Default) { ed.workspace.fs.read(it.audioPath) }
        val orig = withContext(Dispatchers.Default) { ed.workspace.fs.read(b) }
        ed.applyAudioEdit(it.audioPath, now, orig, null)
        app.message(restored())
    }

    fun hasOriginal(): Boolean = ed.item?.let { ed.workspace.fs.exists(backupPath(it.audioPath)) } == true

    private fun backupPath(audioPath: String) =
        Paths.join(Paths.join(ed.workspace.metaDir, "backup"), Paths.stem(audioPath) + ".original." + Paths.ext(audioPath))

    private fun samples(range: Pair<Double, Double>?, a: Audio): Pair<Int, Int> {
        if (range == null) return 0 to a.samples.size
        val from = (range.first * a.sampleRate).toInt().coerceIn(0, a.samples.size)
        return from to (range.second * a.sampleRate).toInt().coerceIn(from, a.samples.size)
    }

    private fun frames(range: Pair<Double, Double>?, w: WavEdit): Pair<Int, Int> {
        if (range == null) return 0 to w.frames
        val from = (range.first * w.sampleRate).toInt().coerceIn(0, w.frames)
        return from to (range.second * w.sampleRate).toInt().coerceIn(from, w.frames)
    }

    /** Interpolates [from, to) of channel [ch]; returns how many stored samples changed. */
    private fun patch(w: WavEdit, ch: Int, x: FloatArray, from: Int, to: Int): Int {
        val v = Repair.interpolate(x, from, to)
        var c = 0
        for (i in v.indices) if (w.set(from + i, ch, v[i])) c++
        return c
    }

    /** Reads the file, runs [f] per channel, writes the file if something changed (keeping the original once). */
    private suspend fun modify(f: (WavEdit, Int, FloatArray) -> Int): Int {
        val it = ed.item ?: return 0
        val w = withContext(Dispatchers.Default) { readEdit() } ?: return 0
        val before = w.bytes.copyOf()
        val changed = withContext(Dispatchers.Default) { (0 until w.channels).sumOf { ch -> f(w, ch, w.channel(ch)) } }
        if (changed == 0) return 0
        keepOriginal(it.audioPath, before)
        ed.applyAudioEdit(it.audioPath, before, w.bytes, null)
        return changed
    }

    private fun run(what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        ed.workScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val onlyWav = L("Only WAV recordings can be cleaned", "Чистить можно только WAV-записи")
        val noClicks = L("No clicks found", "Щелчков не найдено")
        val clicksFound = L("Clicks found: {0}. They are marked in red above the waveform.", "Найдено щелчков: {0}. Они отмечены красным над волной.")
        val repaired = L("Repaired {0} clicks ({1} samples changed, the rest is untouched)", "Исправлено щелчков: {0} (изменено сэмплов: {1}, остальное не тронуто)")
        val repairedPart = L("Part repaired ({0} samples changed)", "Кусок исправлен (изменено сэмплов: {0})")
        val tooLong = L("Select a short part (up to 100 ms) to repair", "Для починки выделите короткий кусок (до 100 мс)")
        val profileShort = L("Select at least 50 ms of noise only", "Выделите хотя бы 50 мс, где только шум")
        val profileTaken = L("Noise profile taken", "Профиль шума взят")
        val noiseDone = L("Noise lowered ({0} samples changed)", "Шум снижен (изменено сэмплов: {0})")
        val cutDone = L("Cut out of the recording: {0}. Ctrl+Z puts it back.", "Вырезано из записи: {0}. Ctrl+Z вернёт.")
        val cutOto = L("Cutting would move every oto marker after the cut; it works with labels only", "Вырезание сдвинуло бы все метки oto после него; оно работает только с разметкой")
        val cutAll = L("The whole recording can't be cut out", "Нельзя вырезать всю запись")
        val silenced = L("Silenced in the recording: {0}. Ctrl+Z puts it back.", "Заглушено в записи: {0}. Ctrl+Z вернёт.")
        val undone = L("The recording is back as it was before the last change", "Запись возвращена как до последнего изменения")
        val restored = L("The original recording is back", "Исходная запись возвращена")
        val noOriginal = L("This recording wasn't cleaned here", "Эту запись здесь не чистили")
    }
}
