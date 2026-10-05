package mlabeler.app.ui

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
import mlabeler.app.state.EditorState
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
    Column(modifier.background(c.panel)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
            Field(ed.query, { ed.query = it }, Modifier.fillMaxWidth(), placeholder = S.search())
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(S.all(), ed.filter == FileFilter.All) { ed.filter = FileFilter.All }
                Chip(S.notDone(), ed.filter == FileFilter.NotDone) { ed.filter = FileFilter.NotDone }
                Chip(S.starred(), ed.filter == FileFilter.Starred) { ed.filter = FileFilter.Starred }
                Chip(S.noLabels(), ed.filter == FileFilter.NoLabels) { ed.filter = FileFilter.NoLabels }
            }
        }
        val list = ed.filtered()
        val state = rememberLazyListState()
        LaunchedEffect(ed.index) {
            val pos = list.indexOfFirst { it.first == ed.index }
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
            items(list, key = { it.second.id }) { (i, item) ->
                val marks = ed.marks(item)
                val current = i == ed.index
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = if (Platform.isMobile) 52.dp else 34.dp)
                        .background(if (current) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                        .clickable { ed.open(i); onOpened() }
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
                            if (dir.isNotEmpty()) add(dir)
                            if (marks.tag.isNotEmpty()) add("#" + marks.tag)
                        }
                        if (sub.isNotEmpty()) Text(sub.joinToString("  "), color = if (current && c.square) c.onAccent else c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (marks.star) Icon(Icons.starOn, null, Modifier.size(14.dp), tint = c.warn)
                }
            }
        }
        Divider()
        val done = ed.items.count { ed.marks(it).done }
        Text(
            S.doneCount.format(done, ed.items.size), color = c.muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun Inspector(ed: EditorState, modifier: Modifier = Modifier) {
    if (ed.mode == mlabeler.app.state.Mode.Oto) return OtoInspector(ed, modifier)
    val c = T.c
    val item = ed.item
    val doc = ed.doc
    Column(modifier.background(c.panel).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp)) {
        if (item != null) {
            SectionTitle(S.file())
            Text(Paths.name(item.audioPath), color = c.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val a = ed.audio
            if (a != null) Text("${formatTime(a.duration)} · ${a.sampleRate} Hz", color = c.muted, fontSize = 12.sp)
            KeyValue(S.labels(), item.labelPath?.let { Paths.name(it) } ?: S.notSavedYet())
            val marks = ed.marks(item)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(S.done(), marks.done) { ed.setMarks(item) { it.copy(done = !it.done) } }
                Chip(S.star(), marks.star) { ed.setMarks(item) { it.copy(star = !it.star) } }
            }
            var tag by remember(item.id) { mutableStateOf(marks.tag) }
            Field(
                tag, { tag = it; ed.setMarks(item) { m -> m.copy(tag = it.trim()) } },
                Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = S.tag(),
            )
        }

        val sel = ed.selection
        SectionTitle(if (sel is Selection.Bound) S.boundary() else S.interval())
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
            else -> Text(S.nothingSelected(), color = c.muted, fontSize = 13.sp)
        }

        if (doc != null) {
            SectionTitle(S.tiers()) {
                IconBtn(Icons.plus, S.addTier(), size = 26.dp) {
                    ed.updateDoc { Edits.addTier(it, S.newTierName.format(it.tiers.size + 1), ed.duration) }
                }
            }
            for ((k, tier) in doc.tiers.withIndex()) {
                TierRow(ed, k, tier.name, k == ed.activeTier, k, doc.tiers.size)
            }
            SectionTitle(S.problems())
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
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun formatMsField(seconds: Double): String {
    val v = kotlin.math.round(seconds * 1000 * 10) / 10.0
    return if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
}

@Composable
private fun problemTitle(p: Problem) = when (p.kind) {
    Problem.Kind.Short -> S.probShort() + " (${p.detail})"
    Problem.Kind.Empty -> S.probEmpty()
    Problem.Kind.UnknownPhoneme -> S.probUnknown()
    Problem.Kind.LowConfidence -> S.probConfidence() + " (${p.detail})"
    Problem.Kind.NoPauseAtEdge -> S.probEdge()
}

@Composable
private fun TierRow(ed: EditorState, k: Int, name: String, active: Boolean, index: Int, count: Int) {
    val c = T.c
    var editing by remember { mutableStateOf(false) }
    var value by remember(name) { mutableStateOf(name) }
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(c.radius))
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
