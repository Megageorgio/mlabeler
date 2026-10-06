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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
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

val karaokeTitle = L("Karaoke recording", "Караоке-запись")
private val noSongs = L("Add a song to sing over (WAV, MP3, FLAC…). Songs, their lyrics and backing tracks are kept in the program's folder, not in the dataset; only your takes go to the dataset.",
    "Добавьте песню, под которую будете петь (WAV, MP3, FLAC…). Песни, их текст и минусы хранятся в папке программы, а не в датасете; в датасет попадают только ваши дубли.")
private val addSongT = L("Add a song…", "Добавить песню…")
private val songPathT = L("Path to a song file", "Путь к файлу песни")
private val songsFolderT = L("Or put files into: {0}", "Или положите файлы в: {0}")
private val separateT = L("Make a backing track", "Сделать минус")
private val separateHint = L("The toolkit removes the voice from the song; the result is kept for the next time.",
    "Тулкит уберёт голос из песни; результат сохранится на следующий раз.")
private val recogniseT = L("Recognise the words", "Распознать слова")
private val recogniseHint = L("Whisper in the toolkit writes the lines with approximate times. Better after making the backing track.",
    "Whisper в тулките запишет строки с примерным временем. Лучше после того, как сделан минус.")
private val guideT = L("Original voice in the headphones", "Голос исполнителя в наушниках")
private val headphonesT = L("Sing in headphones: whatever the speakers play gets into the take.", "Пойте в наушниках: всё, что играет из колонок, попадёт в дубль.")
private val takeT = L("Take name", "Имя дубля")
private val intoT = L("Takes go to {0}, with the sung lines next to them as .txt", "Дубли сохраняются в {0}, рядом — спетые строки в .txt")
private val recordT = L("Record from here (R)", "Записать отсюда (R)")
private val stopRecT = L("Stop recording", "Остановить запись")
private val lastTakeT = L("Listen to {0}", "Прослушать {0}")
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
private val leadT = L("Start before a line, s", "Начинать до строки, с")
private val languageT = L("Language (empty = detect)", "Язык (пусто — определить)")
private val keysT = L("R records from the current place, Space plays and stops, the Up and Down arrows go by lines, Home goes to the first words.",
    "R — запись с текущего места, пробел — играть и стоп, стрелки вверх и вниз — по строкам, Home — к первым словам.")
private val inT = L("in {0} s", "через {0} с")
private val cancelWorkT = L("Stop", "Остановить")

