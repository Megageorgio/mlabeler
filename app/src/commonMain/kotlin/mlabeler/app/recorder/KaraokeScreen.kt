package mlabeler.app.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import mlabeler.app.ui.TextFocus
import mlabeler.core.io.Paths
import kotlin.math.max

val karaokeTitle = L("Karaoke", "Караоке")
private val noSongs = L("No audio files in this folder. Put a song here (WAV, MP3, FLAC…) and open again.",
    "В папке нет аудиофайлов. Положите сюда песню (WAV, MP3, FLAC…) и откройте снова.")
private val songT = L("Song", "Песня")
private val musicT = L("Music only", "Минус")
private val voiceT = L("Voice only", "Голос")
private val separateT = L("Separate the voice", "Отделить голос")
private val separateHint = L("The toolkit splits the song into the voice and the music; both are kept for the next time.",
    "Тулкит разделит песню на голос и минус; оба сохранятся на следующий раз.")
private val recogniseT = L("Recognise the words", "Распознать слова")
private val recogniseHint = L("Whisper in the toolkit writes the lines with approximate times. Better after separating the voice.",
    "Whisper в тулките запишет строки с примерным временем. Лучше после отделения голоса.")
private val editT = L("Edit the lines", "Править строки")
private val doneT = L("Done", "Готово")
private val pasteT = L("Paste the lyrics", "Вставить текст")
private val pasteAbout = L("One line per line. The times of the existing lines are kept, new lines are spread to the end; then set each one with ⏱ while listening.",
    "По одной строке на строку. Время уже имеющихся строк сохранится, новые распределятся до конца; потом выставьте каждую кнопкой ⏱ под музыку.")
private val noLines = L("No lyrics yet. Recognise them in the toolkit, paste the text, or add lines while listening.",
    "Текста пока нет. Распознайте его в тулките, вставьте или добавляйте строки под музыку.")
private val firstWordsT = L("To the first words", "К первым словам")
private val prevLineT = L("Previous line", "Предыдущая строка")
private val nextLineT = L("Next line", "Следующая строка")
private val addHereT = L("Add a line here", "Строка отсюда")
private val setNowT = L("Starts now (at the playhead)", "Начинается сейчас (по позиции)")
private val leadT = L("Start before a line, s", "Начинать до строки, с")
private val languageT = L("Language (empty = detect)", "Язык (пусто — определить)")
private val keysT = L("Space plays and stops, the Up and Down arrows go by lines, Home goes to the first words.",
    "Пробел — играть и стоп, стрелки вверх и вниз — по строкам, Home — к первым словам.")
private val inT = L("in {0} s", "через {0} с")
private val cancelWorkT = L("Stop", "Остановить")

@Composable
fun KaraokeScreen(app: AppState, k: KaraokeState) {
    val c = T.c
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    LaunchedEffect(k) { kotlinx.coroutines.delay(150); runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || pasting || TextFocus.active) return@onKeyEvent false
                when (e.key) {
                    Key.Spacebar -> { k.toggle(); true }
                    Key.DirectionDown, Key.DirectionRight -> { k.stepLine(1); true }
                    Key.DirectionUp, Key.DirectionLeft -> { k.stepLine(-1); true }
                    Key.MoveHome -> { k.toFirstWords(); true }
                    Key.Escape -> { k.stop(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).background(c.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.back, S.back()) { app.closeKaraoke() }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(karaokeTitle(), color = c.text, fontSize = 15.sp)
                    Text(k.song ?: Paths.name(k.folder), color = c.muted, fontSize = 11.sp, maxLines = 1)
                }
                if (k.dirty) Btn(S.save()) { k.save() }
            }
            Divider()
            if (k.songs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(noSongs(), color = c.muted, fontSize = 14.sp) }
                return@Column
            }
            // songs and what to hear
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (k.songs.size > 1) for (s in k.songs) Chip(Paths.stem(s), s == k.song) { k.open(s) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(songT(), k.source == KaraokeState.Source.Song) { k.useSource(KaraokeState.Source.Song) }
                if (k.music != null) Chip(musicT(), k.source == KaraokeState.Source.Music) { k.useSource(KaraokeState.Source.Music) }
                if (k.voice != null) Chip(voiceT(), k.source == KaraokeState.Source.Voice) { k.useSource(KaraokeState.Source.Voice) }
                Spacer(Modifier.weight(1f))
                Btn(separateT(), enabled = k.busy == null && k.audio != null) { k.separate() }
                Btn(recogniseT(), enabled = k.busy == null && k.audio != null) { k.recognise() }
            }
            k.busy?.let { b ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b + if (k.progress >= 0) " ${(k.progress * 100).toInt()}%" else "", color = c.accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Btn(cancelWorkT()) { k.cancelWork() }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (editing) LineEditor(k, Modifier.fillMaxSize()) else Lyrics(k, Modifier.fillMaxSize())
            }
            Divider()
            Bar(k)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(firstWordsT(), enabled = k.lines.isNotEmpty()) { k.toFirstWords() }
                IconBtn(Icons.up, prevLineT(), size = 44.dp, enabled = k.lines.isNotEmpty()) { k.stepLine(-1) }
                IconBtn(if (k.playing) Icons.stop else Icons.play, S.play(), size = 52.dp, enabled = k.audio != null) { k.toggle() }
                IconBtn(Icons.down, nextLineT(), size = 44.dp, enabled = k.lines.isNotEmpty()) { k.stepLine(1) }
                Text(mlabeler.app.ui.formatTime(k.position, precise = false) + " / " + mlabeler.app.ui.formatTime(k.duration, precise = false), color = c.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (editing) Btn(pasteT()) { pasting = true }
                Btn(if (editing) doneT() else editT(), primary = editing) { editing = !editing; if (!editing) focus.requestFocus() }
            }
            if (!Platform.isMobile) Text(keysT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        }
        mlabeler.app.ui.MessageToast(app, 24.dp)
        if (pasting) PasteLyrics(k) { pasting = false; focus.requestFocus() }
    }
}

