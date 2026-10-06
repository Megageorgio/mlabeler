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
import mlabeler.core.dsp.Pitch
import mlabeler.core.format.NoteNames
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.log10
import kotlin.math.pow
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
private val keysHint = L("R or the red button records, Space plays the take from the line, the Up and Down arrows change the item.",
    "R или красная кнопка — запись, пробел — прослушать с линии, стрелки вверх и вниз — другая строка.")
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
    val compact = Platform.isMobile
    Column(modifier.padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name.trimStart('_'), color = c.text, fontSize = if (name.length > 12) 30.sp else 44.sp, maxLines = 2)
        // romaji under kana, comment from the list
        val sub = buildList {
            if (name.any { Kana.isKana(it) }) add(Syllables.fromName(name).joinToString(" ") { s -> Kana.romaji(s.text) ?: s.text })
            rec.comments[name]?.let { add(it) }
        }
        if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), color = c.muted, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Readout(rec)
        Spacer(Modifier.height(10.dp))
        TakeView(rec, Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp))
        TakeInfo(rec)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBtn(Icons.up, S.prevFile(), size = 48.dp) { rec.step(-1) }
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(if (rec.recording) c.danger else c.danger.copy(alpha = 0.85f))
                    .clickable { rec.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (rec.recording) 22.dp else 28.dp).clip(if (rec.recording) RoundedCornerShape(4.dp) else CircleShape).background(Color.White))
            }
            IconBtn(if (rec.playhead >= 0) Icons.stop else Icons.play, playTake(), size = 48.dp, enabled = rec.take != null && !rec.recording) { rec.playTake() }
            IconBtn(Icons.down, S.nextFile(), size = 48.dp) { rec.step(1) }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text(keysHint(), color = c.muted, fontSize = 12.sp)
        }
    }
}

private val noVoice = L("—", "—")
private val takeNote = L("take: {0}", "дубль: {0}")
private val clipped = L("too loud: the take clips", "слишком громко: дубль перегружен")
private val quiet = L("very quiet take", "очень тихий дубль")
private val takeHint = L("Click to play from there, drag to play a part", "Щелчок — играть отсюда, протяжка — играть кусок")

