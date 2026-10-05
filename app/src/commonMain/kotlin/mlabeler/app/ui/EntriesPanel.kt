package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.EditorState
import mlabeler.app.state.Selection
import mlabeler.app.theme.T
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.name

val entriesTitle = L("Entries", "Записи")
private val thisFile = L("This file", "Этот файл")
private val allFiles = L("All files", "Все файлы")
private val summary = L("Counts", "Подсчёт")
private val searchHint = L("text, or name: file: tier:", "текст, или name: file: tier:")
private val countLine = L("{0} entries", "записей: {0}")

/** One interval somewhere in the folder. */
private data class Entry(val item: Int, val file: String, val tier: String, val index: Int, val text: String, val start: Double, val end: Double)

private fun matches(q: String, e: Entry): Boolean {
    if (q.isBlank()) return true
    return q.split(';').map { it.trim() }.filter { it.isNotEmpty() }.all { p ->
        fun v(prefix: String) = p.removePrefix(prefix).trim().trim('"')
        when {
            p.startsWith("name:") -> v("name:").let { if (it.startsWith("=")) e.text == it.drop(1) else e.text.contains(it, true) }
            p.startsWith("file:") -> e.file.contains(v("file:"), true)
            p.startsWith("tier:") -> e.tier.equals(v("tier:"), true)
            else -> e.text.contains(p, true) || e.file.contains(p, true)
        }
    }
}

/** The list of intervals (phonemes, words…) of this file or the whole folder, with search and counts. */
@Composable
fun EntriesPanel(ed: EditorState, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    val c = T.c
    var all by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var everything by remember { mutableStateOf<List<Entry>?>(null) }
    val doc = ed.doc
    val current: List<Entry> = remember(doc, ed.index, ed.activeTier) {
        val t = doc?.tiers?.getOrNull(ed.activeTier) as? IntervalTier ?: return@remember emptyList()
        (0 until t.size).map { Entry(ed.index, ed.item?.id ?: "", t.name, it, t.texts[it], t.startOf(it), t.endOf(it)) }
    }
    LaunchedEffect(all, ed.items) {
        if (!all) return@LaunchedEffect
        everything = withContext(Dispatchers.Default) {
            ed.items.withIndex().flatMap { (k, item) ->
                val d = runCatching { ed.workspace.readLabels(item, 0.0) }.getOrNull() ?: return@flatMap emptyList()
                d.tiers.filterIsInstance<IntervalTier>().flatMap { t ->
                    (0 until t.size).map { Entry(k, item.id, t.name, it, t.texts[it], t.startOf(it), t.endOf(it)) }
                }
            }
        }
    }
    val source = if (all) everything.orEmpty() else current
    val list = remember(source, query) { source.filter { matches(query, it) } }
    Column(modifier.background(c.panel)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Field(query, { query = it }, Modifier.fillMaxWidth(), placeholder = searchHint())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(thisFile(), !all) { all = false }
                Chip(allFiles(), all) { all = true }
                Chip(summary(), counts) { counts = !counts }
            }
        }
        if (all && everything == null) Text(S.loading(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        if (counts) {
            // how often each label occurs; a click searches for it
            val byText = list.groupingBy { it.text }.eachCount().entries.sortedByDescending { it.value }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(byText, key = { it.key }) { (text, n) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = "name:=$text"; counts = false }.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text.ifEmpty { "∅" }, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(n.toString(), color = c.muted, fontSize = 12.sp)
                    }
                }
            }
        } else {
            val sel = (ed.selection as? Selection.Interval)?.ref
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(list, key = { "${it.item}/${it.tier}/${it.index}" }) { e ->
                    val active = e.item == ed.index && sel != null && sel.index == e.index &&
                        doc?.tiers?.getOrNull(sel.tier)?.name == e.tier
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 44.dp else 26.dp)
                            .background(if (active) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                            .clickable { ed.openInterval(e.item, e.tier, e.index); onOpened() }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (active && c.square) c.onAccent else c.text
                        Text(e.text.ifEmpty { "∅" }, color = fg, fontSize = 13.sp, modifier = Modifier.width(70.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (all) Paths.stem(e.file) else formatTime(e.start), color = if (active && c.square) c.onAccent else c.muted,
                            fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(formatMs(e.end - e.start), color = if (active && c.square) c.onAccent else c.muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Divider()
        Text(countLine.format(list.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
