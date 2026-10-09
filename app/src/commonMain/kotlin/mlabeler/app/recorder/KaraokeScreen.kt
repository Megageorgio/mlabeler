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
import mlabeler.app.theme.RoundedCornerShape
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
import androidx.compose.ui.text.drawText
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
import mlabeler.app.ui.scrollWithHint
import mlabeler.app.ui.DialogContent
import mlabeler.app.ui.TextFocus
import mlabeler.core.io.Paths
import kotlin.math.max

val karaokeTitle = L("Karaoke recording", "Караоке-запись")
private val noSongs = L("Add a song to sing over (WAV, MP3, FLAC…). Songs, their lyrics and backing tracks are kept in the program's folder, not in the dataset; only your takes go to the dataset.",
    "Добавьте песню для исполнения (WAV, MP3, FLAC…). Песни, их тексты и минусы хранятся в папке программы, а не в датасете; в датасет попадают только ваши дубли.")
private val addSongT = L("Add a song…", "Добавить песню…")
private val songPathT = L("Path to a song file", "Путь к файлу песни")
private val songsFolderT = L("Or put files into: {0}", "Или положите файлы в: {0}")
private val separateT = L("Make a backing track", "Создать минус")
private val separateHint = L("The toolkit removes the voice from the song; the result is kept for the next time.",
    "Тулкит уберёт голос из песни; результат сохранится на следующий раз.")
private val recogniseT = L("Recognise the words", "Распознать слова")
private val recogniseHint = L("Whisper in the toolkit writes the lines with approximate times, in the language set next to the button. It listens to the voice without the music, so the backing track is made first if there is none.",
    "Whisper в тулките записывает строки с примерным временем на языке, указанном рядом с кнопкой. Он анализирует голос без музыки, поэтому, если минуса ещё нет, сначала создаётся он.")
private val guideT = L("Original voice in the headphones", "Голос исполнителя в наушниках")
private val headphonesT = L("Sing in headphones: sound from the speakers is recorded into the take.", "Пойте в наушниках: звук из колонок записывается в дубль.")
private val takeT = L("Take name", "Имя дубля")
private val intoT = L("Takes go to {0}, with the sung lines next to them as .txt", "Дубли сохраняются в {0}, рядом — спетые строки в .txt")
private val recordT = L("Record from here (R)", "Записать отсюда (R)")
private val stopRecT = L("Stop recording", "Остановить запись")
private val lastTakeT = L("Listen to {0}", "Прослушать {0}")
private val editT = L("Edit the lines", "Править строки")
private val doneT = L("Done", "Готово")
private val pasteT = L("Paste the lyrics", "Вставить текст")
private val pasteAbout = L("One lyric line per line. The times of the existing lines are kept, new lines are spread to the end; then set each one with ⏱ while listening.",
    "Одна строка текста на строку. Время имеющихся строк сохраняется, новые распределяются до конца; затем выставьте каждую кнопкой ⏱ во время прослушивания.")
private val noLines = L("No lyrics yet. Recognise them in the toolkit, paste the text, or add lines while listening.",
    "Текста пока нет. Распознайте его в тулките, вставьте или добавьте строки во время прослушивания.")
private val firstWordsT = L("To the first words", "К первым словам")
private val prevLineT = L("Previous line", "Предыдущая строка")
private val nextLineT = L("Next line", "Следующая строка")
private val addHereT = L("Add a line here", "Добавить строку здесь")
private val leadT = L("Start before a line, s", "Начинать до строки, с")
private val languageT = L("Lyrics language", "Язык текста")
private val firstTimeT = L("On first use the toolkit installs the separation module and downloads its model (several GB); this takes some time, later runs are fast.",
    "При первом использовании тулкит устанавливает модуль разделения и загружает его модель (несколько ГБ); это занимает время, последующие запуски выполняются быстро.")
private val keysT = L("R records from the current place, Space plays and stops, the Up and Down arrows go by lines, Home goes to the first words.",
    "R — запись с текущего места, пробел — воспроизведение и остановка, стрелки вверх и вниз — переход по строкам, Home — к первым словам.")
