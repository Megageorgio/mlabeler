package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.Dictionaries
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.audio.Wav
import mlabeler.core.ds.Dataset
import mlabeler.core.ds.DatasetOptions
import mlabeler.core.ds.NoteSource
import mlabeler.core.ds.SegmentOptions
import mlabeler.core.format.DsCsv
import mlabeler.core.io.Paths
import mlabeler.core.model.NoteTier

private val title = L("Export a DiffSinger dataset", "Экспорт датасета DiffSinger")
private val hint = L(
    "Recordings are cut into segments at pauses and saved as wavs/ + transcriptions.csv with phonemes, their grouping into notes (ph_num) and notes. To choose the segments yourself, add a tier named \"segments\" and name the parts to keep.",
    "Записи разрезаются на сегменты по паузам и сохраняются как wavs/ + transcriptions.csv: фонемы, их группировка по нотам (ph_num) и ноты. Чтобы задать сегменты вручную, добавьте слой «segments» и подпишите нужные части.",
)
private val whereT = L("Folder", "Папка")
private val pickT = L("Choose…", "Выбрать…")
private val whichT = L("Recordings", "Записи")
private val allLabeled = L("All with labels", "Все с разметкой")
private val onlyDone = L("Only done", "Только готовые")
private val piecesT = L("Segments", "Сегменты")
private val maxLen = L("Longest segment", "Самый длинный сегмент")
private val minLen = L("Shortest segment", "Самый короткий сегмент")
private val pad = L("Silence at the edges", "Тишина по краям")
private val groupsT = L("Grouping into notes (ph_num)", "Группировка по нотам (ph_num)")
private val keepWords = L("From the words tier when it fits", "Из слоя слов, если подходит")
private val byDict = L("Always by the dictionary", "Всегда по словарю")
private val dictT = L("Phoneme dictionary", "Словарь фонем")
private val dictHint = L("Vowels start a note, consonants go with the note before them.",
    "Гласные начинают ноту, согласные идут с нотой перед ними.")
private val notesT = L("Notes", "Ноты")
private val notesNone = L("None", "Без нот")
private val notesLabels = L("From the labels (else by pitch)", "Из разметки (иначе по высоте)")
private val notesPitch = L("By pitch, one per group", "По высоте, по одной на группу")
private val rateT = L("Sample rate", "Частота")
private val asIs = L("As recorded", "Как в записи")
private val exportBtn = L("Export", "Экспортировать")
private val working = L("Exporting {0} of {1}…", "Экспорт {0} из {1}…")
private val doneMsg = L("Exported {0} segments ({1}) from {2} recordings into {3}", "Экспортировано сегментов: {0} ({1}) из записей: {2} в {3}")
private val skippedMsg = L("Skipped without labels: {0}", "Пропущено без разметки: {0}")
private val failedMsg = L("Export failed: {0}", "Экспорт не удался: {0}")
private val stopBtn = L("Stop", "Остановить")

