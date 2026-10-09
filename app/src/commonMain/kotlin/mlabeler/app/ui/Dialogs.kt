package mlabeler.app.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.Lang
import mlabeler.app.i18n.L
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.theme.Themes
import mlabeler.core.format.LabelFormat
import kotlin.math.roundToInt
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import kotlinx.coroutines.launch

/** Dim background with a centred card; full screen on narrow windows. */
internal object PlayTitles {
    val playback = mlabeler.app.i18n.L("Playback", "Воспроизведение")
    val volume = mlabeler.app.i18n.L("Volume", "Громкость")
    val follow = mlabeler.app.i18n.L("While playing, the view", "Во время воспроизведения вид")
    val followOff = mlabeler.app.i18n.L("Stays in place", "Остаётся на месте")
    val followPage = mlabeler.app.i18n.L("Turns the page at the edge", "Перелистывается у края")
    val followKeep = mlabeler.app.i18n.L("Scrolls smoothly, the playhead stays in place", "Плавно прокручивается, курсор остаётся на месте")
    val followAt = mlabeler.app.i18n.L("Where the playhead stays (from the left)", "Где держится курсор (от левого края)")
}

private object CheckTitles {
    val maxLen = mlabeler.app.i18n.L("Longest phoneme (not a pause)", "Самая длинная фонема (не пауза)")
    val maxPause = mlabeler.app.i18n.L("Longest pause or gap", "Самая длинная пауза или промежуток")
    val maxPhrase = mlabeler.app.i18n.L("Longest singing without a pause (DiffSinger: about 15 s)", "Самое долгое пение без паузы (для DiffSinger — около 15 с)")
    val phrasePause = mlabeler.app.i18n.L("A pause counts from", "Пауза считается от")
    val zeroOff = mlabeler.app.i18n.L("0 = not checked.", "0 — не проверять.")
    val diffsinger = mlabeler.app.i18n.L("For DiffSinger: phonemes shorter than one frame, spaces inside a phoneme, two same pauses in a row, zero length",
        "Для DiffSinger: фонемы короче одного кадра, пробел внутри фонемы, две одинаковые паузы подряд, нулевая длина")
    val edges = mlabeler.app.i18n.L("A pause at the start and the end of every recording", "Пауза в начале и в конце каждой записи")
    val edgeWhich = mlabeler.app.i18n.L("Which one (empty = any pause)", "Какая именно (пусто — любая пауза)")
    val scripts = mlabeler.app.i18n.L("Own checks (scripts)", "Свои проверки (скрипты)")
    val scriptsHint = mlabeler.app.i18n.L("Small JavaScript files that mark problems in the labels. For every folder: {0}; for one folder: {1} inside it. The example shows how.",
        "Небольшие файлы на JavaScript, которые отмечают проблемы в разметке. Для всех папок: {0}; для одной папки: {1} внутри неё. Порядок написания показан в примере.")
    val runScripts = mlabeler.app.i18n.L("Run them", "Запускать их")
    val none = mlabeler.app.i18n.L("No scripts yet", "Скриптов пока нет")
    val example = mlabeler.app.i18n.L("Create an example", "Создать пример")
    val reload = mlabeler.app.i18n.L("Read again", "Перечитать")
    val fromDict = mlabeler.app.i18n.L("Take the set from a dictionary:", "Взять набор из словаря:")
    val anyPhoneme = mlabeler.app.i18n.L("Any (not checked)", "Любые (не проверять)")
    val written = mlabeler.app.i18n.L("Example saved: {0}", "Пример сохранён: {0}")
}

private val tipsAtStartT = mlabeler.app.i18n.L("Tip of the day at start", "Совет дня при запуске")
private val titleBarMenuT = mlabeler.app.i18n.L("Menus in the window title", "Меню в заголовке окна")
private val restartT = mlabeler.app.i18n.L("Takes effect after restarting mLabeler", "Сработает после перезапуска mLabeler")
private val minimapT = mlabeler.app.i18n.L("Map of the whole recording instead of the scroll bar", "Карта всей записи вместо полосы прокрутки")
private val phonemeColorsT = mlabeler.app.i18n.L("Phonemes tinted by kind (vowels, consonants, pauses)", "Подсветка фонем по типу (гласные, согласные, паузы)")
private val animationsT = mlabeler.app.i18n.L("Animations", "Анимации")
private val animNormalT = mlabeler.app.i18n.L("Normal", "Обычные")
private val animReducedT = mlabeler.app.i18n.L("Reduced", "Уменьшенные")
private val animOffT = mlabeler.app.i18n.L("Off", "Выключены")
private val animationsHint = mlabeler.app.i18n.L("Windows, messages and buttons appear and change softly. What you edit — boundaries, the cursor, the zoom — never moves by itself.",
    "Окна, сообщения и кнопки появляются и меняются плавно. То, что вы редактируете, — границы, курсор, масштаб — само никогда не движется.")
private val searchSettingsT = mlabeler.app.i18n.L("Search settings", "Поиск настроек")
private val scaleButtonT = mlabeler.app.i18n.L("Interface size button (in percent) on the toolbar", "Кнопка размера интерфейса (в процентах) на панели")
private val detailT = mlabeler.app.i18n.L("Detail", "Чёткость")
private val detailHint = mlabeler.app.i18n.L("Sharper pictures take longer to build and more memory; the values below can be set by hand too.",
    "Более чёткая картинка дольше строится и занимает больше памяти; значения ниже можно задать и вручную.")
/** Window ms, step ms (0 = by length), bands. */
private val detailPresets = listOf(
    mlabeler.app.i18n.L("Low", "Низкая") to Triple(30f, 8f, 128),
    mlabeler.app.i18n.L("Normal", "Обычная") to Triple(25f, 0f, 192),
    mlabeler.app.i18n.L("High", "Высокая") to Triple(20f, 1.5f, 288),
    mlabeler.app.i18n.L("Highest", "Максимальная") to Triple(20f, 1f, 384),
)
private val snapZeroT = mlabeler.app.i18n.L("Snap boundaries to zero crossings of the waveform", "Привязывать границы к переходам волны через ноль")
private val keepZoomT = mlabeler.app.i18n.L("Keep the scale when going to another file", "Сохранять масштаб при переходе к другому файлу")
private val namesOnAudioT = mlabeler.app.i18n.L("Phoneme names on the waveform and spectrogram too", "Имена фонем также на волне и спектрограмме")
private val namesWhereT = mlabeler.app.i18n.L("Where inside each phoneme: drag the dot or click the grid", "Где внутри каждой фонемы: перетащите точку или щёлкните по сетке")

/** A box standing for one phoneme: the dot is where its name goes (0..1 across and down). */
@Composable
private fun PlacementPad(x: Float, y: Float, onChange: (Float, Float) -> Unit) {
    val c = T.c
    var px by remember { mutableStateOf(x) }
    var py by remember { mutableStateOf(y) }
    androidx.compose.runtime.LaunchedEffect(x, y) { px = x; py = y }
    fun snap(v: Float) = (kotlin.math.round(v * 20) / 20f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(
            Modifier.size(180.dp, 110.dp).clip(RoundedCornerShape(c.radius)).background(c.laneBg)
                .border(c.borderWidth, c.border, RoundedCornerShape(c.radius))
                .pointerInput(Unit) {
                    detectTapGestures { o -> px = snap(o.x / size.width); py = snap(o.y / size.height); onChange(px, py) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onChange(px, py) }) { ch, _ ->
                        px = snap(ch.position.x / size.width); py = snap(ch.position.y / size.height)
                    }
                },
        ) {
            for (k in 1..3) {
                drawLine(c.border, androidx.compose.ui.geometry.Offset(size.width * k / 4, 0f), androidx.compose.ui.geometry.Offset(size.width * k / 4, size.height), 1f)
                drawLine(c.border, androidx.compose.ui.geometry.Offset(0f, size.height * k / 4), androidx.compose.ui.geometry.Offset(size.width, size.height * k / 4), 1f)
            }
            drawCircle(c.accent, 7.dp.toPx(), androidx.compose.ui.geometry.Offset(px * size.width, py * size.height))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("${(px * 100).toInt()}% · ${(py * 100).toInt()}%", color = c.text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Btn(S.reset()) { px = 0.5f; py = 0.5f; onChange(0.5f, 0.5f) }
        }
    }
}

private val whatToShow = mlabeler.app.i18n.L("What to show", "Что показывать")
private val scaleT = mlabeler.app.i18n.L("Size and scale", "Размер и масштаб")

/**
 * The inside of a dialog: [content] scrolls, [footer] (the final buttons, right-aligned) always stays visible
 * at the bottom, however small the window is.
 */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.DialogContent(
    footer: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier.weight(1f, fill = false).scrollWithHint()
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (footer == null) 18.dp else 6.dp),
        content = content,
    )
    if (footer != null) {
        Box(Modifier.fillMaxWidth().height(T.c.borderWidth).background(T.c.border.copy(alpha = 0.5f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
            content = footer,
        )
    }
}

@Composable
fun Overlay(onDismiss: () -> Unit, maxWidth: Int = 560, dim: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // a new interface size lays the window out afresh: otherwise it keeps its old height in pixels and gets cramped
    // a short fade and lift when it opens (when the interface may move)
    val on = Motion.on()
    val full = Motion.full()
    val appear = remember { androidx.compose.animation.core.Animatable(if (on) 0f else 1f) }
    val ms = Motion.ms(170)
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.tween(ms)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = appear.value
        if (full) translationY = (1f - appear.value) * 10.dp.toPx()
    }) {
        androidx.compose.runtime.key(androidx.compose.ui.platform.LocalDensity.current.density) { OverlayBox(onDismiss, maxWidth, dim, content) }
    }
}