/** Note name without cents for a MIDI value. */
private fun noteName(midi: Double) = NoteNames.format(kotlin.math.round(midi))
private fun midiToHz(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

/** Median voiced pitch of the take, MIDI; null when nothing is voiced. */
private fun medianNote(curve: mlabeler.core.dsp.Curve?): Double? {
    val v = curve?.values?.filter { it > 0f }?.sorted() ?: return null
    if (v.size < 5) return null
    return Pitch.hzToMidi(v[v.size / 2].toDouble())
}

/** Level meter and the pitch heard now (or the take's typical pitch). */
@Composable
private fun Readout(rec: RecorderState) {
    val c = T.c
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        // level
        Box(Modifier.width(160.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.panelAlt)) {
            val db = if (rec.level > 0f) 20 * log10(rec.level.toDouble()) else -90.0
            val lv = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(lv).height(10.dp).background(if (lv > 0.92f) c.danger else if (lv > 0.75f) c.warn else c.ok))
        }
        Spacer(Modifier.width(20.dp))
        val live = rec.recording
        val note: Double? = if (live) rec.liveNote.takeIf { it > 0f }?.toDouble() else medianNote(rec.takePitch)
        Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
            Text(note?.let { noteName(it) } ?: noVoice(), color = if (live && note != null) c.accent else c.text, fontSize = 30.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(110.dp)) {
            if (note != null) {
                val cents = kotlin.math.round((note - kotlin.math.round(note)) * 100).toInt()
                Text("${kotlin.math.round(midiToHz(note)).toInt()} Hz", color = c.text, fontSize = 14.sp)
                Text((if (cents >= 0) "+" else "") + "$cents ¢", color = if (kotlin.math.abs(cents) <= 15) c.ok else c.muted, fontSize = 12.sp)
            } else Text(if (live) "" else " ", color = c.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TakeInfo(rec: RecorderState) {
    val c = T.c
    val take = rec.take
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (take != null && !rec.recording) {
            val peak = remember(take) { var p = 0f; for (v in take.samples) { val a = kotlin.math.abs(v); if (a > p) p = a }; p }
            Text(takeHint(), color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                peak >= 0.99f -> Text(clipped(), color = c.danger, fontSize = 11.sp)
                peak < 0.06f -> Text(quiet(), color = c.warn, fontSize = 11.sp)
            }
        } else Spacer(Modifier.height(14.dp))
    }
}

/**
 * The take (or the live input while recording): waveform on top, pitch on a piano-roll grid below.
 * Click plays from the click, drag plays the dragged part.
 */
@Composable
private fun TakeView(rec: RecorderState, modifier: Modifier) {
    val c = T.c
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val take = rec.take
    val shape = RoundedCornerShape(c.radius)
    var dragFrom by remember { mutableStateOf(-1.0) }
    var dragTo by remember { mutableStateOf(-1.0) }
    // the take's peaks per pixel are cached by width
    var peaksKey by remember { mutableStateOf<Pair<Any?, Int>?>(null) }
    var peaks by remember { mutableStateOf(FloatArray(0) to FloatArray(0)) }
    Box(modifier.clip(shape).background(c.laneBg).border(c.borderWidth, c.border, shape)) {
        val live = rec.recording
        val frames = rec.liveFrames
        val canvasMod = if (take != null && !live) Modifier.fillMaxSize().pointerInput(take) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val dur = take.duration
                fun timeAt(x: Float) = (x / size.width * dur).coerceIn(0.0, dur)
                val t0 = timeAt(down.position.x)
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    if (kotlin.math.abs(ch.position.x - down.position.x) > 6f) moved = true
                    if (moved) { dragFrom = t0; dragTo = timeAt(ch.position.x) }
                    ch.consume()
                }
                if (moved && dragFrom >= 0) {
                    val a = min(dragFrom, dragTo); val b = max(dragFrom, dragTo)
                    rec.cursor = a
                    rec.playRange(a, b)
                } else {
                    rec.cursor = t0
                    rec.playRange(t0, dur)
                }
                dragFrom = -1.0; dragTo = -1.0
            }
        } else Modifier.fillMaxSize()
        val labelStyle = TextStyle(color = c.muted, fontSize = 10.sp)
        Canvas(canvasMod) {
            val w = size.width
            val h = size.height
            val waveH = h * 0.34f
            val pitchTop = waveH
            val pitchH = h - waveH
            drawLine(c.border, Offset(0f, waveH), Offset(w, waveH), 1f)
            // what to show: the take, or the last seconds of the input
            val step = RecorderState.LIVE_STEP
            val span: Double
            val start: Double
            if (live) {
                val rec_s = frames * step
                span = 8.0
                start = max(0.0, rec_s - span * 0.9)
            } else {
                span = take?.duration ?: 1.0
                start = 0.0
            }
            fun xOf(t: Double) = ((t - start) / span * w).toFloat()
            // pitch range: around the typical note, at least an octave and a half
            val voiced: List<Double> = if (live) {
                val from = max(0, frames - (span / step).toInt())
                (from until frames).mapNotNull { i -> rec.livePitch.getOrNull(i)?.takeIf { it > 0f }?.toDouble() }
            } else (rec.takePitch?.values?.filter { it > 0f }?.map { Pitch.hzToMidi(it.toDouble()) } ?: emptyList<Double>())
            val sorted = voiced.sorted()
            val mid = if (sorted.size >= 5) sorted[sorted.size / 2] else 60.0
            var lo = if (sorted.size >= 5) min(sorted[sorted.size / 20], mid - 9) else mid - 9
            var hi = if (sorted.size >= 5) max(sorted[sorted.size * 19 / 20], mid + 9) else mid + 9
            lo = kotlin.math.floor(lo) - 1; hi = kotlin.math.ceil(hi) + 1
            val semis = (hi - lo).coerceAtLeast(1.0)
            fun yOf(m: Double) = (pitchTop + pitchH - (m - lo) / semis * pitchH).toFloat()
            val rowH = pitchH / semis.toFloat()
            // piano-roll rows: dark rows for the black keys, a line under every C
            for (k in lo.toInt() until hi.toInt()) {
                val pc = ((k % 12) + 12) % 12
                val yTop = yOf(k + 0.5)
                if (pc in setOf(1, 3, 6, 8, 10)) drawRect(c.text.copy(alpha = 0.045f), Offset(0f, yTop), Size(w, rowH))
                if (pc == 0) drawLine(c.text.copy(alpha = 0.16f), Offset(0f, yOf(k - 0.5)), Offset(w, yOf(k - 0.5)), 1f)
                if (pc == 0 || (rowH >= 13f && pc in setOf(4, 7, 9))) {
                    val lay = measurer.measure(NoteNames.format(k.toDouble()), labelStyle)
                    if (yTop + rowH / 2 - lay.size.height / 2 > pitchTop) drawText(lay, topLeft = Offset(4f, yOf(k.toDouble()) - lay.size.height / 2))
                }
            }
            // waveform
            val midY = waveH / 2
            val wave = c.wave
            if (live) {
                val path = Path()
                val px = w / (span / step).toFloat()
                val from = max(0, (start / step).toInt())
                for (i in from until frames) {
                    val x = xOf(i * step)
                    val v = rec.liveWave.getOrElse(i) { 0f }.coerceAtMost(1f) * (midY - 2)
                    path.moveTo(x, midY - v); path.lineTo(x, midY + v + 0.5f)
                }
                drawPath(path, wave, style = Stroke(max(1f, px)))
            } else if (take != null) {
                val wi = w.toInt().coerceAtLeast(1)
                if (peaksKey != (take to wi)) {
                    val lo2 = FloatArray(wi); val hi2 = FloatArray(wi)
                    val n = take.samples.size
                    for (x in 0 until wi) {
                        val a = (x.toLong() * n / wi).toInt(); val b = max(a + 1, ((x + 1).toLong() * n / wi).toInt())
                        var l = 0f; var hh = 0f
                        for (i in a until min(n, b)) { val v = take.samples[i]; if (v < l) l = v; if (v > hh) hh = v }
                        lo2[x] = l; hi2[x] = hh
                    }
                    peaks = lo2 to hi2
                    peaksKey = take to wi
                }
                val (l2, h2) = peaks
                val path = Path()
                for (x in l2.indices) {
                    path.moveTo(x.toFloat(), midY - h2[x] * (midY - 2))
                    path.lineTo(x.toFloat(), midY - l2[x] * (midY - 2) + 0.5f)
                }
                drawPath(path, wave, style = Stroke(1f))
            }
            // pitch curve
            val stroke = Stroke(2.2f)
            val pc = Path()
            var open = false
            var lastM = 0.0
            fun point(t: Double, m: Double) {
                val x = xOf(t); val y = yOf(m)
                if (open && kotlin.math.abs(m - lastM) < 1.5) pc.lineTo(x, y) else pc.moveTo(x, y)
                open = true; lastM = m
            }
            if (live) {
                val from = max(0, (start / step).toInt())
                for (i in from until frames) {
                    val v = rec.livePitch.getOrElse(i) { 0f }
                    if (v > 0f) point(i * step, v.toDouble()) else open = false
                }
            } else rec.takePitch?.let { cur ->
                for (i in cur.values.indices) {
                    val v = cur.values[i]
                    if (v > 0f) point(i * cur.hop, Pitch.hzToMidi(v.toDouble())) else open = false
                }
            }
            drawPath(pc, c.accent, style = stroke)
            if (!live && take != null) {
                // dragged part, cursor and playhead
                if (dragFrom >= 0) {
                    val a = xOf(min(dragFrom, dragTo)); val b = xOf(max(dragFrom, dragTo))
                    drawRect(c.selectionRange, Offset(a, 0f), Size(b - a, h))
                }
                val cx = xOf(rec.cursor)
                drawLine(c.cursor.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, h), 1f)
                if (rec.playhead >= 0) {
                    val x = xOf(rec.playhead)
                    drawLine(c.playhead, Offset(x, 0f), Offset(x, h), 2f)
                }
            } else if (live) {
                val x = xOf(frames * step)
                drawLine(c.danger, Offset(x, 0f), Offset(x, h), 2f)
            }
        }
        if (rec.countIn > 0) {
            Text(rec.countIn.toString(), color = c.accent, fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
        } else if (take == null && !live) {
            Text(notRecorded(), color = c.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        val shown = when {
            live -> formatTime(frames * RecorderState.LIVE_STEP)
            take != null -> (if (rec.playhead >= 0) formatTime(rec.playhead) + " / " else "") + formatTime(take.duration)
            else -> ""
        }
        if (shown.isNotEmpty()) Text(shown, color = c.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
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