@Composable
fun KaraokeScreen(app: AppState, k: KaraokeState) {
    val c = T.c
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(k) { kotlinx.coroutines.delay(150); runCatching { focus.requestFocus() } }
    fun addSong() {
        val picked = if (Platform.hasNativeFolderPicker) Platform.pickFileNative(addSongT(), listOf("wav", "mp3", "flac", "ogg", "m4a", "opus", "aiff")) else null
        if (picked != null) k.addSong(picked) else if (!Platform.hasNativeFolderPicker) adding = true
    }
    Box(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || pasting || adding || TextFocus.active) return@onKeyEvent false
                when (e.key) {
                    Key.R -> { if (k.recording) k.stopRecording() else k.record(); true }
                    Key.Spacebar -> { k.toggle(); true }
                    Key.DirectionDown, Key.DirectionRight -> { k.stepLine(1); true }
                    Key.DirectionUp, Key.DirectionLeft -> { k.stepLine(-1); true }
                    Key.MoveHome -> { k.toFirstWords(); true }
                    Key.Escape -> { if (k.recording) k.stopRecording() else k.stop(); true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).background(c.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.back, S.back()) { app.closeKaraoke() }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(karaokeTitle(), color = c.text, fontSize = 15.sp)
                    Text(k.song ?: "", color = c.muted, fontSize = 11.sp, maxLines = 1)
                }
                if (k.dirty) { Btn(S.save()) { k.save() }; Spacer(Modifier.width(6.dp)) }
                Btn(addSongT(), icon = Icons.plus) { addSong() }
            }
            Divider()
            if (k.songs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(noSongs(), color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Btn(addSongT(), primary = true, icon = Icons.plus) { addSong() }
                        Spacer(Modifier.height(8.dp))
                        Text(songsFolderT.format(k.songsDir), color = c.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                if (adding) AddSong(k) { adding = false; focus.requestFocus() }
                return@Column
            }
            // songs
            if (k.songs.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in k.songs) Chip(Paths.stem(s), s == k.song) { if (!k.recording) k.open(s) }
            }
            // what plays in the headphones
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (k.music != null) {
                    Text(guideT(), color = c.muted, fontSize = 12.sp)
                    for ((v, t) in listOf(0f to "0", 0.15f to "15%", 0.35f to "35%", 1f to "100%")) Chip(t, kotlin.math.abs(k.guideLevel - v) < 0.01f) {
                        k.guideLevel = v; if (k.playing && !k.recording) k.play()
                    }
                } else Text(separateHint(), color = c.muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Btn(separateT(), enabled = k.busy == null && k.audio != null && !k.recording) { k.separate() }
                Btn(recogniseT(), enabled = k.busy == null && k.audio != null && !k.recording) { k.recognise() }
            }
            k.busy?.let { b ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b + if (k.progress >= 0) " ${(k.progress * 100).toInt()}%" else "", color = c.accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Btn(cancelWorkT()) { k.cancelWork() }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (editing && !k.recording) LineEditor(k, Modifier.fillMaxSize()) else Lyrics(k, Modifier.fillMaxSize())
            }
            Divider()
            Bar(k)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(firstWordsT(), enabled = k.lines.isNotEmpty() && !k.recording) { k.toFirstWords() }
                IconBtn(Icons.up, prevLineT(), size = 44.dp, enabled = k.lines.isNotEmpty() && !k.recording) { k.stepLine(-1) }
                IconBtn(if (k.playing && !k.recording) Icons.stop else Icons.play, S.play(), size = 48.dp, enabled = k.audio != null && !k.recording) { k.toggle() }
                IconBtn(Icons.down, nextLineT(), size = 44.dp, enabled = k.lines.isNotEmpty() && !k.recording) { k.stepLine(1) }
                // record
                mlabeler.app.ui.Tip(if (k.recording) stopRecT() else recordT()) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(c.danger.copy(alpha = if (k.audio != null) 0.9f else 0.3f))
                            .clickable(enabled = k.audio != null) { if (k.recording) k.stopRecording() else k.record() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(if (k.recording) 18.dp else 22.dp).clip(if (k.recording) RoundedCornerShape(3.dp) else CircleShape).background(androidx.compose.ui.graphics.Color.White))
                    }
                }
                if (k.recording) Box(Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.panelAlt)) {
                    val db = if (k.level > 0f) 20 * kotlin.math.log10(k.level.toDouble()) else -90.0
                    val lv = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(lv).height(8.dp).background(if (lv > 0.92f) c.danger else c.ok))
                }
                Text(mlabeler.app.ui.formatTime(k.position, precise = false) + " / " + mlabeler.app.ui.formatTime(k.duration, precise = false), color = c.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (editing && !k.recording) Btn(pasteT()) { pasting = true }
                Btn(if (editing) doneT() else editT(), primary = editing, enabled = !k.recording) { editing = !editing; if (!editing) focus.requestFocus() }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(takeT(), color = c.muted, fontSize = 12.sp)
                Field(k.takeName, { k.takeName = it }, Modifier.width(180.dp))
                if (k.lastTake != null && !k.recording) Btn(lastTakeT.format(k.lastTakeName), icon = Icons.play) { k.playLastTake() }
                Text(intoT.format(Paths.name(k.folder)) + ". " + headphonesT(), color = c.muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
            }
            if (!Platform.isMobile) Text(keysT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        }
        mlabeler.app.ui.MessageToast(app, 24.dp)
        if (pasting) PasteLyrics(k) { pasting = false; focus.requestFocus() }
        if (adding && k.songs.isNotEmpty()) AddSong(k) { adding = false; focus.requestFocus() }
    }
}

/** Where file dialogs are missing: a path to type, and the folder to put songs into. */
@Composable
private fun AddSong(k: KaraokeState, onClose: () -> Unit) {
    val c = T.c
    var path by remember { mutableStateOf("") }
    Overlay(onClose, 520) {
        Column(Modifier.padding(18.dp)) {
            Text(addSongT(), color = c.text, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Field(path, { path = it }, Modifier.fillMaxWidth(), placeholder = songPathT(), onDone = { k.addSong(path); onClose() })
            Text(songsFolderT.format(k.songsDir), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { onClose() }
                Btn(S.ok(), primary = true, enabled = path.isNotBlank()) { k.addSong(path); onClose() }
            }
        }
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