@Composable
private fun OverlayBox(onDismiss: () -> Unit, maxWidth: Int, dim: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = T.c
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = if (dim) 0.6f else 0f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
            .windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
        contentAlignment = Alignment.TopCenter,
    ) {
        val narrow = this.maxWidth < 600.dp
        if (narrow || mlabeler.app.Platform.isMobile) {
            Card(
                (if (narrow) Modifier.fillMaxSize() else Modifier.padding(top = 56.dp).widthIn(max = maxWidth.dp).fillMaxWidth().heightIn(max = this.maxHeight - 96.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {},
            ) { Column { content() } }
            return@BoxWithConstraints
        }
        // computers: the window can be moved by its top strip and resized by the corner
        val density = androidx.compose.ui.platform.LocalDensity.current
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var size by remember { mutableStateOf<androidx.compose.ui.unit.DpSize?>(null) }
        val boxW = this.maxWidth
        val boxH = this.maxHeight
        // a dragged-in corner stops at a size where the window still reads well (or the whole screen, if smaller)
        val minW = minOf(maxWidth, 480).dp
        val minH = 360.dp
        Box(
            Modifier.padding(top = 56.dp).offset { androidx.compose.ui.unit.IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .then(size?.let { Modifier.size(it.width.coerceIn(minOf(minW, boxW), boxW), it.height.coerceIn(minOf(minH, boxH), boxH)) }
                    ?: Modifier.widthIn(max = maxWidth.dp).fillMaxWidth().heightIn(max = boxH - 96.dp)),
        ) {
            // as tall as the content until the corner is dragged, then exactly the dragged size
            Card((if (size != null) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).clickable(remember { MutableInteractionSource() }, null) {}) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(12.dp)
                            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                            .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); offset += d } },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.border)) }
                    Column(Modifier.weight(1f, fill = false)) { content() }
                }
            }
            var cardSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Box(Modifier.matchParentSize().onSizeChanged { cardSize = it })
            Box(
                Modifier.align(Alignment.BottomEnd).size(16.dp)
                    .pointerHoverIcon(mlabeler.app.resizeHorizontalIcon)
                    .pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            val cur = size ?: with(density) { androidx.compose.ui.unit.DpSize(cardSize.width.toDp(), cardSize.height.toDp()) }
                            size = with(density) {
                                androidx.compose.ui.unit.DpSize(
                                    (cur.width + d.x.toDp()).coerceIn(minOf(minW, boxW), boxW),
                                    (cur.height + d.y.toDp()).coerceIn(minOf(minH, boxH), boxH),
                                )
                            }
                        }
                    },
            ) {
                Text("◢", color = c.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp))
            }
        }
    }
}

