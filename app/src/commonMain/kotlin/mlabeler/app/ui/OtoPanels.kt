@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mlabeler.app.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.EditorState
import mlabeler.app.state.FileFilter
import mlabeler.app.theme.T
import mlabeler.core.format.OtoMarker
import mlabeler.core.format.formatNumberPublic
import mlabeler.core.io.Paths

private val noEntries = L("No oto entries yet. Add one with N or the + button.", "Записей oto пока нет. Добавьте клавишей N или кнопкой +.")
private val entriesCount = L("{0} entries", "записей: {0}")
private val alias = L("Alias", "Псевдоним")
private val otoFile = L("oto.ini", "oto.ini")
private val noEntryFiles = L("Not in oto", "Нет в oto")
private val missingSample = L("No such recording in the folder", "Такой записи в папке нет")

/** Search accepts plain text (alias or file) or "alias:", "sample:" prefixes. */
private fun matches(q: String, alias: String, sample: String, tag: String = ""): Boolean {
    if (q.isBlank()) return true
    return q.split(';').map { it.trim() }.filter { it.isNotEmpty() }.all { part ->
        when {
            part.startsWith("alias:") -> alias.contains(part.removePrefix("alias:").trim('"', ' '), ignoreCase = true)
            part.startsWith("sample:") -> sample.contains(part.removePrefix("sample:").trim('"', ' '), ignoreCase = true)
            part.startsWith("tag:") -> tag.contains(part.removePrefix("tag:").trim('"', ' '), ignoreCase = true)
            else -> alias.contains(part, ignoreCase = true) || sample.contains(part, ignoreCase = true)
        }
    }
}

