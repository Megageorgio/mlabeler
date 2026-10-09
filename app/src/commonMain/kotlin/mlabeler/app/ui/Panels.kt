@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mlabeler.app.ui

import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.S
import mlabeler.app.i18n.L
import mlabeler.app.state.EditorState
import mlabeler.app.fileDrop
import mlabeler.app.state.FileFilter
import mlabeler.app.state.Selection
import mlabeler.app.theme.T
import mlabeler.core.check.Problem
import mlabeler.core.edit.Edits
import mlabeler.core.edit.IntervalRef
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.name

@Composable
fun FilesPanel(ed: EditorState, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    val c = T.c
    var dropHover by remember { mutableStateOf(false) }
    // recordings dropped on the list are copied into this folder
    Box(modifier.fileDrop({ dropHover = it }) { ed.addFiles(it) > 0 }) {
    FilesList(ed, Modifier.fillMaxSize(), onOpened)
    if (dropHover) DropHint(dropAdd.format(Paths.name(ed.workspace.root)))
    }
}

@Composable
private fun FilesList(ed: EditorState, modifier: Modifier, onOpened: () -> Unit) {
    val c = T.c
    val hasDirs = ed.items.any { '/' in it.id }
    var tree by remember(ed.workspace) { mutableStateOf(ed.workspace.state.tree) }
    var folded by remember(ed.workspace) { mutableStateOf(ed.workspace.state.folded) }
    // labels' last change, shown as "5 min" and refreshed every minute
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    // a file's menu (right click or long press) and the dialogs it opens
    var menuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    Column(modifier.background(c.panel)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
            Field(ed.query, { ed.query = it }, Modifier.fillMaxWidth(), placeholder = searchHint())
            // chips wrap onto more lines in a narrow panel (a sideways scroll can't be reached with a wheel)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(S.all(), ed.filter == FileFilter.All) { ed.filter = FileFilter.All }
                Chip(S.notDone(), ed.filter == FileFilter.NotDone) { ed.filter = FileFilter.NotDone }
                Chip(S.starred(), ed.filter == FileFilter.Starred) { ed.filter = FileFilter.Starred }
                Chip(S.noLabels(), ed.filter == FileFilter.NoLabels) { ed.filter = FileFilter.NoLabels }
                if (hasDirs) Tip(treeHint()) {
                    Chip(treeT(), tree) { tree = !tree; ed.workspace.updateState { it.copy(tree = tree) } }
                }
            }
        }
        val list = ed.filtered()
        // grouped by subfolder: a heading row per folder (click folds it), its files below
        val rows: List<FileRow> = remember(list, tree, folded, hasDirs) {
            if (!tree || !hasDirs) list.map { FileRow(it.first, it.second) }
            else buildList {
                val groups = list.groupBy { it.second.id.substringBeforeLast('/', "") }
                for (dir in groups.keys.sortedWith(compareBy(mlabeler.core.io.naturalOrder()) { it.lowercase() })) {
                    val g = groups.getValue(dir)
                    add(FileRow(-1, null, dir, g.size, g.count { ed.marks(it.second).done }))
                    if (dir !in folded) for ((i, item) in g) add(FileRow(i, item))
                }
            }
        }
        val state = rememberLazyListState()
        LaunchedEffect(ed.index) {
            // the open file's folder unfolds
            ed.item?.id?.substringBeforeLast('/', "")?.let { d -> if (tree && hasDirs && d in folded) { folded = folded - d; ed.workspace.updateState { it.copy(folded = folded) } } }
        }
        LaunchedEffect(ed.index, rows) {
            val pos = rows.indexOfFirst { it.index == ed.index && it.item != null }
            if (pos >= 0 && (pos < state.firstVisibleItemIndex || pos > state.firstVisibleItemIndex + state.layoutInfo.visibleItemsInfo.size - 2)) {
                state.animateScrollToItem(maxOf(0, pos - 3))
            }
        }
        if (ed.items.isEmpty()) {
            Text(S.noFiles(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        } else if (list.isEmpty()) {
            Text(S.nothingFound(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            items(rows, key = { r -> r.item?.id ?: ("dir:" + r.dir) }) { r ->
                if (r.item == null) {
                    // a folder heading
                    val open = r.dir !in folded
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 44.dp else 28.dp).background(c.panelAlt)
                            .clickable { folded = if (open) folded + r.dir else folded - r.dir; ed.workspace.updateState { it.copy(folded = folded) } }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (open) Icons.down else Icons.right, null, Modifier.size(14.dp), tint = c.muted)
                        Spacer(Modifier.width(6.dp))
                        Text(r.dir.ifEmpty { Paths.name(ed.workspace.root) }.replace("/", " / "), color = c.text, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${r.done}/${r.count}", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                    return@items
                }
                val i = r.index
                val item = r.item
                val marks = ed.marks(item)
                val current = i == ed.index
                Box {
                MenuPopup(menuFor == item.id, onDismiss = { menuFor = null }, focusable = true) {
                    FileMenu(ed, i, item, onOpened, onRename = { ed.app.renamingFile = i }, onTrash = { ed.app.trashingFile = i }) { menuFor = null }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = if (Platform.isMobile) 52.dp else 34.dp)
                        .background(if (current) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else if (menuFor == item.id) c.panelAlt else c.panel)
                        .withContextMenu(onMenu = { menuFor = item.id }) { ed.open(i); onOpened() }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (current && c.square) c.onAccent else c.text
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when {
                                marks.done -> c.ok
                                item.labelPath != null -> c.muted.copy(alpha = 0.6f)
                                else -> c.muted.copy(alpha = 0.15f)
                            },
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Paths.name(item.id), color = fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = buildList {
                            val dir = item.id.substringBeforeLast('/', "")
                            if (dir.isNotEmpty() && !(tree && hasDirs)) add(dir)
                            if (marks.tag.isNotEmpty()) add("#" + marks.tag)
                        }
                        if (sub.isNotEmpty()) Text(sub.joinToString("  "), color = if (current && c.square) c.onAccent else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ed.labelTimes[item.id]?.let { t ->
                        Tip(savedAt.format(mlabeler.app.formatDateTime(t))) {
                            Text(ago(now - t), color = if (current && c.square) c.onAccent else c.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (marks.star) Icon(Icons.starOn, null, Modifier.size(14.dp), tint = c.warn)
                }
                }
            }
        }
        Divider()
        val done = ed.items.count { ed.marks(it).done }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // the count is already in the status bar when it shows it: here only the bar then
            val st = ed.app.settings
            val inStatus = st.statusBar && "done" in st.status.left + st.status.right
            if (!inStatus) Text(S.doneCount.format(done, ed.items.size), color = c.muted, fontSize = 12.sp)
            if (ed.items.isNotEmpty()) Box(Modifier.padding(top = if (inStatus) 0.dp else 5.dp).fillMaxWidth().height(4.dp).clip(mlabeler.app.theme.RoundedCornerShape(2.dp)).background(c.panelAlt)) {
                Box(Modifier.fillMaxWidth(done.toFloat() / ed.items.size).height(4.dp).background(c.ok))
            }
        }
    }
}

/** A row of the file list: a file ([item]) or, in the folder view, a folder heading ([dir] with its counts). */
private data class FileRow(val index: Int, val item: mlabeler.core.io.Item?, val dir: String = "", val count: Int = 0, val done: Int = 0)

private val treeT = L("Folders", "По папкам")
private val treeHint = L("Group the files by subfolder; a click on a folder folds it", "Сгруппировать файлы по подпапкам; щелчок по папке сворачивает её")
private val probLong = L("Too long", "Слишком длинная")
private val probLongPause = L("Pause too long", "Слишком длинная пауза")
private val probLongPhrase = L("Too long without a pause", "Слишком долго без паузы")
private val dropAdd = L("Drop to add to the folder {0}", "Отпустите — файлы добавятся в папку {0}")
private val agoNow = L("just now", "только что")
private val agoMin = L("{0} min ago", "{0} мин назад")
private val agoHour = L("{0} h ago", "{0} ч назад")
private val agoDay = L("{0} d ago", "{0} дн назад")
private val savedAt = L("Labels saved {0}", "Разметка сохранена {0}")

/** How long ago the labels were saved: "just now", "5 min ago", "3 h ago", "2 d ago". */
private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> agoNow()
        m < 60 -> agoMin.format(m)
        m < 48 * 60 -> agoHour.format(m / 60)
        else -> agoDay.format(m / 1440)
    }
}

private val searchHint = L("Search by name or phonemes", "Поиск по имени или фонемам")
private val queueTitle = L("Phonemes in advance", "Фонемы заранее")
private val queueHint = L("e.g. SP k a sh i SP", "например: SP k a sh i SP")
private val queueHelp = L("Type the phonemes, then each new boundary names its part with the next one.",
    "Впишите фонемы — каждая новая граница подпишет свою часть следующей из них.")
private val queueNext = L("Next: {0} ({1} left)", "Следующая: {0} (осталось {1})")
private val queueFill = L("Spread over the selection", "Расставить по выделенному")

@Composable
fun Inspector(ed: EditorState, modifier: Modifier = Modifier) {
    if (ed.mode == mlabeler.app.state.Mode.Oto) return OtoInspector(ed, modifier)
    val c = T.c
    val item = ed.item
    val doc = ed.doc
    val sel = ed.selection
    Column(modifier.background(c.panel).scrollWithHint().padding(horizontal = 14.dp, vertical = 4.dp)) {
        for (id in inspectorOrder(ed.app.settings.layout.inspectorOrder)) when (id) {
            "file" -> if (item != null) InspectorSection(ed, id, S.file()) {
            Text(Paths.name(item.audioPath), color = c.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val a = ed.audio
            if (a != null) Text("${formatTime(a.duration)} · ${a.sampleRate} Hz", color = c.muted, fontSize = 12.sp)
            KeyValue(S.labels(), item.labelPath?.let { Paths.name(it) } ?: S.notSavedYet())
            val marks = ed.marks(item)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.done(), marks.done) { ed.setMarks(item) { it.copy(done = !it.done) } }
                Chip(S.star(), marks.star) { ed.setMarks(item) { it.copy(star = !it.star) } }
            }
            var tag by remember(item.id) { mutableStateOf(marks.tag) }
            Field(
                tag, { tag = it; ed.setMarks(item) { m -> m.copy(tag = it.trim()) } },
                Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = S.tag(),
            )
        
            }
            "selection" -> InspectorSection(ed, id, when (sel) { is Selection.Bound -> S.boundary(); is Selection.Note -> S.note(); else -> S.interval() }) {
        when {
            doc == null -> Unit
            sel is Selection.Interval -> {
                val t = doc.tiers.getOrNull(sel.ref.tier) as? IntervalTier
                if (t != null && sel.ref.index < t.size) {
                    val i = sel.ref.index
                    var text by remember(sel, ed.docVersion) { mutableStateOf(t.texts[i]) }
                    Field(
                        text, { text = it }, Modifier.fillMaxWidth(), placeholder = S.text(),
                        onDone = { ed.setText(sel.ref, text.trim()) },
                    )
                    LaunchedEffect(text) {
                        kotlinx.coroutines.delay(600)
                        if (text.trim() != t.texts[i]) ed.setText(sel.ref, text.trim())
                    }
                    Spacer(Modifier.height(6.dp))
                    KeyValue(S.start(), formatTime(t.startOf(i)))
                    KeyValue(S.end(), formatTime(t.endOf(i)))
                    KeyValue(S.length(), formatMs(t.durationOf(i)))
                    t.confidenceOf(i)?.let { KeyValue(S.confidence(), "${(it * 100).toInt()}%") }
                }
            }
            sel is Selection.Bound -> {
                val t = doc.tiers.getOrNull(sel.ref.tier) as? IntervalTier
                if (t != null && sel.ref.bound < t.bounds.size) {
                    val time = t.bounds[sel.ref.bound]
                    var ms by remember(sel, ed.docVersion) { mutableStateOf(formatMsField(time)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(ms, { ms = it }, Modifier.weight(1f), onDone = {
                            ms.replace(',', '.').toDoubleOrNull()?.let { v -> ed.moveSelectedBound(v / 1000.0) }
                        })
                        Text("  ms", color = c.muted, fontSize = 13.sp)
                    }
                    KeyValue(S.time(), formatTime(time))
                    val linked = Edits.linkedBounds(doc, sel.ref)
                    if (linked.isNotEmpty()) KeyValue(S.linked(), linked.joinToString { doc.tiers[it.tier].name })
                }
            }
            sel is Selection.Note -> {
                val t = doc.tiers.getOrNull(sel.tier) as? mlabeler.core.model.NoteTier
                val n = t?.notes?.getOrNull(sel.index)
                if (n != null) {
                    var name by remember(sel, ed.docVersion) { mutableStateOf(mlabeler.core.format.NoteNames.format(n.pitch)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(name, { name = it }, Modifier.weight(1f), onDone = {
                            val v = name.trim()
                            if (v.equals("rest", true) || v.isEmpty()) ed.changeNote { tt, i -> mlabeler.core.edit.NoteEdits.setPitch(tt, i, null) }
                            else mlabeler.core.format.NoteNames.parse(v)?.let { p -> ed.changeNote { tt, i -> mlabeler.core.edit.NoteEdits.setPitch(tt, i, p) } }
                        })
                        Spacer(Modifier.width(6.dp))
                        IconBtn(Icons.down, "-1", size = 30.dp) { ed.nudgePitch(-1.0) }
                        IconBtn(Icons.up, "+1", size = 30.dp) { ed.nudgePitch(1.0) }
                    }
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(S.slur(), n.slur) { ed.changeNote { tt, i -> mlabeler.core.edit.NoteEdits.setSlur(tt, i, !n.slur) } }
                    }
                    KeyValue(S.start(), formatTime(n.start))
                    KeyValue(S.end(), formatTime(n.end))
                    KeyValue(S.length(), formatMs(n.end - n.start))
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn(S.pitchFromAudioOne()) { ed.notePitchFromAudio(all = false) }
                        Btn(S.pitchFromAudioAll()) { ed.notePitchFromAudio(all = true) }
                    }
                }
            }
            else -> Text(S.nothingSelected(), color = c.muted, fontSize = 13.sp)
        }

            }
            "queue" -> if (doc != null) InspectorSection(ed, id, queueTitle()) {
            var queue by remember(ed.item?.id) { mutableStateOf(ed.phonemeQueue.joinToString(" ")) }
            // the field follows the queue as boundaries use it up
            LaunchedEffect(ed.phonemeQueue) {
                if (queue.split(Regex("[\\s,]+")).filter { it.isNotEmpty() } != ed.phonemeQueue) queue = ed.phonemeQueue.joinToString(" ")
            }
            Field(queue, { queue = it; ed.setQueueText(it) }, Modifier.fillMaxWidth(), placeholder = queueHint())
            val next = ed.phonemeQueue.firstOrNull()
            Text(if (next != null) queueNext.format(next, ed.phonemeQueue.size) else queueHelp(), color = c.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            if (next != null) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(queueFill(), modifier = Modifier.weight(1f)) { ed.fillWithQueue() }
                Btn(S.clear()) { ed.phonemeQueue = emptyList() }
            }
            }
            "notes" -> if (doc != null) InspectorSection(ed, id, S.notes()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(Commands.groupPhonemes.title(), modifier = Modifier.fillMaxWidth()) { ed.groupPhonemes() }
                Btn(Commands.notesFromGroups.title(), modifier = Modifier.fillMaxWidth()) { ed.notesFromGroups() }
            }
        
            }
            "tiers" -> if (doc != null) InspectorSection(ed, id, S.tiers(), trailing = {
                IconBtn(Icons.plus, S.addTier(), size = 26.dp) {
                    ed.updateDoc { Edits.addTier(it, S.newTierName.format(it.tiers.size + 1), ed.duration) }
                }
            }) {
            for ((k, tier) in doc.tiers.withIndex()) {
                TierRow(ed, k, tier.name, k == ed.activeTier, k, doc.tiers.size)
            }
            }
            "compare" -> if (doc != null) InspectorSection(ed, id, compareTitle(), trailing = {
                IconBtn(Icons.plus, addFolder(), size = 26.dp) { ed.app.pickFolder(addFolder()) { ed.addCompareFolder(it) } }
            }) { CompareSection(ed) }
            "problems" -> if (doc != null) InspectorSection(ed, id, S.problems()) {
            if (ed.problems.isEmpty()) {
                Text(S.noProblems(), color = c.muted, fontSize = 13.sp)
            } else {
                for (p in ed.problems.take(200)) {
                    val t = doc.tiers.getOrNull(p.ref.tier) as? IntervalTier ?: continue
                    if (p.ref.index >= t.size) continue
                    Row(
                        Modifier.fillMaxWidth().clickable { ed.selectInterval(p.ref) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.warn, null, Modifier.size(14.dp), tint = if (p.severity == mlabeler.core.check.Severity.Error) c.danger else c.warn)
                        Spacer(Modifier.width(8.dp))
                        Text(problemTitle(p), fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
                        Text(
                            "${t.texts[p.ref.index].ifEmpty { "∅" }} · ${formatTime(t.startOf(p.ref.index))}",
                            fontSize = 12.sp, color = c.muted, maxLines = 1,
                        )
                    }
                }
            }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Sections of the details panel, in their default order. */
val INSPECTOR_SECTIONS = listOf("file", "selection", "queue", "notes", "tiers", "compare", "problems")

/** The user's order with sections it lacks (new ones) added at their default place. */
fun inspectorOrder(saved: List<String>): List<String> {
    val out = saved.filter { it in INSPECTOR_SECTIONS }.distinct().toMutableList()
    for ((k, id) in INSPECTOR_SECTIONS.withIndex()) if (id !in out) out.add(k.coerceAtMost(out.size), id)
    return out
}

/**
 * A section of the details panel: its title folds it; while panels are being arranged (View → Panels) arrows
 * move it up and down. Both are remembered.
 */
@Composable
private fun InspectorSection(
    ed: EditorState, id: String, title: String,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = T.c
    val app = ed.app
    val l = app.settings.layout
    val folded = id in l.inspectorFolded
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(c.radius))
                .clickable { app.update { st -> st.copy(layout = st.layout.copy(inspectorFolded = if (folded) st.layout.inspectorFolded - id else st.layout.inspectorFolded + id)) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (folded) "▸" else "▾", color = c.muted, fontSize = 11.sp, modifier = Modifier.width(14.dp))
            Text(if (c.square) title.uppercase() else title, color = c.muted, fontSize = 11.sp, letterSpacing = if (c.square) 1.sp else 0.3.sp)
        }
        if (app.arrangePanels) {
            fun move(step: Int) = app.update { st ->
                val o = inspectorOrder(st.layout.inspectorOrder).toMutableList()
                val i = o.indexOf(id)
                val j = (i + step).coerceIn(0, o.size - 1)
                o.removeAt(i); o.add(j, id)
                st.copy(layout = st.layout.copy(inspectorOrder = o))
            }
            IconBtn(Icons.up, sectionUp(), size = 24.dp) { move(-1) }
            IconBtn(Icons.down, sectionDown(), size = 24.dp) { move(1) }
        }
        if (!folded) trailing()
    }
    if (!folded) Column { content() }
}

private val sectionUp = mlabeler.app.i18n.L("Move up", "Выше")
private val sectionDown = mlabeler.app.i18n.L("Move down", "Ниже")

private fun formatMsField(seconds: Double): String {
    val v = kotlin.math.round(seconds * 1000 * 10) / 10.0
    return if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
}

private val probEdgeStart = mlabeler.app.i18n.L("No pause at the start", "Нет паузы в начале")
private val probEdgeEnd = mlabeler.app.i18n.L("No pause at the end", "Нет паузы в конце")

@Composable
private fun problemTitle(p: Problem) = when (p.kind) {
    Problem.Kind.Short -> S.probShort() + " (${p.detail})"
    Problem.Kind.Empty -> S.probEmpty()
    Problem.Kind.UnknownPhoneme -> S.probUnknown()
    Problem.Kind.LowConfidence -> S.probConfidence() + " (${p.detail})"
    Problem.Kind.NoPauseAtEdge -> (if (p.ref.index == 0) probEdgeStart() else probEdgeEnd()) + if (p.detail.isNotEmpty()) " (${p.detail})" else ""
    Problem.Kind.Long -> probLong() + " (${p.detail})"
    Problem.Kind.LongPause -> probLongPause() + " (${p.detail})"
    Problem.Kind.LongPhrase -> probLongPhrase() + " (${p.detail})"
    Problem.Kind.Script -> p.detail
    Problem.Kind.ZeroLength -> probZero()
    Problem.Kind.BelowFrame -> probFrame() + " (${p.detail})"
    Problem.Kind.SpaceInPhoneme -> probSpace() + " «${p.detail}»"
    Problem.Kind.TwoPauses -> probTwoPauses() + " (${p.detail})"
    Problem.Kind.NotesLength -> probNotes() + " (${p.detail})"
}

private val probZero = L("Zero length", "Нулевая длина")
private val probFrame = L("Shorter than one DiffSinger frame", "Короче одного кадра DiffSinger")
private val probSpace = L("Space inside the phoneme", "Пробел внутри фонемы")
private val probNotes = L("Notes don't last as long as the phonemes of this sentence", "Ноты не совпадают по длине с фонемами этого предложения")
private val probTwoPauses = L("Two same pauses in a row", "Две одинаковые паузы подряд")

@Composable
private fun TierRow(ed: EditorState, k: Int, name: String, active: Boolean, index: Int, count: Int) {
    val c = T.c
    var editing by remember { mutableStateOf(false) }
    var value by remember(name) { mutableStateOf(name) }
    Row(
        Modifier.fillMaxWidth().clip(mlabeler.app.theme.RoundedCornerShape(c.radius))
            .background(if (active) c.panelAlt else c.panel)
            .clickable { ed.activeTier = k }.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 18.dp).background(c.tierColors[k % c.tierColors.size]))
        Spacer(Modifier.width(8.dp))
        if (editing) {
            Field(value, { value = it }, Modifier.weight(1f), onDone = {
                editing = false
                if (value.isNotBlank()) ed.updateDoc { Edits.renameTier(it, k, value.trim()) }
            })
        } else {
            Text(name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val small = if (Platform.isMobile) 36.dp else 24.dp
        IconBtn(Icons.edit, S.renameTier(), size = small) { editing = !editing }
        IconBtn(Icons.up, S.moveUp(), enabled = index > 0, size = small) { ed.updateDoc { Edits.moveTier(it, k, k - 1) }; ed.activeTier = k - 1 }
        IconBtn(Icons.down, S.moveDown(), enabled = index < count - 1, size = small) { ed.updateDoc { Edits.moveTier(it, k, k + 1) }; ed.activeTier = k + 1 }
        IconBtn(Icons.trash, S.deleteTier(), enabled = count > 1, size = small) { ed.updateDoc { Edits.removeTier(it, k) } }
    }
}

@Suppress("unused")
private fun refText(ed: EditorState, r: IntervalRef) = (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.texts?.getOrNull(r.index)

@Composable
fun EmptyNote(text: String) {
    val c = T.c
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = c.muted, fontSize = 14.sp)
    }
}

private val compareTitle = mlabeler.app.i18n.L("Compare", "Сравнение")
private val compareHint = mlabeler.app.i18n.L(
    "Show labels of the same files from another folder (another model, another person) under these, with the differences marked.",
    "Показать разметку тех же файлов из другой папки (другая модель, другой человек) под этой и отметить различия.")
private val addFolder = mlabeler.app.i18n.L("Add folder…", "Добавить папку…")
private val useThese = mlabeler.app.i18n.L("Use these labels", "Использовать эту разметку")
private val showRef = mlabeler.app.i18n.L("Show under the labels", "Показать под разметкой")
private val hideRef = mlabeler.app.i18n.L("Hide (stays in this list)", "Скрыть (останется в этом списке)")
private val noMatch = mlabeler.app.i18n.L("no labels for this file", "для этого файла разметки нет")
private val statsLine = mlabeler.app.i18n.L("{0} ms average, {1} ms median, {2}% under 20 ms, {3} other texts",
    "в среднем {0} мс, медиана {1} мс, {2}% ближе 20 мс, другой текст: {3}")

@Composable
private fun CompareSection(ed: EditorState) {
    val c = T.c
    val doc = ed.doc ?: return
    // compare folders (each may have labels for this file) and results of autolabel kept for comparison
    val entries: List<Pair<String, EditorState.Reference?>> = ed.workspace.state.compareFolders.map { dir -> dir to ed.references.firstOrNull { it.folder == dir } } +
        ed.modelReferences.map { "" to it }
    if (entries.isEmpty()) {
        Text(compareHint(), color = c.muted, fontSize = 12.sp)
        return
    }
    for ((dir, r) in entries) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = if (dir.isEmpty()) r!!.name + (r.range?.let { (a, b) -> "  ${formatTime(a)}–${formatTime(b)}" } ?: "") else Paths.name(dir)
                val hidden = r != null && ed.isHidden(r)
                Text(name, color = if (hidden) c.muted else c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r != null) IconBtn(if (hidden) Icons.eyeOff else Icons.eye, if (hidden) showRef() else hideRef(), size = 24.dp) { ed.setHidden(r, !hidden) }
                IconBtn(Icons.close, S.removeFromList(), size = 24.dp) {
                    if (dir.isEmpty()) r?.let { ed.dropModelResult(it) } else ed.removeCompareFolder(dir)
                }
            }
            if (r == null) {
                Text(noMatch(), color = c.muted, fontSize = 12.sp)
            } else {
                for (t in r.doc.tiers.filterIsInstance<IntervalTier>()) {
                    val main = mlabeler.core.check.Compare.counterpart(doc, t) ?: continue
                    val st = mlabeler.core.check.Compare.stats(main, t)
                    Text(
                        t.name + ": " + statsLine.format(
                            kotlin.math.round(st.meanMs * 10) / 10, kotlin.math.round(st.medianMs * 10) / 10,
                            kotlin.math.round(st.within20 * 100).toInt(), st.textMismatches,
                        ),
                        color = c.muted, fontSize = 12.sp,
                    )
                }
                Btn(useThese(), modifier = Modifier.padding(top = 4.dp)) { ed.takeReference(r) }
            }
        }
    }
}
