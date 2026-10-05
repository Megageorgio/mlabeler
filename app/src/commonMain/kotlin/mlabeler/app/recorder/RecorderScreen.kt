package mlabeler.app.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.ui.Btn
import mlabeler.app.ui.Chip
import mlabeler.app.ui.Divider
import mlabeler.app.ui.Field
import mlabeler.app.ui.IconBtn
import mlabeler.app.ui.Icons
import mlabeler.app.ui.Overlay
import mlabeler.app.ui.SectionTitle
import mlabeler.app.ui.formatTime
import mlabeler.core.io.Paths
import mlabeler.core.oto.Kana
import mlabeler.core.oto.Syllables
import kotlin.math.max
import kotlin.math.min

val recordTitle = L("Record", "Запись")
private val emptyList = L("No list yet. Add the names to record (one per line), they are saved as reclist.txt.",
    "Списка пока нет. Добавьте имена для записи (по одному в строке), они сохранятся в reclist.txt.")
private val editList = L("Edit list", "Изменить список")
private val recordKey = L("Record / stop", "Запись / стоп")
private val playTake = L("Play the take", "Прослушать дубль")
private val notRecorded = L("not recorded yet", "ещё не записано")
private val recordedCount = L("{0} of {1} recorded", "записано {0} из {1}")
private val clickBpm = L("Click, BPM (0 = off)", "Метроном, BPM (0 — выкл.)")
private val countInT = L("Count-in beats", "Тактов отсчёта")
private val guideT = L("Guide WAV in the folder (empty = none)", "Направляющий WAV в папке (пусто — нет)")
private val autoNextT = L("Next line after a take", "Следующая строка после дубля")
private val keysHint = L("R or the red button records, Space plays the take, ↑ ↓ change the line.",
    "R или красная кнопка — запись, пробел — прослушать, ↑ ↓ — другая строка.")
private val openInEditor = L("Open in the editor", "Открыть в редакторе")

@Composable
fun RecorderScreen(app: AppState, rec: RecorderState) {
    val c = T.c
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(rec) {
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || editing || showSettings) return@onKeyEvent false
                when (e.key) {
                    Key.R -> { rec.toggle(); true }
                    Key.Spacebar -> { rec.playTake(); true }
                    Key.DirectionDown -> { rec.step(1); true }
                    Key.DirectionUp -> { rec.step(-1); true }
                    Key.Escape -> { if (rec.recording) rec.stop(); true }
                    else -> false
                }
            },
    ) {
        val wide = maxWidth > 760.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).background(c.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.back, S.back()) { app.closeRecorder() }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(recordTitle(), color = c.text, fontSize = 15.sp)
                    Text(Paths.name(rec.folder), color = c.muted, fontSize = 11.sp, maxLines = 1)
                }
                Btn(editList()) { editing = true }
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.settings, S.settings()) { showSettings = true }
                IconBtn(Icons.file, openInEditor()) { val f = rec.folder; app.closeRecorder(); app.openFolder(f) }
            }
            Divider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    RecList(rec, Modifier.width(280.dp).fillMaxHeight())
                    Divider(vertical = true)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (rec.names.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(emptyList(), color = c.muted, fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                                Btn(editList(), primary = true) { editing = true }
                            }
                        }
                    } else {
                        Current(rec, Modifier.weight(1f).fillMaxWidth())
                        if (!wide) {
                            Divider()
                            RecList(rec, Modifier.height(200.dp).fillMaxWidth())
                        }
                    }
                }
            }
        }
        mlabeler.app.ui.MessageToast(app, 24.dp)
        if (editing) ListEditor(rec) { editing = false; focus.requestFocus() }
        if (showSettings) RecSettings(rec) { showSettings = false; focus.requestFocus() }
    }
}