@Composable
fun CommandPalette(app: AppState) {
    val c = T.c
    val ed = app.editor ?: return
    var query by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = Commands.forPalette(ed.mode).filter { query.isBlank() || it.title().contains(query.trim(), ignoreCase = true) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(index) { if (list.isNotEmpty()) state.scrollToItem(index.coerceIn(0, list.size - 1)) }
    fun runAt(i: Int) {
        val cmd = list.getOrNull(i) ?: return
        app.showCommands = false
        if (cmd.enabled(ed)) cmd.run(ed, app)
        ed.requestFocus()
    }
    Overlay({ app.showCommands = false; ed.requestFocus() }, 520) {
        Field(
            query, { query = it; index = 0 },
            Modifier.fillMaxWidth().padding(10.dp).focusRequester(focus).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> { index = (index + 1).coerceAtMost(list.size - 1); true }
                    Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                    Key.Enter, Key.NumPadEnter -> { runAt(index); true }
                    else -> false
                }
            },
            placeholder = S.typeCommand(),
            onDone = { runAt(index) },
        )
        Divider()
        LazyColumn(Modifier.weight(1f, fill = false), state = state) {
            itemsIndexed(list) { i, cmd ->
                Row(
                    Modifier.fillMaxWidth().background(if (i == index) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                        .clickable { runAt(i) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (i == index && c.square) c.onAccent else c.text
                    Text(cmd.title(), color = if (cmd.enabled(ed)) fg else c.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(cmd.keyLabel, color = if (i == index && c.square) c.onAccent else c.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private enum class Section { General, Interface, Themes, View, Spectrogram, Editing, Mouse, Checks, Toolkit, Keys, About }

@Composable
private fun sectionTitle(s: Section) = when (s) {
    Section.General -> S.general()
    Section.Interface -> MenuTitles.interfaceSet()
    Section.Themes -> S.theme()
    Section.View -> S.view()
    Section.Spectrogram -> S.spectrogramSection()
    Section.Editing -> S.editing()
    Section.Mouse -> MouseTitles.page()
    Section.Checks -> S.checks()
    Section.Toolkit -> S.toolkit()
    Section.Keys -> S.shortcuts()
    Section.About -> S.about()
}

/** Settings split into pages: a list on the left on wide windows, tabs on top on narrow ones. */
@Composable
fun SettingsDialog(app: AppState) {
    val c = T.c
    var section by remember {
        mutableStateOf(Section.entries.firstOrNull { it.name.equals(app.settingsPage, ignoreCase = true) } ?: Section.General)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.settingsPage = "" } }
    var query by remember { mutableStateOf("") }
    var focusTitle by remember { mutableStateOf<String?>(null) }
    val folds = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    Overlay({ app.showSettings = false; app.editor?.requestFocus?.invoke() }, 820, dim = app.settings.settingsDim) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // on a small window the search goes under the title instead of squeezing it
            val tight = maxWidth < 460.dp
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(S.settings(), color = c.text, fontSize = 18.sp, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!tight) Field(query, { query = it }, Modifier.width(220.dp), placeholder = searchSettingsT())
                    IconBtn(Icons.close, S.close()) { app.showSettings = false }
                }
                if (tight) Field(query, { query = it }, Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp), placeholder = searchSettingsT())
            }
        }
        Divider()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp)) {
            val narrow = maxWidth < 560.dp
            if (query.isNotBlank()) {
                // what was found: a click opens the page with the setting highlighted
                val found = SettingsHelp.search(query)
                Column(Modifier.fillMaxWidth().heightIn(max = minOf(800.dp, maxHeight)).scrollWithHint().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    if (found.isEmpty()) Text(S.nothingFound(), color = c.muted, fontSize = 13.sp)
                    for (e in found) {
                        val sec = Section.entries.firstOrNull { it.name == e.section } ?: continue
                        Column(Modifier.fillMaxWidth().clickable { section = sec; focusTitle = e.title(); query = "" }.padding(vertical = 6.dp)) {
                            Text(sectionTitle(sec) + " · " + e.title(), color = c.text, fontSize = 13.sp)
                            Text(e.hint(), color = c.muted, fontSize = 12.sp)
                        }
                    }
                }
            } else androidx.compose.runtime.CompositionLocalProvider(LocalSettingFocus provides focusTitle, LocalFolds provides folds) {
            if (narrow) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) { for (s in Section.entries) Chip(sectionTitle(s), s == section) { section = s; focusTitle = null } }
                    Divider()
                    SettingsPage(app, section, Modifier.fillMaxWidth().weight(1f, fill = false))
                }
            } else {
                Row(Modifier.fillMaxWidth().height(minOf(800.dp, maxHeight))) {
                    Column(Modifier.width(190.dp).fillMaxHeight().background(c.panelAlt).scrollWithHint().padding(vertical = 8.dp)) {
                        for (s in Section.entries) {
                            val sel = s == section
                            Text(
                                sectionTitle(s),
                                color = if (sel && c.square) c.onAccent else if (sel) c.accent else c.text,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (sel) c.accent.copy(alpha = if (c.square) 1f else 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { section = s; focusTitle = null }.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Divider(vertical = true)
                    SettingsPage(app, section, Modifier.weight(1f))
                }
            }
            }
        }
    }
}

@Composable
private fun SettingsPage(app: AppState, section: Section, modifier: Modifier) {
    val c = T.c
    val s = app.settings
    val dE = mlabeler.app.state.EditSettings()
    val dV = mlabeler.app.state.ViewSettings()
    val dL = mlabeler.app.state.LayoutSettings()
    val dC = mlabeler.core.check.CheckSettings()
    val dScale = if (mlabeler.app.Platform.isMobile) 0.8f else 1f
    Column(modifier.scrollWithHint().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        when (section) {
            Section.General -> {
                Fold(S.language()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                    }
                }
                if (mlabeler.app.Platform.isMobile) Fold(S.screen()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((v, t) in listOf("landscape" to S.landscape(), "portrait" to S.portrait(), "auto" to S.autoRotate())) Chip(t, s.orientation == v) { app.update { it.copy(orientation = v) } }
                    }
                    SwitchRow(S.fullscreen(), s.fullscreen) { v -> app.update { it.copy(fullscreen = v) } }
                    if (s.fullscreen) SwitchRow(S.avoidCutout(), s.avoidCutout) { v -> app.update { it.copy(avoidCutout = v) } }
                }
                Fold(S.files()) {
                    SwitchRow(S.saveOnSwitch(), s.edit.saveOnSwitch) { v -> app.update { it.copy(edit = it.edit.copy(saveOnSwitch = v)) } }
                    SwitchRow(S.otherAudio(), s.otherAudio) { v -> app.update { it.copy(otherAudio = v) } }
                    ValueSlider(S.autosave(), s.edit.autosaveSeconds.toFloat(), 0f..300f, S.secondsShort(), default = dE.autosaveSeconds.toFloat()) { v -> app.update { it.copy(edit = it.edit.copy(autosaveSeconds = (v / 10).roundToInt() * 10)) } }
                }
            }
            Section.View -> {
                Fold(scaleT()) {
                    ValueSlider(S.interfaceScale(), s.scale, 0.5f..2f, "%", factor = 100f, default = dScale, live = false) { v -> app.update { it.copy(scale = (v * 100).roundToInt() / 100f) } }
                    SwitchRow(scaleButtonT(), s.toolbar.scaleButton ?: mlabeler.app.Platform.isMobile) { v -> app.update { it.copy(toolbar = it.toolbar.copy(scaleButton = v)) } }
                    SwitchRow(keepZoomT(), s.edit.keepZoom) { v -> app.update { it.copy(edit = it.edit.copy(keepZoom = v)) } }
                }
                Fold(whatToShow()) {
                    SwitchRow(S.overlay(), s.layout.overlay) { v -> app.update { it.copy(layout = it.layout.copy(overlay = v)) } }
                    SwitchRow(namesOnAudioT(), s.layout.namesOnAudio) { v -> app.update { it.copy(layout = it.layout.copy(namesOnAudio = v)) } }
                    if (s.layout.namesOnAudio) {
                        Text(namesWhereT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                        PlacementPad(s.layout.namesX, s.layout.namesY) { x, y -> app.update { it.copy(layout = it.layout.copy(namesX = x, namesY = y)) } }
                    }
                    ValueSlider(S.labelFontSize(), s.layout.labelFontSize, 8f..48f, "sp", default = dL.labelFontSize) { v -> app.update { it.copy(layout = it.layout.copy(labelFontSize = v.roundToInt().toFloat())) } }
                    if (s.layout.overlay) ValueSlider(S.overlayWaveFillAlpha(), s.layout.overlayWaveFillAlpha, 0.05f..1f, "%", factor = 100f,
                        default = mlabeler.app.state.LayoutSettings().overlayWaveFillAlpha) { v -> app.update { it.copy(layout = it.layout.copy(overlayWaveFillAlpha = v)) } }
                    if (s.layout.overlay) ValueSlider(S.overlayDim(), s.layout.overlayDim, 0f..0.8f, "%", factor = 100f, default = dL.overlayDim) { v -> app.update { it.copy(layout = it.layout.copy(overlayDim = v)) } }
                    SwitchRow(S.tiersOnTop(), s.layout.tiersOnTop) { v -> app.update { it.copy(layout = it.layout.copy(tiersOnTop = v)) } }
                    SwitchRow(S.waveform(), s.layout.showWaveform) { v -> app.update { it.copy(layout = it.layout.copy(showWaveform = v)) } }
                    SwitchRow(S.spectrogram(), s.layout.showSpectrogram) { v -> app.update { it.copy(layout = it.layout.copy(showSpectrogram = v)) } }
                    SwitchRow(S.pitch(), s.layout.showPitch) { v -> app.update { it.copy(layout = it.layout.copy(showPitch = v)) } }
                    if (s.layout.showPitch) SwitchRow(S.pitchOver(), s.layout.pitchOverSpectrogram) { v -> app.update { it.copy(layout = it.layout.copy(pitchOverSpectrogram = v)) } }
                    SwitchRow(S.power(), s.layout.showPower) { v -> app.update { it.copy(layout = it.layout.copy(showPower = v)) } }
                    SwitchRow(Commands.formants.title(), s.layout.showFormants) { v -> app.update { it.copy(layout = it.layout.copy(showFormants = v)) } }
                    SwitchRow(S.toggleFiles(), s.layout.showFiles) { v -> app.update { it.copy(layout = it.layout.copy(showFiles = v)) } }
                    SwitchRow(S.toggleInspector(), s.layout.showInspector) { v -> app.update { it.copy(layout = it.layout.copy(showInspector = v)) } }
                }
            }
            Section.Spectrogram -> {
                Fold(S.colors()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(S.theme(), s.view.palette.isEmpty()) { app.update { it.copy(view = it.view.copy(palette = "")) } }
                        for (p in Themes.palettes.keys) Chip(p, s.view.palette == p) { app.update { it.copy(view = it.view.copy(palette = p)) } }
                    }
                    ValueSlider(S.brightness(), s.view.brightness, -0.5f..0.5f, factor = 100f, default = dV.brightness) { v -> app.update { it.copy(view = it.view.copy(brightness = v)) } }
                    ValueSlider(S.contrast(), s.view.contrast, 0.5f..3f, "%", factor = 100f, default = dV.contrast) { v -> app.update { it.copy(view = it.view.copy(contrast = v)) } }
                }
                Fold(detailT()) {
                    Text(detailHint(), color = c.muted, fontSize = 12.sp)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((name, preset) in detailPresets) {
                            val (w, hop, bands) = preset
                            Chip(name(), s.view.windowMs == w && s.view.hopMs == hop && s.view.bands == bands) {
                                app.update { it.copy(view = it.view.copy(windowMs = w, hopMs = hop, bands = bands)) }
                            }
                        }
                    }
                    ValueSlider(S.windowMs(), s.view.windowMs, 5f..80f, S.msUnit(), default = dV.windowMs) { v -> app.update { it.copy(view = it.view.copy(windowMs = v.roundToInt().toFloat())) } }
                    ValueSlider(S.hopMs(), s.view.hopMs, 0f..20f, S.msUnit(), decimals = 1, default = dV.hopMs) { v -> app.update { it.copy(view = it.view.copy(hopMs = (v * 2).roundToInt() / 2f)) } }
                    ValueSlider(S.bands(), s.view.bands.toFloat(), 64f..384f, default = dV.bands.toFloat()) { v -> app.update { it.copy(view = it.view.copy(bands = (v / 32).roundToInt() * 32)) } }
                    ValueSlider(S.dbRange(), s.view.minDb, -140f..-40f, "dB", default = dV.minDb) { v -> app.update { it.copy(view = it.view.copy(minDb = v.roundToInt().toFloat())) } }
                    ValueSlider(S.dbTop(), s.view.maxDb, -40f..10f, "dB", default = dV.maxDb) { v -> app.update { it.copy(view = it.view.copy(maxDb = v.roundToInt().toFloat())) } }
                    ValueSlider(S.maxFrequency(), s.view.maxFreq, 1000f..24000f, S.hzShort(), default = dV.maxFreq) { v -> app.update { it.copy(view = it.view.copy(maxFreq = v.roundToInt().toFloat())) } }
                }
            }
            Section.Editing -> {
                Fold(S.editing()) {
                    ValueSlider(S.nudgeStep(), s.edit.nudgeMs, 1f..50f, S.msUnit(), default = dE.nudgeMs) { v -> app.update { it.copy(edit = it.edit.copy(nudgeMs = v.roundToInt().toFloat())) } }
                    ValueSlider(S.minInterval(), s.edit.minIntervalMs, 0f..20f, S.msUnit(), default = dE.minIntervalMs) { v -> app.update { it.copy(edit = it.edit.copy(minIntervalMs = v.roundToInt().toFloat())) } }
                    SwitchRow(S.ripple() + " — " + S.rippleHint(), s.edit.ripple) { v -> app.update { it.copy(edit = it.edit.copy(ripple = v)) } }
                    SwitchRow(S.linked() + " — " + S.linkedHint(), s.edit.linked) { v -> app.update { it.copy(edit = it.edit.copy(linked = v)) } }
                    SwitchRow(S.loop(), s.edit.loop) { v -> app.update { it.copy(edit = it.edit.copy(loop = v)) } }
                    SwitchRow(snapZeroT(), s.edit.snapToZero) { v -> app.update { it.copy(edit = it.edit.copy(snapToZero = v)) } }
                }
                Fold(PlayTitles.playback()) {
                    ValueSlider(PlayTitles.volume(), s.edit.volume, 0f..1f, "%", factor = 100f, default = dE.volume) { v -> app.update { it.copy(edit = it.edit.copy(volume = v)) } }
                    Text(PlayTitles.follow(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(PlayTitles.followOff(), s.edit.follow == "off") { app.update { it.copy(edit = it.edit.copy(follow = "off")) } }
                        Chip(PlayTitles.followPage(), s.edit.follow == "page") { app.update { it.copy(edit = it.edit.copy(follow = "page")) } }
                        Chip(PlayTitles.followKeep(), s.edit.follow == "keep") { app.update { it.copy(edit = it.edit.copy(follow = "keep")) } }
                    }
                    if (s.edit.follow == "keep") ValueSlider(PlayTitles.followAt(), s.edit.followAt, 0f..1f, "%", factor = 100f, default = dE.followAt) { v -> app.update { it.copy(edit = it.edit.copy(followAt = v)) } }
                    ValueSlider(S.speedSetting(), s.edit.speed, 0.1f..1f, "×", decimals = 2, default = dE.speed) { v -> app.update { it.copy(edit = it.edit.copy(speed = (v * 100).roundToInt() / 100f)) } }
                    SwitchRow(S.playOnDrag(), s.edit.playOnDrag) { v -> app.update { it.copy(edit = it.edit.copy(playOnDrag = v)) } }
                    SwitchRow(S.otoLocked(), s.edit.otoLockedDrag) { v -> app.update { it.copy(edit = it.edit.copy(otoLockedDrag = v)) } }
                    SwitchRow(MouseTitles.spaceRestarts(), s.edit.spaceRestarts) { v -> app.update { it.copy(edit = it.edit.copy(spaceRestarts = v)) } }
                    Text(MouseTitles.owner(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(MouseTitles.ownerHint(), color = c.muted, fontSize = 12.sp)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(MouseTitles.ownerEnd(), s.edit.boundaryOwner == "end") { app.update { it.copy(edit = it.edit.copy(boundaryOwner = "end")) } }
                        Chip(MouseTitles.ownerStart(), s.edit.boundaryOwner != "end") { app.update { it.copy(edit = it.edit.copy(boundaryOwner = "start")) } }
                    }
                }
            }
            Section.Mouse -> MousePage(app)
            Section.Checks -> {
                Fold(S.checks()) {
                    ValueSlider(S.shortThreshold(), s.checks.minDurationMs.toFloat(), 0f..150f, S.msUnit(), default = dC.minDurationMs.toFloat()) { v ->
                        app.update { it.copy(checks = it.checks.copy(minDurationMs = v.roundToInt().toDouble())) }
                    }
                    var phonemes by remember { mutableStateOf(s.checks.phonemeSet.joinToString(" ")) }
                    Text(S.phonemeSet(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Field(phonemes, { v ->
                        phonemes = v
                        app.update { it.copy(checks = it.checks.copy(phonemeSet = v.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }.toSet())) }
                    }, Modifier.fillMaxWidth())
                    Text(CheckTitles.fromDict(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    fun setOf(d: mlabeler.core.ds.PhonemeDict) = (d.rests + d.vowels + d.semivowels + d.special + d.consonants).toSet()
                    val chosen = remember(s.checks.phonemeSet) { mlabeler.app.state.Dictionaries.all().firstOrNull { it.vowels.isNotEmpty() && setOf(it) == s.checks.phonemeSet }?.name ?: "" }
                    DictionaryChips(chosen, autoTitle = CheckTitles.anyPhoneme()) { name ->
                        val set = if (name.isEmpty()) emptySet() else setOf(mlabeler.app.state.Dictionaries.byName(name))
                        phonemes = set.joinToString(" ")
                        app.update { it.copy(checks = it.checks.copy(phonemeSet = set)) }
                    }
                    ValueSlider(CheckTitles.maxLen(), s.checks.maxDurationMs.toFloat(), 0f..3000f, S.msUnit(), default = 0f) { v ->
                        app.update { it.copy(checks = it.checks.copy(maxDurationMs = ((v / 10).roundToInt() * 10).toDouble())) }
                    }
                    ValueSlider(CheckTitles.maxPause(), s.checks.maxPauseSeconds.toFloat(), 0f..30f, S.secondsShort(), decimals = 1, default = 0f) { v ->
                        app.update { it.copy(checks = it.checks.copy(maxPauseSeconds = (v * 10).roundToInt() / 10.0)) }
                    }
                    ValueSlider(CheckTitles.maxPhrase(), s.checks.maxPhraseSeconds.toFloat(), 0f..60f, S.secondsShort(), decimals = 1, default = 0f) { v ->
                        app.update { it.copy(checks = it.checks.copy(maxPhraseSeconds = (v * 10).roundToInt() / 10.0)) }
                    }
                    if (s.checks.maxPhraseSeconds > 0) ValueSlider(CheckTitles.phrasePause(), s.checks.phrasePauseMs.toFloat(), 50f..1000f, S.msUnit(), default = 200f) { v ->
                        app.update { it.copy(checks = it.checks.copy(phrasePauseMs = v.roundToInt().toDouble())) }
                    }
                    Text(CheckTitles.zeroOff(), color = c.muted, fontSize = 12.sp)
                    SwitchRow(CheckTitles.diffsinger(), s.checks.diffsinger) { v -> app.update { it.copy(checks = it.checks.copy(diffsinger = v)) } }
                    SwitchRow(CheckTitles.edges(), s.checks.pauseAtEdges) { v -> app.update { it.copy(checks = it.checks.copy(pauseAtEdges = v)) } }
                    if (s.checks.pauseAtEdges) {
                        var edge by remember { mutableStateOf(s.checks.edgePause) }
                        Text(CheckTitles.edgeWhich(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                        Field(edge, { v -> edge = v; app.update { it.copy(checks = it.checks.copy(edgePause = v.trim())) } }, Modifier.width(160.dp))
                    }
                }
                Fold(CheckTitles.scripts()) {
                    Text(CheckTitles.scriptsHint.format(mlabeler.app.plugins.CheckScripts.appDir(), ".mlabeler/checks"), color = c.muted, fontSize = 12.sp)
                    SwitchRow(CheckTitles.runScripts(), s.checks.scripts) { v -> app.update { it.copy(checks = it.checks.copy(scripts = v)) }; app.editor?.reloadCheckScripts() }
                    val ed = app.editor
                    if (ed != null) {
                        Text(if (ed.checkScripts.isEmpty()) CheckTitles.none() else ed.checkScripts.joinToString(", ") { it.name }, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn(CheckTitles.example()) {
                            val p = runCatching { mlabeler.app.plugins.CheckScripts.writeExample() }.getOrNull()
                            app.editor?.reloadCheckScripts()
                            if (p != null) { app.message(CheckTitles.written.format(p)); if (!mlabeler.app.Platform.isMobile) mlabeler.app.Platform.openInFileManager(mlabeler.app.plugins.CheckScripts.appDir()) }
                        }
                        Btn(CheckTitles.reload()) { app.editor?.reloadCheckScripts() }
                    }
                }
            }
            Section.Toolkit -> ToolkitPage(app)
            Section.Interface -> InterfacePage(app)
            Section.Themes -> ThemesPage(app)
            Section.Keys -> KeymapPage(app)
            Section.About -> AboutPage(app)
        }
        // every page can go back to the defaults (with a second click to confirm)
        val reset: ((mlabeler.app.state.AppSettings) -> mlabeler.app.state.AppSettings)? = when (section) {
            Section.General -> { st -> st.copy(otherAudio = false, edit = st.edit.copy(saveOnSwitch = dE.saveOnSwitch, autosaveSeconds = dE.autosaveSeconds)) }
            Section.View -> { st -> st.copy(scale = dScale, font = "", layout = dL.copy(showFiles = st.layout.showFiles, showInspector = st.layout.showInspector,
                filesWidth = st.layout.filesWidth, inspectorWidth = st.layout.inspectorWidth)) }
            Section.Spectrogram -> { st -> st.copy(view = dV) }
            Section.Editing -> { st -> st.copy(edit = dE.copy(tool = st.edit.tool, tools = st.edit.tools, cutAskName = st.edit.cutAskName, cutPlay = st.edit.cutPlay,
                playOnDrag = st.edit.playOnDrag, newFormat = st.edit.newFormat)) }
            Section.Mouse -> { st -> st.copy(mouse = mlabeler.app.state.MouseSettings(), edit = st.edit.copy(tool = dE.tool, tools = dE.tools, cutAskName = dE.cutAskName,
                cutPlay = dE.cutPlay, playOnDrag = dE.playOnDrag, selectAfterDrag = dE.selectAfterDrag, audioClickDeselects = dE.audioClickDeselects)) }
            Section.Checks -> { st -> st.copy(checks = dC) }
            Section.Toolkit -> { st -> st.copy(toolkit = mlabeler.app.state.ToolkitSettings(lastModel = st.toolkit.lastModel,
                lastLanguage = st.toolkit.lastLanguage, lastSegmentModel = st.toolkit.lastSegmentModel)) }
            Section.Themes -> { st -> st.copy(theme = "modern-dark", font = "") }
            Section.Keys -> { st -> Keymap.load(emptyMap()); st.copy(keymap = emptyMap()) }
            Section.Interface -> { st -> mlabeler.app.state.Environments.byId(st.environment)?.let { e -> mlabeler.app.state.Environments.apply(st, e) } ?: st }
            Section.About -> null
        }
        if (reset != null) {
            var confirm by remember(section) { mutableStateOf(false) }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!confirm) Btn(S.resetPage()) { confirm = true }
                else {
                    Btn(S.resetPage() + "?", primary = true) { confirm = false; app.update(reset) }
                    Btn(S.cancel()) { confirm = false }
                }
            }
        }
        if (section == Section.General) { Spacer(Modifier.height(28.dp)); DangerZone(app) }
    }
}

@Composable
internal fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = T.c
    Row(Modifier.fillMaxWidth().settingFocus(title).clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            SettingHelpMark(title)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(value, onChange)
    }
}

@Suppress("unused")
@Composable
private fun Gap() = Box(Modifier.height(8.dp))

private val pressKeys = mlabeler.app.i18n.L("Press the keys…", "Нажмите клавиши…")
private val resetAll = mlabeler.app.i18n.L("Reset all", "Сбросить все")
private val keysHint = mlabeler.app.i18n.L("Click a command and press new keys. Esc cancels, Backspace removes the binding.",
    "Нажмите на команду, затем новые клавиши. Esc — отмена, Backspace — убрать сочетание.")
private val usedBy = mlabeler.app.i18n.L("also used by: {0}", "также используется: {0}")

@Composable
private fun KeymapPage(app: AppState) {
    val c = T.c
    var capturing by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun save(map: Map<String, List<Chord>>) {
        Keymap.overrides = map
        app.update { it.copy(keymap = Keymap.toSettings()) }
    }
    androidx.compose.runtime.LaunchedEffect(capturing) { if (capturing != null) runCatching { focus.requestFocus() } }
    Column(
        Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            val id = capturing ?: return@onPreviewKeyEvent false
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            val k = e.key
            if (k in setOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight)) return@onPreviewKeyEvent true
            when (k) {
                Key.Escape -> capturing = null
                Key.Backspace -> { save(Keymap.overrides + (id to emptyList<Chord>())); capturing = null }
                else -> {
                    val ctrl = if (mlabeler.app.Platform.isMac) e.isMetaPressed else e.isCtrlPressed
                    save(Keymap.overrides + (id to listOf<Chord>(Chord(k, ctrl, e.isShiftPressed, e.isAltPressed))))
                    capturing = null
                }
            }
            true
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(S.shortcuts(), Modifier.weight(1f))
            Btn(resetAll()) { save(emptyMap()) }
        }
        Text(keysHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        for (cmd in Commands.all) {
            val conflicts = cmd.keys.flatMap { k -> Commands.all.filter { o -> o !== cmd && o.keys.contains(k) && (o.mode == null || cmd.mode == null || o.mode == cmd.mode) } }
            Column(
                Modifier.fillMaxWidth().clickable { capturing = cmd.id }
                    .background(if (capturing == cmd.id) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            ) {
                Row {
                    Text(cmd.title(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (capturing == cmd.id) pressKeys() else cmd.keys.joinToString("   ") { it.label() }.ifEmpty { "—" },
                        color = if (capturing == cmd.id) c.accent else if (Keymap.overrides.containsKey(cmd.id)) c.text else c.muted, fontSize = 12.sp,
                    )
                }
                if (conflicts.isNotEmpty()) Text(usedBy.format(conflicts.joinToString { it.title() }), color = c.warn, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ToolkitPage(app: AppState) {
    val c = T.c
    val s = app.settings
    val tk = app.toolkit
    Fold(S.toolkit()) {
        Text(S.toolkitHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        mlabeler.app.Platform.portableDir?.let { PortableNote(it) }
        if (mlabeler.app.Platform.legacyWindows) LegacyNote()
        ToolkitStatus(app, reinstall = true)
        if (tk.canRunHere) Row(Modifier.padding(top = 6.dp)) {
            Btn(ErrorTitles.updateToolkit(), enabled = !tk.updatingNow) { tk.updateNow() }
        }
    }
    OwnModelsSection(app)
    ToolkitStorageSection(app)
    Fold(S.toolkitUrl()) {
        var url by remember { mutableStateOf(s.toolkit.url) }
        var token by remember { mutableStateOf(s.toolkit.token) }
        Field(url, { url = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(url = it.trim())) } }, Modifier.fillMaxWidth())
        Text(S.toolkitToken(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Field(token, { token = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(token = it.trim())) } }, Modifier.fillMaxWidth())
        if (mlabeler.app.toolkit.LocalToolkit.supported) {
            SwitchRow(S.toolkitAutoStart(), s.toolkit.autoStart) { v -> app.update { it.copy(toolkit = it.toolkit.copy(autoStart = v)) } }
            SwitchRow(S.toolkitShare(), s.toolkit.shareOnNetwork) { v ->
                app.update { it.copy(toolkit = it.toolkit.copy(shareOnNetwork = v)) }
                if (tk.ownProcess || (v && tk.status != mlabeler.app.toolkit.ToolkitManager.Status.Ready)) tk.restart()
            }
            if (s.toolkit.shareOnNetwork) {
                val ips = remember { mlabeler.app.toolkit.LocalToolkit.lanAddresses() }
                Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(c.panelAlt).padding(10.dp)) {
                    Text(S.toolkitShareHint(), color = c.muted, fontSize = 12.sp)
                    for (ip in ips.ifEmpty { listOf("?") }) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text("http://$ip:${tk.port}", color = c.text, fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(S.toolkitToken() + ": " + tk.networkToken.ifEmpty { "…" }, color = c.text, fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (!tk.ownProcess) Text(S.toolkitShareOwn(), color = c.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            var more by remember { mutableStateOf(false) }
            Row(Modifier.padding(top = 10.dp)) { Chip(S.more(), more) { more = !more } }
            if (more) {
                var mvt by remember { mutableStateOf(s.toolkit.mvtPath) }
                var src by remember { mutableStateOf(s.toolkit.installSource) }
                Text(S.toolkitMvtPath(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Field(mvt, { mvt = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(mvtPath = it.trim())) } }, Modifier.fillMaxWidth(),
                    placeholder = mlabeler.app.toolkit.LocalToolkit.findMvt("") ?: "mvt")
                Text(S.toolkitSource(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Field(src, { src = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(installSource = it.trim())) } }, Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(S.toolkitReinstall(), enabled = !tk.installing) { tk.stop(); tk.install() }
                }
            }
        }
    }
}

@Composable
private fun InterfacePage(app: AppState) {
    val c = T.c
    val s = app.settings
    Fold(S.environments()) { EnvironmentsSection(app) }
    Fold(MenuTitles.panels()) {
        if (!mlabeler.app.Platform.isMobile) SwitchRow(MenuTitles.menuBar(), s.menuBar) { v -> app.update { it.copy(menuBar = v) } }
        if (!mlabeler.app.Platform.isMobile) SwitchRow(titleBarMenuT(), s.titleBarMenu) { v -> app.update { it.copy(titleBarMenu = v) }; app.message(restartT()) }
        SwitchRow(MenuTitles.statusBar(), s.statusBar) { v -> app.update { it.copy(statusBar = v) } }
        SwitchRow(MenuTitles.filesPanel(), s.layout.showFiles) { v -> app.update { it.copy(layout = it.layout.copy(showFiles = v)) } }
        SwitchRow(MenuTitles.detailsPanel(), s.layout.showInspector) { v -> app.update { it.copy(layout = it.layout.copy(showInspector = v)) } }
        SwitchRow(tipsAtStartT(), s.tipsAtStart) { v -> app.update { it.copy(tipsAtStart = v) } }
        SwitchRow(minimapT(), s.layout.minimap) { v -> app.update { it.copy(layout = it.layout.copy(minimap = v)) } }
        SwitchRow(phonemeColorsT(), s.layout.phonemeColors) { v -> app.update { it.copy(layout = it.layout.copy(phonemeColors = v)) } }
    }
    Fold(animationsT()) {
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, t) in listOf("normal" to animNormalT(), "reduced" to animReducedT(), "off" to animOffT())) Chip(t, s.animations == v) { app.update { it.copy(animations = v) } }
        }
        Text(animationsHint(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    if (s.statusBar) Fold(MenuTitles.statusBar()) {
        StatusBarSettings(app) { t, v, f -> SwitchRow(t, v, f) }
    }
    Fold(MenuTitles.toolbar()) {
        SwitchRow(MenuTitles.buttonLabels(), s.toolbar.labels) { v -> app.update { it.copy(toolbar = it.toolbar.copy(labels = v)) } }
        SwitchRow(MenuTitles.bigButtons(), s.toolbar.big) { v -> app.update { it.copy(toolbar = it.toolbar.copy(big = v)) } }
        Text(S.toolbarHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        // a fixed list: ticking a group on or off doesn't move it
        val order = s.toolbar.fullOrder()
        for ((i, g) in order.withIndex()) {
            val on = g in s.toolbar.groups
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.checkboxes) Toggle(on, { v -> app.update { st -> st.copy(toolbar = st.toolbar.toggled(g, v)) } }, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                else androidx.compose.material3.Checkbox(on, { v -> app.update { st -> st.copy(toolbar = st.toolbar.toggled(g, v)) } },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.muted, checkmarkColor = c.onAccent))
                Text(ToolLabels.group(g)(), color = if (on) c.text else c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.up, S.moveUp(), enabled = i > 0, size = 28.dp) { app.update { st -> st.copy(toolbar = st.toolbar.moved(g, -1)) } }
                IconBtn(Icons.down, S.moveDown(), enabled = i < order.size - 1, size = 28.dp) { app.update { st -> st.copy(toolbar = st.toolbar.moved(g, 1)) } }
            }
        }
    }
}

private fun swapAt(l: MutableList<String>, a: Int, b: Int) {
    if (a !in l.indices || b !in l.indices) return
    val t = l[a]; l[a] = l[b]; l[b] = t
}

object MouseTitles {
    val page = L("Mouse", "Мышь")
    val tool = L("Clicks", "Щелчки")
    val click = L("Click", "Щелчок")
    val toolsOn = L("Mouse tools (scissors, hand, play) — keys 1–4 and a toolbar button",
        "Инструменты мыши (ножницы, рука, проигрывание) — клавиши 1–4 и кнопка на панели")
    val toolsOff = L("Off: the left button always works as the cursor. Right and middle clicks keep their own actions below.",
        "Выключены: левая кнопка всегда работает как курсор. У правой и средней кнопок остаются свои действия ниже.")
    val cursor = L("Cursor: click selects, drag moves (1)", "Курсор: клик выбирает, перетаскивание двигает (1)")
    val cut = L("Scissors: click adds a boundary (2)", "Ножницы: клик ставит границу (2)")
    val pan = L("Hand: dragging scrolls (3)", "Рука: перетаскивание прокручивает (3)")
    val playTool = L("Play: a click plays the phoneme (4)", "Проигрывание: клик воспроизводит фонему (4)")
    val cutOnLanes = L("Scissors cut on label lanes too (otherwise clicks there do what is set below)",
        "Ножницы режут и на полосах разметки (иначе щелчки там выполняют действия, заданные ниже)")
    val wheel = L("Mouse wheel", "Колесо мыши")
    val wheelScroll = L("Scrolls through time (Ctrl+wheel zooms)", "Прокручивает по времени (Ctrl+колесо — масштаб)")
    val wheelPhonemes = L("Steps through phonemes, Space plays the chosen one (Shift+wheel scrolls)",
        "Переходит по фонемам, пробел воспроизводит выбранную (Shift+колесо — прокрутка)")
    val toolHint = L("Near a boundary both tools drag it. Shift+click and dragging over the audio select a part in both.",
        "Рядом с границей оба инструмента её перемещают. Shift+клик и перетаскивание по звуку выделяют фрагмент в обоих.")
    val askName = L("Type the name of the new part right away", "Сразу вводить название новой части")
    val playIt = L("Play the part before a new boundary", "Проигрывать часть перед новой границей")
    val leftHint = L("Near a boundary a press always drags it; Shift+click and dragging over the audio select a part. Each click below can be set to select, split, play and more.",
        "Рядом с границей нажатие всегда перемещает её; Shift+щелчок и перетаскивание по звуку выделяют фрагмент. Каждому щелчку ниже можно назначить действие: выбрать, разрезать, воспроизвести и другие.")
    val onLabels = L("On label lanes", "На полосах разметки")
    val onAudio = L("On the waveform and spectrogram", "На волне и спектрограмме")
    val double = L("Double click", "Двойной клик")
    val right = L("Right click", "Правый клик")
    val middle = L("Middle click (dragging with it scrolls)", "Средний клик (с перетаскиванием — прокрутка)")
    val ctrl = L("Ctrl+click", "Ctrl+клик")
    val alt = L("Alt+click", "Alt+клик")
    val selectAfterDrag = L("Touching a boundary (moving or pressing it) selects its phoneme: Space plays it, Delete removes it",
        "Касание границы (перетаскивание или нажатие) выделяет её фонему: пробел воспроизводит её, Delete удаляет")
    val audioDeselects = L("A click on the waveform or spectrogram clears the selection (Space plays from there)",
        "Клик по волне или спектрограмме снимает выделение (пробел воспроизводит с этого места)")
    val spaceRestarts = L("Space while playing starts again (instead of stopping)", "Пробел во время воспроизведения начинает заново (а не останавливает)")
    val owner = L("A boundary belongs to the phoneme…", "Граница относится к фонеме…")
    val ownerHint = L("Delete on a selected boundary removes that phoneme, Space plays it, and a new boundary creates it (that part gets the new name).",
        "Delete на выбранной границе убирает эту фонему, пробел её проигрывает, а новая граница создаёт её (эта часть получает новое название).")
    val ownerEnd = L("that ends at it", "которая на ней заканчивается")
    val ownerStart = L("that starts at it", "которая с неё начинается")

    fun action(id: String): String = when (id) {
        mlabeler.app.state.MouseActions.NONE -> L("Nothing", "Ничего")()
        mlabeler.app.state.MouseActions.SELECT -> L("Select", "Выбрать")()
        mlabeler.app.state.MouseActions.DESELECT -> L("Clear the selection", "Снять выделение")()
        mlabeler.app.state.MouseActions.PLAY -> L("Play the phoneme", "Проиграть фонему")()
        mlabeler.app.state.MouseActions.PLAY_FROM -> L("Play from here", "Воспроизвести отсюда")()
        mlabeler.app.state.MouseActions.RENAME -> L("Rename", "Переименовать")()
        mlabeler.app.state.MouseActions.SPLIT -> L("Add a boundary", "Поставить границу")()
        mlabeler.app.state.MouseActions.SPLIT_NAME -> L("Add a boundary and name it", "Поставить границу и назвать")()
        mlabeler.app.state.MouseActions.DELETE -> L("Remove the phoneme", "Убрать фонему")()
        else -> id
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MousePage(app: AppState) {
    val c = T.c
    val s = app.settings
    val m = s.mouse
    fun set(f: (mlabeler.app.state.MouseSettings) -> mlabeler.app.state.MouseSettings) = app.update { it.copy(mouse = f(it.mouse)) }
    Fold(MouseTitles.tool()) {
        SwitchRow(MouseTitles.playIt(), s.edit.cutPlay) { v -> app.update { it.copy(edit = it.edit.copy(cutPlay = v)) } }
        SwitchRow(S.playOnDrag(), s.edit.playOnDrag) { v -> app.update { it.copy(edit = it.edit.copy(playOnDrag = v)) } }
        SwitchRow(MouseTitles.selectAfterDrag(), s.edit.selectAfterDrag) { v -> app.update { it.copy(edit = it.edit.copy(selectAfterDrag = v)) } }
        SwitchRow(MouseTitles.audioDeselects(), s.edit.audioClickDeselects) { v -> app.update { it.copy(edit = it.edit.copy(audioClickDeselects = v)) } }
    }
    Fold(MouseTitles.wheel()) {
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(MouseTitles.wheelScroll(), m.wheel != "phonemes") { set { it.copy(wheel = "scroll") } }
            Chip(MouseTitles.wheelPhonemes(), m.wheel == "phonemes") { set { it.copy(wheel = "phonemes") } }
        }
    }
    Fold(MouseTitles.onLabels()) {
        Text(MouseTitles.leftHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        ActionRow(MouseTitles.click(), m.tierClick) { v -> set { it.copy(tierClick = v) } }
        ActionRow(MouseTitles.double(), m.tierDouble) { v -> set { it.copy(tierDouble = v) } }
        ActionRow(MouseTitles.right(), m.tierRight) { v -> set { it.copy(tierRight = v) } }
        ActionRow(MouseTitles.middle(), m.tierMiddle) { v -> set { it.copy(tierMiddle = v) } }
        ActionRow(MouseTitles.ctrl(), m.tierCtrl) { v -> set { it.copy(tierCtrl = v) } }
        ActionRow(MouseTitles.alt(), m.tierAlt) { v -> set { it.copy(tierAlt = v) } }
    }
    Fold(MouseTitles.onAudio()) {
        ActionRow(MouseTitles.click(), m.audioClick) { v -> set { it.copy(audioClick = v) } }
        ActionRow(MouseTitles.double(), m.audioDouble) { v -> set { it.copy(audioDouble = v) } }
        ActionRow(MouseTitles.right(), m.audioRight) { v -> set { it.copy(audioRight = v) } }
        ActionRow(MouseTitles.middle(), m.audioMiddle) { v -> set { it.copy(audioMiddle = v) } }
        ActionRow(MouseTitles.ctrl(), m.audioCtrl) { v -> set { it.copy(audioCtrl = v) } }
        ActionRow(MouseTitles.alt(), m.audioAlt) { v -> set { it.copy(audioAlt = v) } }
    }
}

@Composable
private fun ActionRow(title: String, value: String, onChange: (String) -> Unit) {
    val c = T.c
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box {
            Btn(MouseTitles.action(value) + "  ▾") { open = true }
            MenuPopup(open, onDismiss = { open = false }, focusable = true) {
                for (a in mlabeler.app.state.MouseActions.all) {
                    androidx.compose.material3.DropdownMenuItem(
                        { Text((if (a == value) "✓  " else "     ") + MouseTitles.action(a), fontSize = 13.sp) },
                        onClick = { open = false; onChange(a) },
                    )
                }
            }
        }
    }
}

private val leaveTitle = mlabeler.app.i18n.L("Save the changes?", "Сохранить изменения?")
private val leaveText = mlabeler.app.i18n.L("The labels of {0} have changes that are not saved yet.", "В разметке {0} есть несохранённые изменения.")
private val dontSave = mlabeler.app.i18n.L("Don't save", "Не сохранять")

/** Asked before leaving a folder with unsaved labels (when "save when switching files" is off). */
@Composable
fun LeaveDialog(app: AppState) {
    val action = app.pendingLeave ?: return
    val c = T.c
    val ed = app.editor
    fun done(save: Boolean?) {
        app.pendingLeave = null
        if (save == null) return
        // not saving: leaving the folder with "save when switching" off keeps the files as they are
        if (save) ed?.save(quiet = true)
        action()
    }
    Overlay({ done(null) }, 440) {
        Column(Modifier.padding(18.dp)) {
            Text(leaveTitle(), color = c.text, fontSize = 17.sp)
            Text(leaveText.format(ed?.item?.name ?: ""), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { done(null) }
                Btn(dontSave()) { done(false) }
                Btn(S.save(), primary = true) { done(true) }
            }
        }
    }
}

private object AboutTitles {
    val author = mlabeler.app.i18n.L("Author", "Автор")
    val thanks = mlabeler.app.i18n.L("Special thanks", "Отдельная благодарность")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutPage(app: AppState) {
    val c = T.c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    @Composable
    fun link(text: String, url: String) {
        Text(text, color = c.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 4.dp, horizontal = 2.dp))
    }
    @Composable
    fun person(name: String, links: List<Pair<String, String>>) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = c.text, fontSize = 14.sp, modifier = Modifier.width(110.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { for ((t, u) in links) link(t, u) }
        }
    }
    Fold(S.about()) {
        Text("mLabeler ${mlabeler.app.AppInfo.VERSION}", color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Text(S.aboutText(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        mlabeler.app.Platform.portableDir?.let { PortableNote(it) }
        if (mlabeler.app.Platform.legacyWindows) LegacyNote()
    }
    UpdateSection(app) { t, v, f -> SwitchRow(t, v, f) }
    Fold(AboutTitles.author()) {
        person("m", listOf(
            "GitHub" to "https://github.com/Megageorgio",
            "Telegram" to "https://t.me/m_repository",
            "Discord" to "https://discord.gg/y5YsY9UfBG",
        ))
    }
    Fold(AboutTitles.thanks()) {
        person("HHS_kt", listOf("YouTube" to "https://www.youtube.com/@HHS_kt", "Telegram" to "https://t.me/hhs_kt_666"))
        person("Gitreti", emptyList())
        person("XHR0ME", listOf("X" to "https://x.com/ExChroma", "Telegram" to "https://t.me/xhr0m1", "YouTube" to "https://www.youtube.com/@chr0ma313"))
    }
}

private object DangerTitles {
    val title = mlabeler.app.i18n.L("Delete all program data", "Удалить все данные программы")
    val about = mlabeler.app.i18n.L(
        "Settings, themes, environments, shortcuts, phoneme dictionaries, plugins, check scripts and caches of mLabeler are deleted, and it starts as on the first launch. Recordings and labels in your folders stay. This can't be undone.",
        "Удаляются настройки, темы, рабочие среды, сочетания клавиш, словари фонем, плагины, скрипты проверок и кэш mLabeler, и программа начинает как при первом запуске. Записи и разметка в ваших папках остаются. Отменить это нельзя.")
    val sure = mlabeler.app.i18n.L("Yes, delete everything", "Да, удалить всё")
}

/** The button that deletes everything the program keeps; a second, red button confirms. */
@Composable
private fun DangerZone(app: AppState) {
    val c = T.c
    var asked by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp).border(c.borderWidth, c.danger.copy(alpha = 0.6f), mlabeler.app.theme.RoundedCornerShape(c.radius)).padding(12.dp)) {
        Text(DangerTitles.title(), color = c.danger, fontSize = 14.sp)
        Text(DangerTitles.about(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!asked) Btn(DangerTitles.title()) { asked = true }
            else {
                Box(Modifier.clip(mlabeler.app.theme.RoundedCornerShape(c.radius)).background(c.danger)
                    .clickable { app.deleteAllProgramData() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(DangerTitles.sure(), color = c.bg, fontSize = 13.sp)
                }
                Btn(S.cancel()) { asked = false }
            }
        }
    }
}

// ---------------- explanations of settings and search over them ----------------

/** A setting: its page, its title as shown and a longer explanation (a "?" next to it, and what search finds). */
internal class SettingHelp(val section: String, val title: L, val hint: L)

private fun h(section: String, title: L, en: String, ru: String) = SettingHelp(section, title, L(en, ru))

internal object SettingsHelp {
    val all: List<SettingHelp> by lazy {
        listOf(
            h("General", S.language, "Language of the whole program. Changes at once.", "Язык всей программы. Меняется сразу."),
            h("General", S.saveOnSwitch, "Going to another file saves the labels of this one first. Off: changes stay in memory until you save; leaving the folder asks about them.",
                "При переходе к другому файлу разметка текущего сначала сохраняется. Выключено: изменения хранятся в памяти до сохранения, при выходе из папки будет предложено их сохранить."),
            h("General", S.otherAudio, "Lists compressed formats too. On computers they are read with ffmpeg, which must be installed. Labels and cleaning work only on WAV.",
                "Показывать и сжатые форматы. На компьютере они читаются через ffmpeg, его нужно установить. Чистка записи работает только с WAV."),
            h("General", S.autosave, "Saves the labels automatically every N seconds when something changed. 0 turns it off.",
                "Сохранять разметку автоматически каждые N секунд, если что-то изменилось. 0 — выключено."),
            h("General", S.fullscreen, "Phones and tablets: the status and navigation bars are hidden.", "Телефоны и планшеты: скрыть строку состояния и навигации."),
            h("General", S.avoidCutout, "Phones: nothing is drawn under the camera cutout.", "Телефоны: ничего не рисуется под вырезом камеры."),

            h("Interface", MenuTitles.menuBar, "File, Edit, View… menus at the top of the window.", "Меню «Файл», «Правка», «Вид»… вверху окна."),
            h("Interface", titleBarMenuT, "The program draws the window title itself and puts the menus in it, saving a row. Window buttons, dragging, double click and resizing at the edges keep working; on macOS the system's round buttons stay. Some window-manager features (snapping to screen halves, the frame shadow) may not work. Takes effect after a restart.",
                "Программа сама рисует заголовок окна и ставит в него меню — на одну строку меньше. Кнопки окна, перетаскивание, двойной щелчок и изменение размера за края работают; на macOS остаются системные круглые кнопки. Некоторые возможности оконной системы (прилипание к половине экрана, тень рамки) могут не работать. Действует после перезапуска."),
            h("Interface", minimapT, "A thin strip with the whole recording: its loudness, the labelled parts and the part on screen as a window. It replaces the scroll bar: drag the window to move the view, drag its edge to zoom, press elsewhere to go there; the wheel scrolls, Ctrl+wheel zooms.",
                "Тонкая полоса со всей записью: громкость, размеченные места и видимая часть в виде окошка. Заменяет полосу прокрутки: перетащите окошко — вид сдвинется, потяните за его край — изменится масштаб, нажмите в другом месте — вид перейдёт туда; колесо листает, Ctrl+колесо меняет масштаб."),
            h("Interface", phonemeColorsT, "Vowels, consonants and pauses get a light tint of the theme's colours on the phoneme lane.",
                "Гласные, согласные и паузы на дорожке фонем слегка подкрашиваются цветами темы."),
            h("Interface", animationsT, "Normal: short fades and slides. Reduced: quicker fades only. Off: everything at once.",
                "Обычные — короткие появления и сдвиги. Уменьшенные — только быстрые появления. Выключены — всё сразу."),
            h("Interface", tipsAtStartT, "At every start a tip about a less obvious feature or setting; Help → Tip of the day shows them any time.",
                "При каждом запуске — совет о неочевидной функции или настройке; Справка → Совет дня показывает их в любое время."),
            h("Interface", MenuTitles.statusBar, "The line at the bottom: phoneme number, done files, scale and more; below it you choose what it shows.",
                "Строка внизу окна: номер фонемы, готовые файлы, масштаб и прочее; ниже настраивается, что в ней показывать."),
            h("Interface", MenuTitles.filesPanel, "The panel with the list of files and the list of all labels.", "Панель со списком файлов и списком всех меток."),
            h("Interface", MenuTitles.detailsPanel, "The panel with details of the selection, checks and comparison.", "Панель со свойствами выбранного, проверками и сравнением."),
            h("Interface", MenuTitles.buttonLabels, "Names of the toolbar buttons under their icons.", "Названия кнопок панели инструментов под значками."),
            h("Interface", MenuTitles.bigButtons, "Larger toolbar buttons, easier to tap with a finger or a pen.", "Кнопки панели крупнее — удобнее нажимать пальцем или пером."),

            h("Themes", dimT, "Off: the program behind the settings stays as bright as usual, so colours of a theme can be judged while editing it.",
                "Выключено: программа за окном настроек не затемняется, и цвета темы видны без искажений во время правки."),
            h("Themes", crispT, "Text, corners, sliders and switches are drawn with hard pixel edges, like in old programs. Corners keep their rounding as pixel steps.",
                "Текст, углы, ползунки и переключатели рисуются чёткими пикселями, как в старых программах. Скругления передаются пиксельными ступенями."),
            h("Themes", checkboxesT, "Square boxes with a tick instead of sliding switches, and classic sliders.", "Квадратные флажки вместо переключателей и классические ползунки."),
            h("Themes", radiusT, "How round the corners of buttons, fields and windows are; 0 = square.", "Насколько скруглены углы кнопок, полей и окон; 0 — прямые."),
            h("Themes", borderT, "Thickness of frames around buttons and fields.", "Толщина рамок вокруг кнопок и полей."),
            h("Themes", squareT, "A pressed button is filled with one colour instead of a soft highlight.", "Нажатая кнопка заливается одним цветом, а не мягкой подсветкой."),
            h("Themes", monoT, "Every letter takes the same width, as in a terminal.", "Все буквы одной ширины, как в терминале."),
            h("Themes", darkT, "Tells the system parts (scroll bars, text cursor) that the theme is dark.", "Сообщает системным элементам (полосы прокрутки, текстовый курсор), что тема тёмная."),

            h("View", S.interfaceScale, "Size of all elements: text, buttons, panels. Applied when the slider is released.", "Размер всех элементов: текста, кнопок, панелей. Применяется после отпускания ползунка."),
            h("View", scaleButtonT, "A button with the interface size in percent on the toolbar, for quick changes.", "Кнопка с размером интерфейса в процентах на панели — для быстрой смены."),
            h("View", keepZoomT, "Opening another file keeps the scale you set. Off: each file opens with the scale it had last time (or whole).",
                "Другой файл открывается в том же масштабе. Выключено: каждый файл открывается в своём последнем масштабе (или целиком)."),
            h("View", S.overlay, "The waveform is drawn over the spectrogram and the labels over both, in one picture instead of separate lanes.",
                "Волна рисуется поверх спектрограммы, а разметка поверх обоих — одна картинка вместо отдельных полос."),
            h("View", namesOnAudioT, "Names of the phonemes are also written over the waveform and the spectrogram, where you choose inside each phoneme.",
                "Имена фонем выводятся также поверх волны и спектрограммы — в выбранном месте каждой фонемы."),
            h("View", S.labelFontSize, "Size of the label text on the lanes; the lanes grow to fit it.", "Размер текста меток на полосах; полосы подстраиваются по высоте."),
            h("View", S.overlayWaveFillAlpha, "How solid the waveform is over the spectrogram in the overlaid view.", "Насколько плотная волна поверх спектрограммы в наложенном виде."),
            h("View", S.overlayDim, "Darkens the spectrogram in the overlaid view so labels and the waveform stay readable.", "Затемняет спектрограмму в наложенном виде, чтобы разметка и волна оставались различимыми."),
            h("View", S.tiersOnTop, "Label lanes above the waveform and spectrogram instead of below.", "Полосы разметки над волной и спектрограммой, а не под ними."),
            h("View", S.waveform, "Shows the waveform lane.", "Показывать полосу волны."),
            h("View", S.spectrogram, "Shows the spectrogram lane.", "Показывать полосу спектрограммы."),
            h("View", S.pitch, "Pitch of the voice: a curve over the spectrogram or a piano roll lane with notes.", "Высота голоса: кривая поверх спектрограммы или полоса-пианоролл с нотами."),
            h("View", S.pitchOver, "On: the pitch curve over the spectrogram. Off: its own lane, a piano roll where notes can be edited.",
                "Включено: кривая высоты поверх спектрограммы. Выключено: отдельная полоса-пианоролл, где можно править ноты."),
            h("View", S.power, "A lane with the loudness of the recording.", "Полоса с громкостью записи."),
            h("View", Commands.formants.title, "The first three resonances of the voice (F1 red, F2 orange, F3 green) as dots over the spectrogram. They help to tell similar vowels apart (a / ax, i / e).",
                "Первые три резонанса голоса (F1 красный, F2 оранжевый, F3 зелёный) точками поверх спектрограммы. Помогают различать похожие гласные (a / ax, i / e)."),

            h("Spectrogram", S.brightness, "Lifts or lowers all colours of the spectrogram.", "Делает все цвета спектрограммы светлее или темнее."),
            h("Spectrogram", S.contrast, "Spreads the colours: higher makes quiet and loud parts differ more.", "Растягивает цвета: больше — сильнее различаются тихое и громкое."),
            h("Spectrogram", S.windowMs, "Length of the audio analysed for each column. Longer: sharper harmonics, blurrier in time. Shorter: sharper in time.",
                "Длина фрагмента, по которому рассчитывается каждый столбец. Длиннее — чётче гармоники, но ниже точность по времени. Короче — выше точность по времени."),
            h("Spectrogram", S.hopMs, "Time between columns. Smaller is sharper when zoomed in but takes longer and more memory. 0 picks it by the length of the file.",
                "Время между столбцами. Меньше — чётче при приближении, но дольше расчёт и больше памяти. 0 — по длине файла."),
            h("Spectrogram", S.bands, "How many rows of frequency the picture has. More is finer, slower.", "Сколько строк по частоте в картинке. Больше — детальнее, но медленнее."),
            h("Spectrogram", S.dbRange, "Anything quieter is drawn as the darkest colour. Lower shows more of the quiet sound and noise.",
                "Всё тише этого рисуется самым тёмным цветом. Ниже — видно больше тихих звуков и шума."),
            h("Spectrogram", S.dbTop, "Anything louder is drawn as the brightest colour.", "Всё громче этого рисуется самым ярким цветом."),
            h("Spectrogram", S.maxFrequency, "The top of the spectrogram. Singing is mostly below 8 kHz; consonants like s and sh go higher.",
                "Верх спектрограммы. Пение в основном ниже 8 кГц, согласные вроде s и sh — выше."),

            h("Editing", S.nudgeStep, "How far the arrow keys move a selected boundary (Shift: more).", "На сколько стрелки сдвигают выбранную границу (с Shift — больше)."),
            h("Editing", S.minInterval, "A boundary can't come closer than this to its neighbours, so no part becomes empty.", "Граница не подойдёт к соседней ближе этого, чтобы ни одна часть не стала пустой."),
            h("Editing", S.ripple, "Moving a boundary moves every boundary after it by the same amount. Shift switches it while dragging.",
                "При сдвиге границы все границы после неё сдвигаются на столько же. Shift переключает во время перетаскивания."),
            h("Editing", S.linked, "Boundaries at the same time on other lanes (phonemes and words) move together. Alt switches it while dragging.",
                "Границы в то же время на других полосах (фонемы и слова) двигаются вместе. Alt переключает во время перетаскивания."),
            h("Editing", S.loop, "Playing a part repeats it until stopped.", "Воспроизведение фрагмента повторяется до остановки."),
            h("Editing", snapZeroT, "A boundary placed or dragged by hand moves to the nearest point within 3 ms where the waveform crosses zero, so cut segments don't click.",
                "Граница, поставленная или сдвинутая вручную, смещается в ближайшую точку (до 3 мс), где волна проходит через ноль, — чтобы нарезанные сегменты не щёлкали."),
            h("Editing", PlayTitles.volume, "Playback volume of the program (the files don't change).", "Громкость воспроизведения в программе (файлы не меняются)."),
            h("Editing", PlayTitles.followAt, "Where the playhead stays when the view scrolls smoothly during playback.", "Где находится курсор воспроизведения, когда вид плавно прокручивается за ним."),
            h("Editing", S.speedSetting, "Slower playback with the same pitch, to hear fast phonemes.", "Замедленное воспроизведение без изменения высоты — чтобы расслышать быстрые фонемы."),
            h("Editing", S.playOnDrag, "A short part around the boundary plays while it is dragged.", "Во время перетаскивания границы воспроизводится короткий фрагмент вокруг неё."),
            h("Editing", S.otoLocked, "oto: dragging the preutterance moves all markers of the entry together. Shift switches it.",
                "oto: перетаскивание preutterance двигает все маркеры записи вместе. Shift — наоборот."),
            h("Editing", MouseTitles.spaceRestarts, "Space during playback starts the part again instead of stopping.", "Пробел во время воспроизведения запускает фрагмент заново, а не останавливает его."),
            h("Editing", MouseTitles.owner, "Which phoneme Delete removes and Space plays when a boundary is selected, and which part gets the name of a new boundary.",
                "Какую фонему удаляет Delete и воспроизводит пробел при выбранной границе и какая часть получает имя новой границы."),

            h("Mouse", MouseTitles.wheel, "What the wheel does over the picture: scroll in time, or step through phonemes.", "Что делает колесо над картинкой: прокрутка по времени или переход по фонемам."),
            h("Mouse", MouseTitles.playIt, "After a boundary is added with the mouse, the part before it plays.", "После добавления границы мышью проигрывается часть перед ней."),
            h("Mouse", MouseTitles.selectAfterDrag, "Pressing or moving a boundary selects its phoneme, so Space plays it right away.", "Нажатие или сдвиг границы выбирает её фонему — пробел сразу её воспроизводит."),
            h("Mouse", MouseTitles.audioDeselects, "A click on the audio clears the selected phoneme; Space then plays from the click.", "Щелчок по звуку снимает выбор фонемы; пробел затем воспроизводит с места щелчка."),
            h("Mouse", MouseTitles.onLabels, "What each click does on the label lanes: select, play, rename, split…", "Что делает каждый щелчок на полосах разметки: выбрать, проиграть, переименовать, разрезать…"),
            h("Mouse", MouseTitles.onAudio, "What each click does on the waveform and spectrogram.", "Что делает каждый щелчок на волне и спектрограмме."),

            h("Checks", S.shortThreshold, "Phonemes shorter than this are marked (pauses are not).", "Фонемы короче этого отмечаются (паузы — нет)."),
            h("Checks", S.phonemeSet, "Phonemes outside this set are marked as unknown. Empty: any name is allowed. The buttons below fill it from a dictionary.",
                "Фонемы не из этого набора отмечаются как незнакомые. Пусто — подходит любое имя. Кнопки ниже заполняют набор из словаря."),
            h("Checks", CheckTitles.maxLen, "A phoneme (not a pause) longer than this is marked. 0 = not checked.", "Фонема (не пауза) длиннее этого отмечается. 0 — не проверять."),
            h("Checks", CheckTitles.maxPause, "A pause or an unnamed part longer than this is marked. 0 = not checked.", "Пауза или неподписанный интервал длиннее этого отмечается. 0 — не проверять."),
            h("Checks", CheckTitles.maxPhrase, "Singing without a long enough pause for longer than this is marked: DiffSinger is trained on segments up to about 15 s.",
                "Пение без достаточной паузы дольше этого отмечается: DiffSinger обучается на сегментах примерно до 15 с."),
            h("Checks", CheckTitles.phrasePause, "How long a pause must be to count as a place where a segment can end.", "Какой длины пауза считается местом, где может закончиться сегмент."),
            h("Checks", CheckTitles.diffsinger, "Issues that make DiffSinger fail: phonemes shorter than one frame, spaces inside a name, two same pauses in a row, zero length, notes not as long as the phonemes of their sentence.",
                "Ошибки, приводящие к сбою DiffSinger: фонемы короче кадра, пробелы в имени, две одинаковые паузы подряд, нулевая длина, ноты не той длины, что фонемы предложения."),
            h("Checks", CheckTitles.runScripts, "Runs your own checks written as small JavaScript files after every change.", "Запускать свои проверки (небольшие файлы на JavaScript) после каждого изменения."),

            h("Toolkit", S.toolkitAutoStart, "The toolkit starts automatically when a tool needs it and stops once no program uses it any more.", "Тулкит запускается автоматически, когда он нужен инструменту, и закрывается, когда им больше не пользуется ни одна программа."),
            h("Toolkit", S.toolkitShare, "Phones and tablets in the same network can use the toolkit of this computer. A token protects it.",
                "Телефоны и планшеты в той же сети могут пользоваться тулкитом этого компьютера. Доступ защищён токеном."),
        )
    }

    private var cacheLang = ""
    private var byTitle: Map<String, SettingHelp> = emptyMap()

    /** The explanation of the setting shown with [title] (titles with " — …" after them too). */
    fun forTitle(title: String): SettingHelp? {
        if (cacheLang != mlabeler.app.i18n.Lang.current) {
            byTitle = all.associateBy { it.title() }
            cacheLang = mlabeler.app.i18n.Lang.current
        }
        return byTitle[title] ?: byTitle[title.substringBefore(" — ")]
    }

    /** Settings whose title or explanation has every word of [query] (in either language). */
    fun search(query: String): List<SettingHelp> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { e ->
            val text = (e.title.en + " " + e.title.ru + " " + e.hint.en + " " + e.hint.ru).lowercase()
            words.all { it in text }
        }.sortedBy { e -> if (words.all { it in (e.title.en + " " + e.title.ru).lowercase() }) 0 else 1 }
    }
}

/** The title of the setting the search jumped to: it is highlighted and scrolled into view. */
internal val LocalSettingFocus = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** A small "?" with the explanation of a setting as a tooltip; nothing when there is none. */
@Composable
internal fun SettingHelpMark(title: String) {
    val help = SettingsHelp.forTitle(title) ?: return
    Tip(help.hint()) {
        Text("?", color = T.c.muted, fontSize = 11.sp,
            modifier = Modifier.padding(start = 6.dp).border(T.c.borderWidth, T.c.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp))
    }
}

/** Highlight and scroll for the setting a search result points at. */
@Composable
internal fun Modifier.settingFocus(title: String): Modifier {
    val focus = LocalSettingFocus.current
    val hit = focus != null && (focus == title || title.startsWith("$focus — "))
    if (!hit) return this
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val open = LocalFoldOpen.current
    androidx.compose.runtime.LaunchedEffect(focus) { open?.invoke(); kotlinx.coroutines.delay(150); requester.bringIntoView() }
    return this.bringIntoViewRequester(requester).background(T.c.accent.copy(alpha = 0.18f))
}

/** Which blocks of the settings are open, by title; kept while the settings window is open. */
internal val LocalFolds = androidx.compose.runtime.compositionLocalOf<MutableMap<String, Boolean>?> { null }

/** Opens the block around a setting the search jumped to. */
private val LocalFoldOpen = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** A block of settings under a title that opens and closes with a click; closed at first. */
@Composable
internal fun Fold(title: String, content: @Composable () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius)
    val folds = LocalFolds.current
    var own by remember { mutableStateOf(false) }
    val open = folds?.get(title) ?: own
    fun set(v: Boolean) { if (folds != null) folds[title] = v else own = v }
    val focus = LocalSettingFocus.current
    // after a jump from the search the closed blocks are composed once out of sight: the one with the setting opens
    var looked by remember(focus) { mutableStateOf(focus == null) }
    androidx.compose.runtime.LaunchedEffect(focus) { if (focus != null) { androidx.compose.runtime.withFrameNanos { }; looked = true } }
    val opener: () -> Unit = { set(true) }
    androidx.compose.runtime.CompositionLocalProvider(LocalFoldOpen provides opener) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(c.borderWidth, c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(c.panelAlt).settingFocus(title).clickable { set(!open) }.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(if (open) Icons.down else Icons.right, null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                SettingHelpMark(title)
            }
        }
        if (open) {
            Divider()
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) { content() }
        } else if (!looked) {
            androidx.compose.ui.layout.Layout({ Column { content() } }) { _, _ -> layout(0, 0) {} }
        }
    }
    }
}

/** The notice on old Windows: no toolkit of its own. */
@Composable
internal fun LegacyNote() {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(c.radius)).background(c.warn.copy(alpha = 0.12f))
        .border(c.borderWidth, c.warn.copy(alpha = 0.5f), RoundedCornerShape(c.radius)).padding(10.dp)) {
        Text(legacyTitleT(), color = c.text, fontSize = 13.sp)
        Text(legacyNoteT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private val legacyTitleT = mlabeler.app.i18n.L("Windows 7 or 8.1: without mVocalToolkit", "Windows 7 или 8.1: без mVocalToolkit")
private val legacyNoteT = mlabeler.app.i18n.L(
    "On Windows 7 and 8.1 the toolkit can't be installed (it doesn't run there); autolabel works through the toolkit of another computer in the network. Everything else works as usual.",
    "На Windows 7 и 8.1 тулкит не устанавливается (он там не работает); авторазметка работает через тулкит другого компьютера в сети. Всё остальное работает как обычно.",
)

/** The notice of the fully portable build: where everything is kept and that it needs room. */
@Composable
internal fun PortableNote(dir: String) {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(c.radius)).background(c.warn.copy(alpha = 0.12f))
        .border(c.borderWidth, c.warn.copy(alpha = 0.5f), RoundedCornerShape(c.radius)).padding(10.dp)) {
        Text(S.portableTitle(), color = c.text, fontSize = 13.sp)
        Text(S.portableNote.format(dir), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
