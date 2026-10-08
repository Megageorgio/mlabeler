package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.core.audio.WavEdit
import mlabeler.core.ds.Dataset
import mlabeler.core.ds.SegmentOptions
import mlabeler.core.edit.Edits
import mlabeler.core.format.HtkLab
import mlabeler.core.format.LabelFormat
import mlabeler.core.format.TextGridFormat
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

private val titleT = L("Cut into pieces", "Нарезка на куски")
private val aboutT = L("Pieces are the named parts of the \"segments\" lane. Fill it automatically, then move its boundaries, rename or remove pieces as with any labels. Each named piece is saved as its own WAV (and labels) — the recording itself stays as it is.",
    "Куски — это подписанные части слоя «segments». Заполните его автоматически, потом двигайте границы, переименовывайте и убирайте куски как обычную разметку. Каждый подписанный кусок сохраняется отдельным WAV (и разметкой); сама запись не меняется.")
private val bySilenceT = L("Pieces by silence", "Куски по тишине")
private val byPausesT = L("Pieces by pauses of the labels", "Куски по паузам разметки")
private val fromRangeT = L("The selection becomes a piece", "Выделенное — в кусок")
private val countT = L("Named pieces: {0}", "Подписанных кусков: {0}")
private val noneT = L("No \"segments\" lane yet", "Слоя «segments» пока нет")
private val folderT = L("Save to (folder next to the recording; a \"/\" in a piece's name makes a subfolder, e.g. power/001)",
    "Куда сохранить (папка рядом с записью; «/» в имени куска делает подпапку, например power/001)")
private val withLabelsT = L("With labels", "С разметкой")
private val saveT = L("Save the pieces", "Сохранить куски")
private val savedT = L("Saved pieces: {0} in {1}", "Сохранено кусков: {0} в {1}")
private val existsT = L("{0} already exists; nothing was written", "{0} уже есть; ничего не записано")

/** Marking pieces of a long recording on a "segments" lane and saving each as its own file. */
@Composable
fun SegmentDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val item = ed.item ?: return
    val doc = ed.doc
    val duration = ed.duration
    val segIndex = doc?.tiers?.indexOfFirst { it is IntervalTier && it.name.lowercase() in Dataset.SEGMENT_TIER_NAMES } ?: -1
    val seg = doc?.tiers?.getOrNull(segIndex) as? IntervalTier
    val named = seg?.let { t -> (0 until t.size).filter { t.texts[it].isNotBlank() } } ?: emptyList()
    var folder by remember { mutableStateOf(Paths.stem(item.audioPath) + "_parts") }
    var withLabels by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun close() { app.showSegments = false; ed.requestFocus() }

    /** Puts [parts] on the segments lane (made when missing), named <file>_01, _02 … */
    fun fill(parts: List<Pair<Double, Double>>) {
        val stem = Paths.stem(item.audioPath)
        val lane = IntervalTier.fromIntervals("segments", parts.mapIndexed { k, (a, b) -> Triple(a, b, "${stem}_${(k + 1).toString().padStart(2, '0')}") }, duration)
        ed.updateDoc { d ->
            val k = d.tiers.indexOfFirst { it is IntervalTier && it.name.lowercase() in Dataset.SEGMENT_TIER_NAMES }
            if (k >= 0) d.replace(k, lane) else LabelDoc(listOf(lane) + d.tiers)
        }
        ed.activeTier = 0
    }

    Overlay({ close() }, 560) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(bySilenceT()) {
                    val a = ed.audio ?: return@Btn
                    fill(mlabeler.core.ds.Silence.sounding(a.samples, a.sampleRate))
                }
                val ph = doc?.let { d -> d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier }
                if (ph != null && ph.texts.count { it.isNotBlank() } > 2) Btn(byPausesT()) {
                    val d = doc.copy(tiers = doc.tiers.filterNot { it is IntervalTier && it.name.lowercase() in Dataset.SEGMENT_TIER_NAMES })
                    fill(Dataset.cuts(d, duration, SegmentOptions(), mlabeler.app.state.Dictionaries.byName(ed.workspace.state.dictionary)))
                }
                Btn(fromRangeT(), enabled = ed.range != null) {
                    val (a, b) = ed.range ?: return@Btn
                    val old = seg?.let { t -> (0 until t.size).filter { t.texts[it].isNotBlank() }.map { Triple(t.startOf(it), t.endOf(it), t.texts[it]) } } ?: emptyList()
                    // the new piece replaces what it overlaps
                    val kept = old.filter { it.second <= a || it.first >= b }
                    val parts = (kept.map { it.first to it.second } + (a to b)).sortedBy { it.first }
                    fill(parts)
                }
            }
            Text(if (seg == null) noneT() else countT.format(named.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text(folderT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Field(folder, { folder = it }, Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(withLabels, { withLabels = it })
                Text(withLabelsT(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { close() }
                Btn(saveT(), primary = true, enabled = seg != null && named.isNotEmpty() && folder.isNotBlank()) {
                    val t = seg ?: return@Btn
                    val d = doc ?: return@Btn
                    val fs = ed.workspace.fs
                    val base = Paths.join(Paths.parent(item.audioPath), folder.trim().trim('/', '\\'))
                    val fmt = ed.workspace.state.defaultFormat.takeIf { it == LabelFormat.TextGrid } ?: LabelFormat.Lab
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.Default) {
                                val bytes = fs.read(item.audioPath)
                                if (!mlabeler.core.audio.Wav.isWav(bytes)) error(S.unsupportedAudio())
                                val w = WavEdit(bytes)
                                val labels = d.copy(tiers = d.tiers.filterIndexed { k, _ -> k != segIndex })
                                val targets = named.map { i -> Paths.join(base, t.texts[i].trim().replace('\\', '/') + ".wav") }
                                targets.firstOrNull { fs.exists(it) }?.let { error(existsT.format(it)) }
                                for ((n, i) in named.withIndex()) {
                                    val a = t.startOf(i)
                                    val b = t.endOf(i)
                                    val path = targets[n]
                                    fs.mkdirs(Paths.parent(path))
                                    fs.write(path, w.slice((a * w.sampleRate).toInt(), (b * w.sampleRate).toInt()))
                                    if (withLabels) {
                                        val part = Edits.crop(labels, a, b, duration)
                                        val text = if (fmt == LabelFormat.TextGrid) TextGridFormat.write(part, b - a) else HtkLab.write(part)
                                        fs.write(Paths.withExt(path, fmt.extension), text.encodeToByteArray())
                                    }
                                }
                                named.size
                            }
                        }
                        result.onSuccess { n -> app.message(savedT.format(n, base)); ed.rescan(); close() }
                            .onFailure { app.message(it.message ?: it.toString(), error = true) }
                    }
                }
            }
        }
    }
}
