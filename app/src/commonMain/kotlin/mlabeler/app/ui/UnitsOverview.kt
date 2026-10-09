package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.io.Paths

private val titleT = L("Transitions of the folder (.trans)", "Переходы папки (.trans)")
private val aboutT = L(
    "Every transition between two phonemes in the .seg/.trans recordings of the folder, and where the transcriptions keep it. A transition kept in several recordings only makes the voicebank bigger: listen to them and keep the best one. Kept ones are switched on; a click changes it.",
    "Все переходы между фонемами в записях .seg/.trans этой папки и где они оставлены в транскрипциях. Переход, оставленный в нескольких записях, только утяжеляет банк: прослушайте их и оставьте лучший. Оставленные включены; щелчок меняет выбор.",
)
private val readingT = L("Reading the recordings… {0} of {1}", "Чтение записей… {0} из {1}")
private val noneT = L("No .seg/.trans recordings in this folder", "В этой папке нет записей .seg/.trans")
private val allT = L("All ({0})", "Все ({0})")
private val repeatsT = L("Kept more than once ({0})", "Оставлены несколько раз ({0})")
private val missingT = L("Kept nowhere ({0})", "Нигде не оставлены ({0})")
private val keptT = L("kept {0} of {1}", "оставлено {0} из {1}")
private val keepFirstT = L("Keep one of each (the first)", "Оставить по одному (первый)")
private val keepFirstHint = L("Where a transition is kept in several recordings, only the first of them (in the order of the list) keeps it. Check the result before saving.",
    "Где переход оставлен в нескольких записях, он остаётся только в первой из них (по порядку списка). Проверьте результат перед сохранением.")
private val onlyThisT = L("Only here", "Только здесь")
private val applyT = L("Save ({0} files)", "Сохранить (файлов: {0})")
private val savedT = L("Transitions saved in {0} files", "Переходы сохранены в файлах: {0}")
private val searchT = L("Transition, e.g. b' a", "Переход, например b' a")
private val playT = L("Listen", "Прослушать")
private val openT = L("Open the recording", "Открыть запись")

@Composable
fun UnitsOverviewDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val scope = rememberCoroutineScope()
    fun close() { app.showUnitsOverview = false; ed.requestFocus() }
    var places by remember { mutableStateOf<List<EditorState.UnitPlace>?>(null) }
    var reading by remember { mutableStateOf(0 to 0) }
    // what was changed here: (file, first phoneme, size) → kept
    val changes = remember { mutableStateMapOf<Triple<String, Int, Int>, Boolean>() }
    var filter by remember { mutableStateOf(1) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { places = ed.unitPlaces { n, of -> reading = n to of } }
    fun kept(p: EditorState.UnitPlace) = changes[Triple(p.itemId, p.index, p.size)] ?: p.chosen
    fun set(p: EditorState.UnitPlace, v: Boolean) {
        val k = Triple(p.itemId, p.index, p.size)
        if (v == p.chosen) changes.remove(k) else changes[k] = v
    }
    val order = remember(ed.items) { ed.items.withIndex().associate { it.value.id to it.index } }
    val groups = remember(places) {
        places.orEmpty().groupBy { it.unit }.mapValues { (_, l) -> l.sortedWith(compareBy({ order[it.itemId] ?: 0 }, { it.index })) }
            .toList().sortedBy { it.first }
    }
    Overlay({ if (!busy) close() }, 760) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        val list = places
        if (list == null) {
            Text(readingT.format(reading.first, reading.second), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
        } else if (list.isEmpty()) {
            Text(noneT(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
        } else {
            val counted = groups.map { (u, l) -> Triple(u, l, l.count { kept(it) }) }
            Column(Modifier.padding(horizontal = 18.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(allT.format(counted.size), filter == 0) { filter = 0 }
                    Chip(repeatsT.format(counted.count { it.third > 1 }), filter == 1) { filter = 1 }
                    Chip(missingT.format(counted.count { it.third == 0 }), filter == 2) { filter = 2 }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(query, { query = it }, Modifier.weight(1f), placeholder = searchT())
                    Tip(keepFirstHint()) {
                        Btn(keepFirstT()) {
                            for ((_, l, _) in counted) {
                                val k = l.filter { kept(it) }
                                if (k.size > 1) k.drop(1).forEach { set(it, false) }
                            }
                        }
                    }
                }
            }
            val shown = counted.filter { (u, _, n) ->
                (filter == 0 || (filter == 1 && n > 1) || (filter == 2 && n == 0)) && (query.isBlank() || u.contains(query.trim(), ignoreCase = true))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 160.dp)) {
                items(shown, key = { it.first }) { (u, l, n) ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { open = if (open == u) null else u }.padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (open == u) Icons.down else Icons.right, null, Modifier.size(14.dp), tint = c.muted)
                            Spacer(Modifier.width(6.dp))
                            Text("[$u]", color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(keptT.format(n, l.size), color = if (n > 1) c.warn else if (n == 0) c.muted else c.ok, fontSize = 12.sp)
                        }
                        if (open == u) for (p in l) {
                            val k = kept(p)
                            Row(
                                Modifier.fillMaxWidth().background(c.panelAlt).padding(start = 34.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Toggle(k, { v -> set(p, v) })
                                Text(Paths.name(p.itemId), color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatTime(p.start), color = c.muted, fontSize = 11.sp)
                                IconBtn(Icons.play, playT(), size = 28.dp) { ed.playPlace(p) }
                                Btn(onlyThisT()) { l.forEach { o -> set(o, o === p) } }
                                IconBtn(Icons.right, openT(), size = 28.dp) {
                                    ed.items.indexOfFirst { it.id == p.itemId }.takeIf { it >= 0 }?.let { i -> ed.open(i) }
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(c.borderWidth).background(c.border.copy(alpha = 0.5f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Btn(S.cancel(), enabled = !busy) { close() }
            val files = changes.keys.map { it.first }.toSet()
            Btn(applyT.format(files.size), primary = true, enabled = files.isNotEmpty() && !busy) {
                val all = places.orEmpty()
                val byFile = files.associateWith { id -> all.filter { it.itemId == id && kept(it) }.map { it.index to it.size } }
                busy = true
                scope.launch {
                    val n = try { ed.applyUnits(byFile) } finally { busy = false }
                    app.message(savedT.format(n))
                    close()
                }
            }
        }
    }
}