private val inT = L("in {0} s", "через {0} с")
private val cancelWorkT = L("Stop", "Остановить")
private val tidyT = L("Tidy the lines", "Упорядочить строки")
private val loopT = L("Repeat the line", "Повторять строку")
private val keyT = L("Key", "Тональность")
private val keyDownT = L("A semitone lower", "На полтона ниже")
private val keyUpT = L("A semitone higher", "На полтона выше")
private val tempoT = L("Tempo", "Темп")
private val preparingT = L("Preparing the backing…", "Подготовка минуса…")
private val splitT = L("Each line to its own file", "Каждая строка — отдельный файл")
private val latencyT = L("Sound card delay, ms", "Задержка звука, мс")
private val practiceT = L("Practice", "Репетиция")
private val practicingT = L("Practice: nothing is saved", "Репетиция: ничего не сохраняется")
private val leakT = L("The backing track is heard in this take: sing in headphones, or turn the speakers down.", "В дубле слышен минус: пойте в наушниках или уменьшите громкость колонок.")
private val takesT = L("Takes (click to listen):", "Дубли (щёлкните, чтобы послушать):")
private val scoreT = L("In tune {0}% of the time · {1} ¢ off on average", "Попадание в ноты: {0}% времени · среднее отклонение {1} ¢")
private val octDownT = L("an octave lower than the song", "октавой ниже песни")
private val octUpT = L("an octave higher than the song", "октавой выше песни")
private val pitchHint = L("Grey: the song's melody; colour: your voice (an octave up or down counts as right)",
    "Серым — мелодия песни, цветом — ваш голос (октава выше или ниже считается верной)")

/** The song's melody around now (from its separated voice) and the singer's pitch while recording. */
@Composable
private fun PitchLane(k: KaraokeState) {
    val c = T.c
    val ref = k.refPitch?.let { r -> val st = k.semitones; if (st == 0) r else remember(r, st) { FloatArray(r.size) { r[it] + st } } }
    @Suppress("UNUSED_VARIABLE") val tick = k.liveTick
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 32)
    val labelStyle = TextStyle(color = c.muted, fontSize = 10.sp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pitchHint(), color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            val now = (k.position / KaraokeState.PITCH_HOP).toInt()
            val r = ref?.getOrNull(now)?.takeIf { !it.isNaN() }
            val m = k.liveNote
            if (k.recording && m > 0f) {
                Text(mlabeler.core.format.NoteNames.format(kotlin.math.round(m.toDouble())), color = c.accent, fontSize = 16.sp)
                if (r != null) {
                    val d = m - r - 12 * kotlin.math.round((m - r) / 12f)
                    val cents = (d * 100).toInt()
                    Text("  " + (if (cents >= 0) "+" else "") + "$cents ¢", fontSize = 13.sp,
                        color = if (kotlin.math.abs(cents) <= 50) c.ok else if (kotlin.math.abs(cents) <= 100) c.warn else c.danger)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(c.radius)).background(c.laneBg)) {
            val w = size.width; val h = size.height
            val hop = KaraokeState.PITCH_HOP
            val from = k.position - 5.0; val to = k.position + 3.0
            fun xOf(t: Double) = ((t - from) / (to - from) * w).toFloat()
            val i0 = (from / hop).toInt().coerceAtLeast(0)
            val i1 = (to / hop).toInt()
            // range: the melody around now, at least an octave
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            if (ref != null) for (i in i0 until minOf(i1, ref.size)) { val v = ref[i]; if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v } }
            if (lo > hi) { lo = 55f; hi = 67f }
            val mid = (lo + hi) / 2
            lo = minOf(lo, mid - 6) - 1; hi = maxOf(hi, mid + 6) + 1
            fun yOf(m: Float) = h - (m - lo) / (hi - lo) * h
            for (n in kotlin.math.ceil(lo).toInt()..hi.toInt()) {
                val pc = ((n % 12) + 12) % 12
                if (pc in setOf(1, 3, 6, 8, 10)) drawRect(c.text.copy(alpha = 0.035f), Offset(0f, yOf(n + 0.5f)), Size(w, h / (hi - lo)))
                if (pc == 0) {
                    drawLine(c.text.copy(alpha = 0.14f), Offset(0f, yOf(n - 0.5f)), Offset(w, yOf(n - 0.5f)), 1f)
                    drawText(measurer.measure(mlabeler.core.format.NoteNames.format(n.toDouble()), labelStyle), topLeft = Offset(3f, yOf(n.toFloat()) - 7f))
                }
            }
            fun curve(get: (Int) -> Float, color: androidx.compose.ui.graphics.Color, width: Float) {
                val path = androidx.compose.ui.graphics.Path()
                var open = false; var last = 0f
                for (i in i0 until i1) {
                    val v = get(i)
                    if (v.isNaN() || v == 0f) { open = false; continue }
                    val x = xOf(i * hop); val y = yOf(v)
                    if (open && kotlin.math.abs(v - last) < 1.5f) path.lineTo(x, y) else path.moveTo(x, y)
                    open = true; last = v
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            if (ref != null) curve({ ref.getOrElse(it) { Float.NaN } }, c.text.copy(alpha = 0.35f), 7f)
            val live = k.livePitch
            if (live.isNotEmpty()) curve({ i ->
                val l = live.getOrElse(i) { 0f }
                val r = ref?.getOrNull(i)
                // shown in the song's octave: being an octave off is not a mistake
                if (l == 0f || l.isNaN() || r == null || r.isNaN()) l else l - 12 * kotlin.math.round((l - r) / 12f)
            }, c.accent, 2.5f)
            val x = xOf(k.position)
            drawLine(if (k.recording) c.danger else c.playhead, Offset(x, 0f), Offset(x, h), 2f)
        }
    }
}