/** The lines around the one sung now, the current one large. */
@Composable
private fun Lyrics(k: KaraokeState, modifier: Modifier) {
    val c = T.c
    val cur = k.current
    val state = rememberLazyListState()
    LaunchedEffect(cur) { if (k.lines.isNotEmpty()) state.animateScrollToItem(max(0, cur - 2)) }
    if (k.lines.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(noLines(), color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(separateT() + ": " + separateHint(), color = c.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                Text(recogniseT() + ": " + recogniseHint(), color = c.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }
    Column(modifier) {
        // before the first words: how long to wait
        val first = k.lines.first().time
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
            if (cur < 0 && k.playing && first - k.position < 10) Text(inT.format(kotlin.math.ceil(first - k.position).toInt().coerceAtLeast(0)), color = c.accent, fontSize = 15.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(k.lines) { i, l ->
                val now = i == cur
                val next = i == cur + 1
                Text(
                    l.text.ifEmpty { "…" },
                    color = when { now -> c.accent; next -> c.text; i < cur -> c.muted.copy(alpha = 0.6f); else -> c.muted },
                    fontSize = if (now) 30.sp else if (next) 22.sp else 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { k.toLine(i, withLead = false); if (!k.playing) k.play() }.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Lines with their times: text, start = now, nudge, remove. */
@Composable
private fun LineEditor(k: KaraokeState, modifier: Modifier) {
    val c = T.c
    val cur = k.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(addHereT(), icon = Icons.plus) { k.addLineAt(k.position) }
            Spacer(Modifier.weight(1f))
            Text(leadT(), color = c.muted, fontSize = 12.sp)
            for (v in listOf(1.0, 2.0, 3.0, 5.0)) Chip(v.toInt().toString(), k.lead == v) { k.lead = v }
            Spacer(Modifier.width(8.dp))
            Text(languageT(), color = c.muted, fontSize = 12.sp)
            Field(k.language, { k.language = it }, Modifier.width(70.dp), placeholder = "ru")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(k.lines, key = { i, l -> "$i:${l.time}" }) { i, l ->
                Row(
                    Modifier.fillMaxWidth().background(if (i == cur) c.accent.copy(alpha = 0.10f) else c.bg).padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(mlabeler.app.ui.formatTime(l.time), color = c.muted, fontSize = 12.sp, modifier = Modifier.width(78.dp).clickable { k.toLine(i, withLead = false) })
                    IconBtn(Icons.nudgeLeft, "−0.1 s", size = 30.dp) { k.nudge(i, -0.1) }
                    IconBtn(Icons.nudgeRight, "+0.1 s", size = 30.dp) { k.nudge(i, 0.1) }
                    Btn("⏱") { k.setTime(i, k.position) }
                    Field(l.text, { k.setText(i, it) }, Modifier.weight(1f))
                    IconBtn(Icons.play, S.play(), size = 30.dp) { k.toLine(i); k.play() }
                    IconBtn(Icons.trash, S.deleteTier(), size = 30.dp) { k.remove(i) }
                }
            }
        }
    }
}

/** Where in the song we are, the lines as ticks; click or drag to move. */
@Composable
private fun Bar(k: KaraokeState) {
    val c = T.c
    val dur = k.duration
    Canvas(
        Modifier.fillMaxWidth().height(if (Platform.isMobile) 36.dp else 26.dp).padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(dur) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = (x / size.width * dur).coerceIn(0.0, dur)
                    var t = at(down.position.x)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        t = at(ch.position.x); ch.consume()
                    }
                    k.seek(t)
                }
            },
    ) {
        val w = size.width; val h = size.height
        drawRect(c.panelAlt, Offset(0f, h * 0.3f), Size(w, h * 0.4f))
        if (dur > 0) {
            for (l in k.lines) { val x = (l.time / dur * w).toFloat(); drawLine(c.muted, Offset(x, 0f), Offset(x, h), 1f) }
            val x = (k.position / dur * w).toFloat()
            drawRect(c.accent.copy(alpha = 0.5f), Offset(0f, h * 0.3f), Size(x, h * 0.4f))
            drawLine(c.playhead, Offset(x, 0f), Offset(x, h), 2f)
        }
    }
}

@Composable
private fun PasteLyrics(k: KaraokeState, onClose: () -> Unit) {
    val c = T.c
    var text by remember { mutableStateOf(k.lines.joinToString("\n") { it.text }) }
    Overlay(onClose, 560) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(pasteT(), color = c.text, fontSize = 17.sp)
            Text(pasteAbout(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            BasicTextField(
                text, { text = it }, textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).clip(RoundedCornerShape(c.radius))
                    .background(c.bg).border(c.borderWidth, c.border, RoundedCornerShape(c.radius)).padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { onClose() }
                Btn(S.ok(), primary = true) { k.replaceText(text); onClose() }
            }
        }
    }
}

