package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.Dictionaries
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.ds.DatasetStats
import mlabeler.core.model.LabelDoc
import kotlin.math.roundToInt

private val title = L("Dataset summary", "Сводка по датасету")
private val reading = L("Reading the labels: {0} of {1}…", "Читаю разметку: {0} из {1}…")
private val filesT = L("Recordings", "Записи")
private val filesLine = L("{0} in the folder · {1} labelled · {2} done", "{0} в папке · {1} размечено · {2} готово")
private val lengthT = L("Length", "Длительность")
private val lengthLine = L("{0} labelled · {1} without pauses", "{0} размечено · {1} без пауз")
private val phonemesT = L("Phonemes", "Фонемы")
private val phonemesLine = L("{0} different, {1} in all", "{0} разных, всего {1}")
private val rareT = L("Rare phonemes", "Редкие фонемы")
private val rareHint = L("A model learns a phoneme from its examples: with only a few, it is often sung wrong. Click one to find its files.",
    "Модель учится фонеме на её примерах: если их мало, она часто звучит неверно. Нажмите, чтобы найти её файлы.")
private val rareAtMost = L("at most", "не больше")
private val noneRare = L("None", "Нет")
private val dictT = L("Compared with the dictionary", "Сравнение со словарём")
private val dictPick = L("Pick a dictionary to see phonemes it lacks and ones never used.", "Выберите словарь, чтобы увидеть фонемы, которых в нём нет, и те, что ни разу не встретились.")
private val notInDict = L("Not in the dictionary", "Нет в словаре")
private val unusedT = L("Never used", "Ни разу не встретились")
private val allGood = L("All phonemes are in the dictionary and all of it is used", "Все фонемы есть в словаре, и весь словарь использован")
private val notesT = L("Notes", "Ноты")
private val notesHint = L("How much is sung on each note. Gaps in the range are notes the model hasn't heard.",
    "Сколько спето на каждой ноте. Дыры в диапазоне — ноты, которых модель не слышала.")
private val tableT = L("All phonemes", "Все фонемы")
private val colName = L("Phoneme", "Фонема")
private val colCount = L("Times", "Раз")
private val colFiles = L("Files", "Файлов")
private val colTotal = L("Total", "Всего")
private val colMean = L("Average", "В среднем")

/** Counts over all labelled files of the folder: what there is to train on and what is missing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatasetSummaryDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showSummary = false; ed.requestFocus() }
    var docs by remember { mutableStateOf<List<LabelDoc>?>(null) }
    var read by remember { mutableStateOf(0) }
    val labelled = remember { ed.items.filter { it.labelPath != null } }
    LaunchedEffect(Unit) {
        docs = withContext(Dispatchers.Default) {
            labelled.mapIndexedNotNull { i, item ->
                read = i + 1
                if (item.id == ed.item?.id) ed.doc else runCatching { ed.workspace.readLabels(item, 0.0) }.getOrNull()
            }
        }
    }
    var dictName by remember { mutableStateOf(ed.workspace.state.dictionary) }
    var rareLimit by remember { mutableStateOf(5) }
    val pauses = app.settings.checks.pauses
    val stats = remember(docs, dictName) { docs?.let { DatasetStats.of(it, pauses, Dictionaries.byName(dictName)) } }
    Overlay({ close() }, 760) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title(), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.close, S.close()) { close() }
            }
            if (stats == null) {
                Text(reading.format(read, labelled.size), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                return@Column
            }
            fun find(p: String) { ed.query = p; close() }
            val done = ed.items.count { ed.marks(it).done }
            Fact(filesT(), filesLine.format(ed.items.size, labelled.size, done))
            Fact(lengthT(), lengthLine.format(formatTime(stats.seconds, precise = false), formatTime(stats.singingSeconds, precise = false)))
            Fact(phonemesT(), phonemesLine.format(stats.phonemes.count { it.name !in pauses }, stats.phonemes.filter { it.name !in pauses }.sumOf { it.count }))

            SectionTitle(rareT())
            Text(rareHint(), color = c.muted, fontSize = 11.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rareAtMost(), color = c.muted, fontSize = 12.sp)
                for (n in listOf(1, 3, 5, 10, 20)) Chip(n.toString(), rareLimit == n) { rareLimit = n }
            }
            val rare = stats.rare(rareLimit).filter { it.name !in pauses }
            if (rare.isEmpty()) Text(noneRare(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            else FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in rare.sortedBy { it.count }) Chip("${p.name} · ${p.count}", false) { find(p.name) }
            }

            SectionTitle(dictT())
            DictionaryChips(dictName) { dictName = it }
            val dict = Dictionaries.byName(dictName)
            if (dict.vowels.isEmpty() && dict.consonants.isEmpty()) Text(dictPick(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            else if (stats.notInDictionary.isEmpty() && stats.unusedFromDictionary.isEmpty()) Text(allGood(), color = c.ok, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            else {
                if (stats.notInDictionary.isNotEmpty()) {
                    Text(notInDict(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in stats.notInDictionary) Chip(p, false) { find(p) }
                    }
                }
                if (stats.unusedFromDictionary.isNotEmpty()) {
                    Text(unusedT(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(stats.unusedFromDictionary.joinToString("  "), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (stats.notes.isNotEmpty()) {
                SectionTitle(notesT())
                Text(notesHint(), color = c.muted, fontSize = 11.sp)
                NoteBars(stats)
            }

            SectionTitle(tableT())
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for ((t, w) in listOf(colName() to 1.4f, colCount() to 1f, colFiles() to 1f, colTotal() to 1.2f, colMean() to 1.2f)) {
                    Text(t, color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(w))
                }
            }
            for (p in stats.phonemes) {
                val rareOne = p.count <= rareLimit && p.name !in pauses
                Row(Modifier.fillMaxWidth().clickable { find(p.name) }.padding(vertical = 3.dp)) {
                    Text(p.name, color = if (rareOne) c.warn else c.text, fontSize = 13.sp, modifier = Modifier.weight(1.4f))
                    Text(p.count.toString(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(p.files.toString(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(formatTime(p.seconds, precise = false), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1.2f))
                    Text("${p.meanMs.roundToInt()} ${S.msUnit()}", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1.2f))
                }
            }
        }
    }
}

@Composable
private fun Fact(name: String, value: String) {
    val c = T.c
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(name, color = c.muted, fontSize = 13.sp, modifier = Modifier.width(150.dp))
        Text(value, color = c.text, fontSize = 13.sp)
    }
}

/** Seconds sung on each note from the lowest to the highest, empty notes included. */
@Composable
private fun NoteBars(stats: DatasetStats) {
    val c = T.c
    val lo = stats.notes.keys.min()
    val hi = stats.notes.keys.max()
    val most = stats.notes.values.maxOf { it.seconds }.coerceAtLeast(1e-6)
    Row(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (m in lo..hi) {
            val s = stats.notes[m]?.seconds ?: 0.0
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().weight((1 - s / most).toFloat().coerceAtLeast(0.001f)))
                Box(Modifier.fillMaxWidth().weight((s / most).toFloat().coerceAtLeast(0.02f)).background(if (s == 0.0) c.danger.copy(alpha = 0.6f) else c.accent))
                if ((m - lo) % 3 == 0 || hi - lo < 12) Text(mlabeler.core.format.NoteNames.format(m.toDouble()), color = c.muted, fontSize = 9.sp, maxLines = 1)
                else Text(" ", fontSize = 9.sp)
            }
        }
    }
}