@Composable
private fun RecList(rec: RecorderState, modifier: Modifier) {
    val c = T.c
    val state = rememberLazyListState()
    LaunchedEffect(rec.index) {
        if (rec.index < state.firstVisibleItemIndex || rec.index > state.firstVisibleItemIndex + state.layoutInfo.visibleItemsInfo.size - 2) {
            state.scrollToItem(max(0, rec.index - 3))
        }
    }
    Column(modifier.background(c.panel)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            itemsIndexed(rec.names) { i, n ->
                val sel = i == rec.index
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 44.dp else 30.dp)
                        .background(if (sel) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                        .clickable { rec.select(i) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (rec.isRecorded(n)) c.ok else c.muted.copy(alpha = 0.2f)))
                    Spacer(Modifier.width(10.dp))
                    Text(n, color = if (sel && c.square) c.onAccent else c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Divider()
        Text(recordedCount.format(rec.names.count { rec.isRecorded(it) }, rec.names.size), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Current(rec: RecorderState, modifier: Modifier) {
    val c = T.c
    val name = rec.current ?: return
    Column(modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.4f))
        Text(name.trimStart('_'), color = c.text, fontSize = if (name.length > 12) 34.sp else 52.sp, maxLines = 2)
        // romaji under kana, comment from the list
        val sub = buildList {
            if (name.any { Kana.isKana(it) }) add(Syllables.fromName(name).joinToString(" ") { s -> Kana.romaji(s.text) ?: s.text })
            rec.comments[name]?.let { add(it) }
        }
        if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), color = c.muted, fontSize = 16.sp)
        Spacer(Modifier.height(18.dp))
        // level meter
        Box(Modifier.fillMaxWidth(0.6f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.panelAlt)) {
            val lv = min(1f, rec.level * 4f)
            Box(Modifier.fillMaxWidth(lv).height(8.dp).background(if (lv > 0.9f) c.danger else c.ok))
        }
        Spacer(Modifier.height(14.dp))
        val take = rec.take
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(c.radius)).background(c.laneBg).border(c.borderWidth, c.border, RoundedCornerShape(c.radius))) {
            if (rec.countIn > 0) {
                Text(rec.countIn.toString(), color = c.accent, fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
            } else if (take != null && !rec.recording) {
                val wave = c.wave
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width.toInt().coerceAtLeast(1)
                    val mid = size.height / 2
                    val path = Path()
                    val per = max(1, take.samples.size / w)
                    for (x in 0 until w) {
                        var lo = 0f; var hi = 0f
                        val a = x * per
                        for (i in a until min(take.samples.size, a + per)) { val v = take.samples[i]; if (v < lo) lo = v; if (v > hi) hi = v }
                        path.moveTo(x.toFloat(), mid - hi * mid)
                        path.lineTo(x.toFloat(), mid - lo * mid + 0.5f)
                    }
                    drawPath(path, wave, style = Stroke(1f))
                }
                Text(formatTime(take.duration), color = c.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            } else if (!rec.recording) {
                Text(notRecorded(), color = c.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBtn(Icons.up, S.prevFile(), size = 48.dp) { rec.step(-1) }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(if (rec.recording) c.danger else c.danger.copy(alpha = 0.85f))
                    .clickable { rec.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (rec.recording) 24.dp else 30.dp).clip(if (rec.recording) RoundedCornerShape(4.dp) else CircleShape).background(Color.White))
            }
            IconBtn(Icons.play, playTake(), size = 48.dp, enabled = take != null) { rec.playTake() }
            IconBtn(Icons.down, S.nextFile(), size = 48.dp) { rec.step(1) }
        }
        Spacer(Modifier.height(10.dp))
        Text(keysHint(), color = c.muted, fontSize = 12.sp)
        Spacer(Modifier.weight(0.6f))
    }
}

@Composable
private fun ListEditor(rec: RecorderState, onClose: () -> Unit) {
    val c = T.c
    var text by remember { mutableStateOf(rec.listText()) }
    Overlay(onClose, 560) {
        Column(Modifier.padding(18.dp)) {
            Text(editList(), color = c.text, fontSize = 17.sp)
            Text(emptyList(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            BasicTextField(
                text, { text = it }, textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).clip(RoundedCornerShape(c.radius))
                    .background(c.bg).border(c.borderWidth, c.border, RoundedCornerShape(c.radius)).padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { onClose() }
                Btn(S.save(), primary = true) { rec.saveList(text); onClose() }
            }
        }
    }
}

@Composable
private fun RecSettings(rec: RecorderState, onClose: () -> Unit) {
    val c = T.c
    val s = rec.settings
    Overlay(onClose, 480) {
        Column(Modifier.padding(18.dp)) {
            Text(S.settings(), color = c.text, fontSize = 17.sp)
            SectionTitle(clickBpm())
            var bpm by remember { mutableStateOf(s.bpm.toString()) }
            Field(bpm, { bpm = it; it.toIntOrNull()?.let { v -> rec.updateSettings { st -> st.copy(bpm = v.coerceIn(0, 400)) } } }, Modifier.width(120.dp))
            SectionTitle(countInT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in listOf(0, 2, 4, 8)) Chip(n.toString(), s.countInBeats == n) { rec.updateSettings { st -> st.copy(countInBeats = n) } }
            }
            SectionTitle(guideT())
            var guide by remember { mutableStateOf(s.guide) }
            Field(guide, { guide = it; rec.updateSettings { st -> st.copy(guide = it.trim()) } }, Modifier.fillMaxWidth())
            SectionTitle(autoNextT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.ok(), s.autoNext) { rec.updateSettings { st -> st.copy(autoNext = true) } }
                Chip("—", !s.autoNext) { rec.updateSettings { st -> st.copy(autoNext = false) } }
            }
            SectionTitle("Hz")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in listOf(44100, 48000)) Chip(r.toString(), s.sampleRate == r) { rec.updateSettings { st -> st.copy(sampleRate = r) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) { Btn(S.close()) { onClose() } }
        }
    }
}
