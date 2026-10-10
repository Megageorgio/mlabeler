package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier

private val titleT = L("Cut out phonemes", "Нарезка фонем")
private val aboutT = L(
    "Every place where these phonemes (or runs of them) are in the labels of the folder is cut out of its recording, to listen to them one after another and find wrong ones. Separate them with commas; a run with spaces: \"h, s, k t, pau a\".",
    "Все места, где в разметке папки стоят эти фонемы (или их сочетания), вырезаются из записей — чтобы прослушать их подряд и найти неправильные. Через запятую; сочетание через пробел: «h, s, k t, pau a».",
)
private val modeT = L("How to save", "Как сохранить")
private val separateT = L("Each segment in its own file", "Каждый сегмент в отдельный файл")
private val byPhonemeT = L("One file per phoneme", "Один файл на фонему")
private val byWavT = L("One file per phoneme and recording", "Один файл на фонему и запись")
private val intoT = L("Into the folder", "В папку")
private val runT = L("Cut", "Нарезать")
private val doneT = L("{0} segments cut into {1}", "Нарезано сегментов: {0}, папка {1}")
private val noneT = L("These phonemes are not in the labels", "Этих фонем нет в разметке")
private val workingT = L("Cutting… {0} of {1} files", "Нарезка… файлов: {0} из {1}")

/** A piece of a recording: where it comes from, its sample rate and sound. */
private class Piece(val source: String, val rate: Int, val sound: FloatArray)

@Composable
fun PhonemeCutsDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) }
    // next to the folder, not inside it: the pieces must not turn up in its file list
    var folder by remember { mutableStateOf(Paths.join(Paths.parent(ed.workspace.root), Paths.name(ed.workspace.root) + "_phonemes")) }
    var busy by remember { mutableStateOf<String?>(null) }
    fun close() { app.showPhonemeCuts = false; ed.requestFocus() }
    Overlay({ if (busy == null) close() }, 560) {
        DialogContent(footer = {
            Btn(S.cancel(), enabled = busy == null) { close() }
            Btn(busy ?: runT(), primary = true, enabled = busy == null && text.isNotBlank() && folder.isNotBlank()) {
                busy = workingT.format(0, ed.items.size)
                if (ed.dirty) ed.save(quiet = true)
                scope.launch {
                    try {
                        val n = withContext(Dispatchers.Default) { cut(ed, text, mode, folder.trim()) { k -> busy = workingT.format(k, ed.items.size) } }
                        app.message(if (n == 0) noneT() else doneT.format(n, folder.trim()))
                        if (n > 0) {
                            close()
                            if (!mlabeler.app.Platform.isMobile) mlabeler.app.Platform.openInFileManager(folder.trim())
                        }
                    } catch (e: Exception) {
                        app.message(e.message ?: e.toString(), error = true)
                    } finally { busy = null }
                }
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Field(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "h, s, k t")
            SectionTitle(modeT())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(separateT(), mode == 0) { mode = 0 }
                Chip(byPhonemeT(), mode == 1) { mode = 1 }
                Chip(byWavT(), mode == 2) { mode = 2 }
            }
            SectionTitle(intoT())
            Field(folder, { folder = it }, Modifier.fillMaxWidth())
        }
    }
}

/** Cuts every run of the wanted phonemes out of the folder's recordings; returns the number of pieces. */
private fun cut(ed: EditorState, wanted: String, mode: Int, folder: String, progress: (Int) -> Unit): Int {
    val runs = wanted.split(',').map { it.trim().split(Regex("\\s+")).filter { p -> p.isNotEmpty() } }.filter { it.isNotEmpty() }
    if (runs.isEmpty()) return 0
    val fs = ed.workspace.fs
    val out = folder
    fun safe(s: String) = s.map { ch -> if (ch.isLetterOrDigit() || ch in "-_'") ch else '_' }.joinToString("")
    val found = runs.associateWith { mutableListOf<Piece>() }
    for ((k, item) in ed.items.withIndex()) {
        progress(k)
        if (item.labelPath == null) continue
        val audio: Audio = runCatching { Wav.decode(fs.read(item.audioPath)) }.getOrNull() ?: continue
        val doc = runCatching { ed.workspace.readLabels(item, audio.duration) }.getOrNull() ?: continue
        val tier = doc.tiers.getOrNull(doc.phonemeTierIndex()) as? IntervalTier ?: continue
        for (run in runs) {
            var i = 0
            while (i + run.size <= tier.size) {
                if ((run.indices).all { j -> tier.texts[i + j] == run[j] }) {
                    val a = (tier.startOf(i) * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
                    val b = (tier.endOf(i + run.size - 1) * audio.sampleRate).toInt().coerceIn(a, audio.samples.size)
                    if (b > a) found.getValue(run) += Piece(Paths.stem(item.audioPath), audio.sampleRate, audio.samples.copyOfRange(a, b))
                    i += run.size
                } else i++
            }
        }
    }
    progress(ed.items.size)
    // pieces joined with a short pause, all at the rate of the first one
    fun join(parts: List<Piece>): Audio {
        val rate = parts.first().rate
        val sounds = parts.map { pc -> if (pc.rate == rate) pc.sound else mlabeler.core.dsp.Stretch.resample(pc.sound, pc.rate.toDouble() / rate) }
        val gap = (rate * 0.15).toInt()
        val all = FloatArray(sounds.sumOf { it.size + gap })
        var p = 0
        for (x in sounds) { x.copyInto(all, p); p += x.size + gap }
        return Audio(rate, all)
    }
    var count = 0
    for ((run, pieces) in found) {
        if (pieces.isEmpty()) continue
        count += pieces.size
        val name = safe(run.joinToString("_"))
        fs.mkdirs(out)
        when (mode) {
            // a file per piece, numbered in the order they are in the folder, with the recording in the name
            0 -> {
                val dir = Paths.join(out, name)
                fs.mkdirs(dir)
                pieces.forEachIndexed { n, pc ->
                    fs.write(Paths.join(dir, "${(n + 1).toString().padStart(4, '0')}_${safe(pc.source)}.wav"), Wav.encode16(Audio(pc.rate, pc.sound)))
                }
            }
            1 -> fs.write(Paths.join(out, "$name.wav"), Wav.encode16(join(pieces)))
            else -> {
                val dir = Paths.join(out, name)
                fs.mkdirs(dir)
                for ((src, list) in pieces.groupBy { it.source }) fs.write(Paths.join(dir, "${safe(src)}.wav"), Wav.encode16(join(list)))
            }
        }
    }
    return count
}