@Composable
fun DsExportDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val ws = ed.workspace
    fun close() { app.showDsExport = false; ed.requestFocus() }
    var folder by remember { mutableStateOf(ws.state.exportFolder.ifBlank { Paths.join(Paths.parent(ws.root), Paths.name(ws.root) + "_diffsinger") }) }
    var done by remember { mutableStateOf(false) }
    var maxS by remember { mutableFloatStateOf(15f) }
    var minS by remember { mutableFloatStateOf(1f) }
    var padS by remember { mutableFloatStateOf(0.3f) }
    var keep by remember { mutableStateOf(true) }
    var dict by remember { mutableStateOf(ws.state.dictionary) }
    var notes by remember { mutableStateOf(NoteSource.Pitch) }
    var rate by remember { mutableIntStateOf(44100) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run() {
        if (ed.labelsDirty) ed.save(quiet = true)
        ws.updateState { it.copy(exportFolder = folder, dictionary = dict) }
        val items = ed.items.filter { it.labelPath != null && (!done || ws.itemState(it.id).marks.done) }
        val skipped = ed.items.count { it.labelPath == null }
        val o = DatasetOptions(SegmentOptions(maxS.toDouble(), minS.toDouble(), padS.toDouble()), Dictionaries.byName(dict), keep, notes, rate)
        result = null
        failed = false
        job = scope.launch {
            try {
                val (pieces, seconds) = withContext(Dispatchers.Default) {
                    val fs = ws.fs
                    val wavs = Paths.join(folder, "wavs")
                    fs.mkdirs(wavs)
                    val rows = ArrayList<DsCsv.Row>()
                    val used = HashSet<String>()
                    var total = 0.0
                    for ((k, item) in items.withIndex()) {
                        ensureActive()
                        progress = k to items.size
                        val audio = runCatching {
                            val bytes = fs.read(item.audioPath)
                            if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(item.audioPath)
                        }.getOrNull() ?: continue
                        val doc = runCatching { ws.readLabels(item, audio.duration) }.getOrNull() ?: continue
                        val needF0 = notes == NoteSource.Pitch || (notes == NoteSource.Labels && doc.tiers.none { it is NoteTier })
                        val f0 = if (needF0) Dataset.f0(audio) else null
                        var base = item.name
                        var n = 2
                        while (base in used) base = "${item.name}_${n++}"
                        used += base
                        for (seg in Dataset.segments(base, doc, audio.duration, f0, o)) {
                            val part = Dataset.cutAudio(audio, seg.from, seg.to, o.sampleRate)
                            fs.write(Paths.join(wavs, seg.name + ".wav"), Wav.encode16(part))
                            rows += DsCsv.Row(seg.name, seg.doc)
                            total += seg.to - seg.from
                        }
                    }
                    fs.write(Paths.join(folder, "transcriptions.csv"), DsCsv.write(rows).encodeToByteArray())
                    rows.size to total
                }
                val msg = doneMsg.format(pieces, formatTime(seconds, precise = false), items.size, folder) +
                    if (skipped > 0) "\n" + skippedMsg.format(skipped) else ""
                result = msg
                app.message(doneMsg.format(pieces, formatTime(seconds, precise = false), items.size, Paths.name(folder)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                result = null
            } catch (e: Exception) {
                result = failedMsg.format(e.message ?: e.toString())
                failed = true
            } finally {
                progress = null
                job = null
            }
        }
    }

    Overlay({ if (job == null) close() }, 640) {
        DialogContent(footer = {
            if (job != null) Btn(stopBtn()) { job?.cancel() }
            else {
                Btn(S.close()) { close() }
                Btn(exportBtn(), primary = true, enabled = folder.isNotBlank()) { run() }
            }
        }) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Text(hint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            SectionTitle(whereT())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(folder, { folder = it }, Modifier.weight(1f))
                Btn(pickT()) { app.pickFolder(whereT()) { folder = it } }
            }
            SectionTitle(whichT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(allLabeled(), !done) { done = false }
                Chip(onlyDone(), done) { done = true }
            }
            SectionTitle(piecesT())
            ValueSlider(maxLen(), maxS, 3f..40f, unit = " s", default = 15f) { maxS = it }
            ValueSlider(minLen(), minS, 0f..10f, unit = " s", decimals = 1, default = 1f) { minS = it }
            ValueSlider(pad(), padS, 0f..1f, unit = " s", decimals = 2, default = 0.3f) { padS = it }
            SectionTitle(groupsT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(keepWords(), keep) { keep = true }
                Chip(byDict(), !keep) { keep = false }
            }
            SectionTitle(dictT())
            Text(dictHint(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            DictionaryChips(dict) { dict = it }
            SectionTitle(notesT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(notesPitch(), notes == NoteSource.Pitch) { notes = NoteSource.Pitch }
                Chip(notesLabels(), notes == NoteSource.Labels) { notes = NoteSource.Labels }
                Chip(notesNone(), notes == NoteSource.None) { notes = NoteSource.None }
            }
            SectionTitle(rateT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in listOf(44100, 48000)) Chip("$r Hz", rate == r) { rate = r }
                Chip(asIs(), rate == 0) { rate = 0 }
            }
            progress?.let { (k, n) ->
                Text(working.format(k + 1, n), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.panelAlt)) {
                    Box(Modifier.fillMaxWidth((k + 1f) / n.coerceAtLeast(1)).height(6.dp).background(c.accent))
                }
            }
            result?.let { Text(it, color = if (failed) c.danger else c.ok, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

/**
 * Phoneme dictionaries, chosen like models: the language first, then where the set comes from (DiffSinger,
 * OpenUtau, a model's author, your own files). The first chip, "Guess from letters" or [autoTitle], picks an empty name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryChips(selected: String, autoTitle: String? = null, onPick: (String) -> Unit) {
    val c = mlabeler.app.theme.T.c
    var version by remember { mutableStateOf(0) }
    val all = remember(version) { Dictionaries.all().filter { it.name != "Auto" } }
    val cur = all.firstOrNull { it.name == selected }
    // own dictionaries without a language are shown together
    val langs = all.map { it.language }.distinct()
    var lang by remember(selected) { mutableStateOf(cur?.language) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(autoTitle ?: dictAuto(), cur == null && lang == null) { lang = null; onPick("") }
        for (l in langs) Chip(langTitle(l), lang == l) {
            lang = l
            if (cur?.language != l) all.firstOrNull { it.language == l }?.let { onPick(it.name) }
        }
    }
    val variants = all.filter { lang != null && it.language == lang }
    if (variants.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(sourceT(), color = c.muted, fontSize = 12.sp)
        for (v in variants) Chip(v.source.ifEmpty { v.name }, v.name == cur?.name) { onPick(v.name) }
    }
    Text(missingT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cur != null) Btn(ownCopyT()) {
            val title = dictTitle(cur) + ownSuffixT()
            Dictionaries.saveCopy(cur.copy(source = ""), title)
            version++
            onPick(title)
            mlabeler.app.Platform.openInFileManager(Dictionaries.dir())
        }
        Btn(folderT()) {
            mlabeler.core.io.PlatformFs.mkdirs(Dictionaries.dir())
            mlabeler.app.Platform.openInFileManager(Dictionaries.dir())
        }
        Btn(rereadT()) { version++ }
    }
}

private fun langTitle(code: String) = if (code.isEmpty()) ownT() else mlabeler.app.i18n.LanguageNames.of(code)
private val sourceT = L("Source:", "Источник:")
private val ownT = L("Your own", "Свои")
private val ownSuffixT = L(" (own)", " (свой)")
private val missingT = L("No dictionary for your set? \u201cGuess from letters\u201d works with any phonemes, or make your own from the chosen one: a JSON file with lists of vowels and consonants.",
    "Нет подходящего словаря? «Определить по буквам» работает с любым набором фонем, или сделайте свой на основе выбранного: это JSON-файл со списками гласных и согласных.")
private val ownCopyT = L("Make your own from this one…", "Сделать свой на основе этого…")
private val folderT = L("Dictionaries folder…", "Папка словарей…")
private val rereadT = L("Read the files again", "Перечитать файлы")

private val dictAuto = L("Guess from letters", "Определить по буквам")

/** The name of a phoneme dictionary in the interface language: its language and source. */
internal fun dictTitle(d: mlabeler.core.ds.PhonemeDict): String = when {
    d.name == "Auto" -> dictAuto()
    d.language.isEmpty() || d.source.isEmpty() -> d.name
    else -> mlabeler.app.i18n.LanguageNames.of(d.language) + " (" + d.source + ")"
}