@Composable
fun OtoEntryList(ed: EditorState, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    val c = T.c
    val entries = ed.oto.entries
    Column(modifier.background(c.panel)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
            Field(ed.query, { ed.query = it }, Modifier.fillMaxWidth(), placeholder = S.search())
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.all(), ed.filter == FileFilter.All) { ed.filter = FileFilter.All }
                Chip(S.notDone(), ed.filter == FileFilter.NotDone) { ed.filter = FileFilter.NotDone }
                Chip(S.starred(), ed.filter == FileFilter.Starred) { ed.filter = FileFilter.Starred }
                Chip(noEntryFiles(), ed.filter == FileFilter.NoLabels) { ed.filter = FileFilter.NoLabels }
            }
            // a voicebank with a folder per pitch (or per append): switch between them; each has its own oto.ini
            val folders = remember(ed.items) { ed.items.map { Paths.parent(it.audioPath) }.distinct().sortedWith(compareBy { it.lowercase() }) }
            if (folders.size > 1) {
                val cur = ed.item?.let { Paths.parent(it.audioPath) }
                Text(pitchFolders(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in folders) {
                        val rel = ed.workspace.relative(f).ifEmpty { "." }
                        Chip(rel, f == cur) {
                            val i = ed.items.indexOfFirst { Paths.parent(it.audioPath) == f }
                            if (i >= 0 && f != cur) { ed.open(i); onOpened() }
                        }
                    }
                }
            }
        }
        val dir = ed.item?.let { Paths.parent(it.audioPath) }
        val folderFiles = ed.items.withIndex().filter { dir == null || Paths.parent(it.value.audioPath) == dir }
        if (ed.filter == FileFilter.NoLabels) {
            // recordings of this folder that oto.ini does not mention
            val used = entries.map { it.sample.lowercase() }.toSet()
            val missing = folderFiles.filter { Paths.name(it.value.audioPath).lowercase() !in used }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(missing, key = { it.value.id }) { (i, item) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 48.dp else 30.dp)
                            .background(if (i == ed.index) c.accent.copy(alpha = 0.16f) else c.panel)
                            .clickable { ed.open(i); onOpened() }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Paths.name(item.audioPath), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconBtn(Icons.plus, Commands.otoAdd.title(), size = 26.dp) { ed.open(i); ed.oto.add() }
                    }
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entriesCount.format(missing.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (missing.isNotEmpty()) Btn(Commands.autoOto.title()) { ed.app.showAutoOto = true }
            }
            return@Column
        }
        val existing = folderFiles.map { Paths.name(it.value.audioPath).lowercase() }.toSet()
        val list = entries.withIndex().filter {
            matches(ed.query, it.value.alias, it.value.sample, ed.oto.marks(it.value).tag) && when (ed.filter) {
                FileFilter.NotDone -> !ed.oto.marks(it.value).done
                FileFilter.Starred -> ed.oto.marks(it.value).star
                else -> true
            }
        }
        val state = rememberLazyListState()
        LaunchedEffect(ed.oto.selected) {
            val pos = list.indexOfFirst { it.index == ed.oto.selected }
            if (pos >= 0 && (pos < state.firstVisibleItemIndex || pos > state.firstVisibleItemIndex + state.layoutInfo.visibleItemsInfo.size - 2)) {
                state.scrollToItem(maxOf(0, pos - 3))
            }
        }
        if (entries.isEmpty()) Text(noEntries(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(list, key = { it.index }) { (i, e) ->
                val sel = i == ed.oto.selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 48.dp else 30.dp)
                        .background(if (sel) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                        .clickable { ed.oto.select(i); onOpened() }
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (sel && c.square) c.onAccent else c.text
                    val m = ed.oto.marks(e)
                    androidx.compose.foundation.layout.Box(
                        Modifier.padding(end = 8.dp).width(7.dp).height(7.dp)
                            .background(if (m.done) c.ok else c.muted.copy(alpha = 0.2f), androidx.compose.foundation.shape.CircleShape),
                    )
                    Text(e.alias.ifEmpty { "∅" }, color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    if (m.star) androidx.compose.material3.Icon(Icons.starOn, null, Modifier.width(13.dp).height(13.dp), tint = c.warn)
                    // the recording this entry names is not in the folder
                    if (e.sample.lowercase() !in existing) androidx.compose.material3.Icon(Icons.warn, missingSample(), Modifier.width(13.dp).height(13.dp), tint = c.danger)
                    Text(Paths.stem(e.sample), color = if (sel && c.square) c.onAccent else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.8f))
                }
            }
        }
        LazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), label = { n -> list.getOrNull(n)?.value?.alias })
        }
        Divider()
        Text(entriesCount.format(entries.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
fun OtoInspector(ed: EditorState, modifier: Modifier = Modifier) {
    val c = T.c
    val e = ed.oto.current()
    Column(modifier.background(c.panel).scrollWithHint().padding(horizontal = 14.dp, vertical = 4.dp)) {
        SectionTitle(otoFile())
        ed.item?.let { Text(ed.workspace.relative(ed.oto.bookPath(it)), color = c.muted, fontSize = 12.sp) }
        ed.oto.book()?.let { Text(it.charset, color = c.muted, fontSize = 12.sp) }
        if (e == null) {
            Spacer(Modifier.height(12.dp))
            Text(noEntries(), color = c.muted, fontSize = 13.sp)
            return@Column
        }
        SectionTitle(alias())
        var a by remember(ed.oto.selected, ed.oto.version) { mutableStateOf(e.alias) }
        Field(a, { a = it }, Modifier.fillMaxWidth(), onDone = { ed.oto.rename(a.trim()) })
        LaunchedEffect(a) {
            kotlinx.coroutines.delay(600)
            if (a.trim() != e.alias) ed.oto.rename(a.trim())
        }
        Text(e.sample, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        val marks = ed.oto.marks(e)
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(S.done(), marks.done) { ed.oto.setMarks(e) { it.copy(done = !it.done) } }
            Chip(S.star(), marks.star) { ed.oto.setMarks(e) { it.copy(star = !it.star) } }
        }
        var tag by remember(ed.oto.selected) { mutableStateOf(marks.tag) }
        Field(tag, { tag = it; ed.oto.setMarks(e) { m -> m.copy(tag = it.trim()) } }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = S.tag())
        SectionTitle(S.inspector())
        val rows = listOf(
            Triple(OtoMarker.Left, "Offset", e.offset),
            Triple(OtoMarker.Overlap, "Overlap", e.overlap),
            Triple(OtoMarker.Preutterance, "Preutterance", e.preutterance),
            Triple(OtoMarker.Consonant, "Consonant", e.consonant),
            Triple(OtoMarker.Right, "Cutoff", e.cutoff),
        )
        for ((m, name, value) in rows) {
            var text by remember(ed.oto.selected, ed.oto.version, m) { mutableStateOf(formatNumberPublic(value, 3)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = c.text, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                Field(text, { text = it }, Modifier.weight(1f), onDone = {
                    text.replace(',', '.').toDoubleOrNull()?.let { v -> ed.oto.setValue(m, v) }
                })
            }
        }
        // how dragging the red line behaves; also on the toolbar and key {oto-lock}
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { Commands.otoLock.run(ed, ed.app) }, verticalAlignment = Alignment.CenterVertically) {
            Text(otoLockT() + "  (" + Commands.otoLock.keyLabel + ")", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Toggle(ed.app.settings.edit.otoLockedDrag, { Commands.otoLock.run(ed, ed.app) })
        }
        Text(otoLockHint(), color = c.muted, fontSize = 11.sp)
        OtoCompareSection(ed)
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(Commands.otoDuplicate.title()) { ed.oto.duplicate() }
            Btn(Commands.otoDelete.title()) { ed.oto.delete() }
        }
    }
}

@Composable
private fun OtoCompareSection(ed: EditorState) {
    val c = T.c
    SectionTitle(S.otoCompare()) {
        IconBtn(Icons.plus, S.otoCompare(), size = 26.dp) { ed.app.pickFolder(S.otoCompare()) { ed.oto.loadReference(it) } }
    }
    val ref = ed.oto.reference
    if (ref == null) {
        Text(S.otoCompareHint(), color = c.muted, fontSize = 12.sp)
        return
    }
    val d = remember(ref, ed.oto.version) { mlabeler.core.format.OtoCompare.diff(ed.oto.entries, ref.second) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Paths.name(ref.first), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconBtn(Icons.close, S.removeFromList(), size = 24.dp) { ed.oto.reference = null }
    }
    Text(S.otoDiffLine.format(d.matched, d.onlyHere, d.onlyThere), color = c.muted, fontSize = 12.sp)
    Text(S.otoDiffMean() + ": " + d.meanMs.entries.joinToString("  ") { (m, v) -> m.name.take(4).lowercase() + " " + (kotlin.math.round(v * 10) / 10) },
        color = c.muted, fontSize = 12.sp)
}

private val otoLockT = mlabeler.app.i18n.L("Preutterance moves all markers", "Preutterance двигает все маркеры")
private val otoLockHint = mlabeler.app.i18n.L("Off: dragging the red line moves only it. Shift while dragging does the opposite of this switch.",
    "Выключено: перетаскивание красной линии сдвигает только её. Shift во время перетаскивания меняет это поведение на противоположное.")

private val pitchFolders = mlabeler.app.i18n.L("Folders (pitches, appends)", "Папки (высоты, аппенды)")

private val renameHere = L("Click to rename; Enter keeps it, Esc cancels", "Щёлкните, чтобы переименовать; Enter — сохранить, Esc — отменить")

/** The selected entry's alias in large type over the picture, renamed in place, with its recording in brackets. */
@Composable
fun OtoHeader(ed: EditorState) {
    val c = T.c
    val e = ed.oto.current() ?: return
    var text by remember(ed.oto.selected, ed.oto.version) { mutableStateOf(e.alias) }
    var focused by remember { mutableStateOf(false) }
    fun commit() { if (text.trim() != e.alias) ed.oto.rename(text.trim()) }
    Row(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Tip(renameHere()) {
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 26.sp, fontFamily = T.font),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit(); ed.requestFocus() }),
                modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 60.dp)
                    .border(c.borderWidth, if (focused) c.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(c.radius))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .trackTextFocus()
                    .onFocusChanged { st -> if (focused && !st.isFocused) commit(); focused = st.isFocused }
                    .onPreviewKeyEvent { k ->
                        if (k.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && k.key == androidx.compose.ui.input.key.Key.Escape) {
                            text = e.alias; ed.requestFocus(); true
                        } else false
                    },
            )
        }
        Text("(" + Paths.name(e.sample) + ")", color = c.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f))
        val n = ed.oto.entries.size
        ed.oto.selected?.let { Text("${it + 1} / $n", color = c.muted, fontSize = 12.sp) }
    }
}