@Composable
fun KaraokeScreen(app: AppState, k: KaraokeState) {
    val c = T.c
    val focus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
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
                IconBtn(Icons.settings, optionsT()) { options = true }
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
                Text(languageT(), color = c.muted, fontSize = 12.sp)
                Field(k.language, { k.updateLanguage(it) }, Modifier.width(56.dp), placeholder = "ru")
                Btn(recogniseT(), enabled = k.busy == null && k.audio != null && !k.recording) { k.recognise() }
            }
            // key, tempo and what to do with a take
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(musicT(), color = c.muted, fontSize = 12.sp)
                val level = app.settings.karaoke.musicLevel
                for ((v, t) in listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%")) Chip(t, kotlin.math.abs(level - v) < 0.01f) { k.updateMusicLevel(v) }
                Spacer(Modifier.width(8.dp))
                Text(keyT(), color = c.muted, fontSize = 12.sp)
                IconBtn(Icons.nudgeLeft, keyDownT(), size = 30.dp, enabled = !k.recording) { k.updateKey(k.semitones - 1) }
                Text((if (k.semitones > 0) "+" else "") + k.semitones, color = if (k.semitones != 0) c.accent else c.text, fontSize = 13.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                IconBtn(Icons.nudgeRight, keyUpT(), size = 30.dp, enabled = !k.recording) { k.updateKey(k.semitones + 1) }
                Spacer(Modifier.width(10.dp))
                Text(tempoT(), color = c.muted, fontSize = 12.sp)
                for (v in listOf(1.0, 0.9, 0.8, 0.7)) Chip("${(v * 100).toInt()}%", kotlin.math.abs(k.speed - v) < 0.001) { if (!k.recording) k.updateSpeed(v) }
                if (k.preparing) Text(preparingT(), color = c.accent, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Chip(splitT(), k.splitLines) { k.splitLines = !k.splitLines }
                Spacer(Modifier.width(10.dp))
                Text(latencyT(), color = c.muted, fontSize = 12.sp)
                var lat by remember { mutableStateOf(k.latencyMs.toString()) }
                Field(lat, { lat = it; it.toIntOrNull()?.let { v -> k.updateLatency(v) } }, Modifier.width(64.dp))
            }
            k.busy?.let { b ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b + if (k.progress >= 0) " ${(k.progress * 100).toInt()}%" else "", color = c.accent, fontSize = 12.sp)
                        if (k.stageText.isNotBlank()) Text(k.stageText, color = c.muted, fontSize = 11.sp, maxLines = 1)
                        if (k.music == null) Text(firstTimeT(), color = c.muted, fontSize = 11.sp)
                    }
                    Btn(cancelWorkT()) { k.cancelWork() }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (editing && !k.recording) LineEditor(k, Modifier.fillMaxSize()) else Lyrics(k, Modifier.fillMaxSize())
            }
            if (k.refPitch != null || k.recording) PitchLane(k)
            k.score?.let { sc ->
                Text(scoreT.format((sc.inTune * 100).toInt(), sc.meanCents.toInt()) + when (sc.octaveShift) { 0 -> ""; -1 -> " · " + octDownT(); 1 -> " · " + octUpT(); else -> "" },
                    color = if (sc.inTune >= 0.7) c.ok else if (sc.inTune >= 0.45) c.warn else c.danger, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (k.leaked) Text(leakT(), color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            if (k.takes.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(takesT(), color = c.muted, fontSize = 12.sp)
                val best = k.takes.maxByOrNull { it.score?.inTune ?: -1.0 }
                for (t in k.takes.asReversed()) Chip(t.name + (t.score?.let { " · ${(it.inTune * 100).toInt()}%" } ?: "") + (if (t === best && t.score != null) " ★" else ""), false) { k.playTake(t.audio) }
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
                if (!k.recording) Btn(practiceT(), enabled = k.audio != null) { k.record(practiceOnly = true) }
                else if (k.practice) Text(practicingT(), color = c.accent, fontSize = 12.sp)
                if (k.recording) Box(Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.panelAlt)) {
                    val db = if (k.level > 0f) 20 * kotlin.math.log10(k.level.toDouble()) else -90.0
                    val lv = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(lv).height(8.dp).background(if (lv > 0.92f) c.danger else c.ok))
                }
                Text(mlabeler.app.ui.formatTime(k.position, precise = false) + " / " + mlabeler.app.ui.formatTime(k.duration, precise = false), color = c.muted, fontSize = 12.sp)
                Chip(loopT(), k.loopLine) { k.loopLine = !k.loopLine }
                Spacer(Modifier.weight(1f))
                if (editing && !k.recording) Btn(tidyT()) { k.tidyLines() }
                if (editing && !k.recording) Btn(pasteT() + "…") { pasting = true }
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
        if (options) KaraokeOptions(app) { options = false; focus.requestFocus() }
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
                val sc = k.score?.perLine?.get(i)
                if (sc != null) Text("${(sc * 100).toInt()}%", fontSize = 11.sp,
                    color = if (sc >= 0.7) c.ok else if (sc >= 0.45) c.warn else c.danger)
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
        DialogContent(footer = {
            Btn(S.cancel()) { onClose() }
            Btn(S.ok(), primary = true) { k.replaceText(text); onClose() }
        }) {
            Text(pasteT(), color = c.text, fontSize = 17.sp)
            Text(pasteAbout(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            BasicTextField(
                text, { text = it }, textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).clip(RoundedCornerShape(c.radius))
                    .background(c.bg).border(c.borderWidth, c.border, RoundedCornerShape(c.radius)).padding(10.dp)
                    .scrollWithHint(),
            )
        }
    }
}


private val optionsT = L("Karaoke settings", "Настройки караоке")
private val musicT = L("Music", "Музыка")
private val separationModelT = L("Separating the voice from the music", "Отделение голоса от музыки")
private val sepAutoT = L("Auto", "Авто")
private val sepAutoHint = L("BS-Roformer with an NVIDIA graphics card, MDX-Net without one", "BS-Roformer, если есть видеокарта NVIDIA, иначе MDX-Net")
private val sepFastT = L("Fast (MDX-Net)", "Быстро (MDX-Net)")
private val sepBestT = L("Best (BS-Roformer)", "Лучше всего (BS-Roformer)")
private val sepMelT = L("Mel-Roformer", "Mel-Roformer")
private val sepNote = L("Roformer models are slow without a graphics card (minutes per song). A song is separated once; to separate it again with another model, remove its parts in the songs folder.",
    "Модели Roformer без видеокарты работают медленно (минуты на песню). Песня отделяется один раз; чтобы отделить её заново другой моделью, удалите её части в папке песен.")
private val whisperT = L("Recognising the words", "Распознавание слов")
private val whSmallT = L("Fast (small)", "Быстро (small)")
private val whMediumT = L("Medium", "Средне (medium)")
private val whTurboT = L("Accurate (large-v3-turbo)", "Точно (large-v3-turbo)")
private val whLargeT = L("Most accurate (large-v3, slow)", "Точнее всего (large-v3, медленно)")
private val whNote = L("Each model is downloaded on first use. Without a graphics card the small ones are several times faster.",
    "Каждая модель загружается при первом использовании. Без видеокарты маленькие модели в несколько раз быстрее.")

/** Karaoke settings: the models that separate the voice and recognise the words (kept in the settings). */
@Composable
private fun KaraokeOptions(app: AppState, onClose: () -> Unit) {
    val c = T.c
    val s = app.settings.karaoke
    fun set(f: (mlabeler.app.state.KaraokeSettings) -> mlabeler.app.state.KaraokeSettings) = app.update { it.copy(karaoke = f(it.karaoke)) }
    mlabeler.app.ui.Overlay(onClose, 560) {
        DialogContent(footer = { Btn(S.close(), primary = true) { onClose() } }) {
            Text(optionsT(), color = c.text, fontSize = 17.sp)
            mlabeler.app.ui.SectionTitle(separationModelT())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, t) in listOf("auto" to sepAutoT(), "separation-vocals-mdx-fast" to sepFastT(), "separation-vocals-bs-roformer" to sepBestT(), "separation-vocals-melband-roformer" to sepMelT()))
                    Chip(t, s.separation == id) { set { it.copy(separation = id) } }
            }
            Text(sepAutoHint() + ". " + sepNote(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            mlabeler.app.ui.SectionTitle(whisperT())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, t) in listOf("whisper-small" to whSmallT(), "whisper-medium" to whMediumT(), "whisper-large-v3-turbo" to whTurboT(), "whisper-large-v3" to whLargeT()))
                    Chip(t, s.whisper == id) { set { it.copy(whisper = id) } }
            }
            Text(whNote(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
