package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.CustomTheme
import mlabeler.app.theme.T
import mlabeler.app.theme.ThemeFiles
import mlabeler.app.theme.Themes
import mlabeler.app.theme.Tokens
import kotlin.math.max
import kotlin.math.min

private val themesT = L("Themes", "Темы")
private val makeCopy = L("Make an editable copy", "Сделать копию для правки")
private val builtInNote = L("Built-in themes can't be changed; make a copy and change anything in it.",
    "Встроенные темы не меняются: сделайте копию и меняйте в ней что угодно.")
private val nameT = L("Name", "Название")
private val darkT = L("Dark theme (for system parts like scroll bars)", "Тёмная тема (для системных элементов)")
private val radiusT = L("Corner rounding", "Скругление углов")
private val borderT = L("Border width", "Толщина рамок")
private val squareT = L("Flat pressed buttons", "Плоские нажатые кнопки")
private val monoT = L("Monospace font", "Моноширинный шрифт")
private val deleteT = L("Delete theme", "Удалить тему")
private val boundsT = L("Boundaries over the audio", "Границы поверх звука")
private val boundLineT = L("Colour", "Цвет")
private val boundWidthT = L("Thickness", "Толщина")
private val dashT = L("Dashed", "Пунктир")
private val dotT = L("Dotted", "Точки")
private val solidT = L("Solid", "Сплошная")
private val folderT = L("Themes folder…", "Папка тем…")
private val reloadT = L("Reload from files", "Перечитать файлы")
private val groupUi = L("Interface", "Интерфейс")
private val groupStatus = L("Signals", "Сигналы")
private val groupTimeline = L("Waveform and labels", "Волна и разметка")
private val tierColorsT = L("Tier colours", "Цвета слоёв")
private val spectrogramT = L("Spectrogram colours (quiet → loud)", "Цвета спектрограммы (тихо → громко)")
private val addStop = L("Add a colour", "Добавить цвет")
private val copyName = L("{0} (copy)", "{0} (копия)")

private val colorNames = mapOf(
    "bg" to L("Background", "Фон"), "panel" to L("Panels", "Панели"), "panelAlt" to L("Panels, second shade", "Панели, второй оттенок"),
    "border" to L("Borders", "Рамки"), "text" to L("Text", "Текст"), "muted" to L("Secondary text", "Второстепенный текст"),
    "accent" to L("Accent", "Акцент"), "onAccent" to L("Text on accent", "Текст на акценте"),
    "danger" to L("Errors", "Ошибки"), "ok" to L("Good", "Хорошо"), "warn" to L("Warnings", "Предупреждения"),
    "laneBg" to L("Audio background", "Фон звука"), "wave" to L("Waveform", "Волна"), "waveCenter" to L("Zero line", "Нулевая линия"),
    "bound" to L("Boundaries", "Границы"), "boundSelected" to L("Selected boundary", "Выбранная граница"),
    "intervalSelected" to L("Selected interval", "Выбранный интервал"), "intervalHover" to L("Interval under the mouse", "Интервал под мышью"),
    "playhead" to L("Playhead", "Позиция воспроизведения"), "cursor" to L("Cursor", "Курсор"),
    "selectionRange" to L("Selected range", "Выделенный фрагмент"), "tierText" to L("Label text", "Текст меток"),
)
private val groups = listOf(
    groupUi to listOf("bg", "panel", "panelAlt", "border", "text", "muted", "accent", "onAccent"),
    groupStatus to listOf("danger", "ok", "warn"),
    groupTimeline to listOf("laneBg", "wave", "waveCenter", "bound", "boundSelected", "intervalSelected", "intervalHover",
        "playhead", "cursor", "selectionRange", "tierText"),
)

/** Settings page: pick a theme; copies of themes can be changed in every detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesPage(app: AppState) {
    val c = T.c
    val s = app.settings
    val data = Platform.dataDir()
    SectionTitle(themesT())
    val names = mapOf("modern-dark" to S.themeModernDark(), "modern-light" to S.themeModernLight(), "retro" to S.themeRetro(), "contrast" to S.themeContrast())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in Themes.builtIn) ThemeSwatch(t, names[t.id] ?: t.id, s.theme == t.id) { app.update { it.copy(theme = t.id) } }
        for (ct in Themes.custom) ThemeSwatch(ct.tokens, ct.name, s.theme == ct.tokens.id) { app.update { it.copy(theme = ct.tokens.id) } }
    }
    FontPicker(app)
    val current = Themes.custom.firstOrNull { it.tokens.id == s.theme }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(makeCopy(), primary = current == null) {
            val base = Themes.byId(s.theme)
            val title = current?.name ?: names[base.id] ?: base.id
            val id = ThemeFiles.copy(data, base, copyName.format(title))
            app.update { it.copy(theme = id) }
        }
        if (!Platform.isMobile) Btn(folderT()) {
            mlabeler.core.io.PlatformFs.mkdirs(ThemeFiles.dir(data))
            Platform.openInFileManager(ThemeFiles.dir(data))
        }
        Btn(reloadT()) { ThemeFiles.load(data) }
    }
    if (current == null) {
        Text(builtInNote(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        return
    }
    ThemeEditor(app, current)
}

@Composable
private fun ThemeSwatch(t: Tokens, name: String, selected: Boolean, onClick: () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius)
    Column(
        Modifier.width(132.dp).clip(shape).border(if (selected) 2.dp else c.borderWidth, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp).background(t.panel).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(t.radius)).background(t.accent))
            Box(Modifier.width(40.dp).height(22.dp).background(t.laneBg)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(t.wave))
            }
            Box(Modifier.size(22.dp).background(Brush.verticalGradient(t.spectrogram.reversed())))
        }
        Text(name, color = c.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.background(c.panelAlt).fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun ThemeEditor(app: AppState, theme: CustomTheme) {
    val c = T.c
    val data = Platform.dataDir()
    // edits are written to the theme's file (and so shown) right away
    var tokens by remember(theme.path) { mutableStateOf(theme.tokens) }
    var name by remember(theme.path) { mutableStateOf(theme.name) }
    var dirty by remember(theme.path) { mutableStateOf(0) }
    fun change(t: Tokens) { tokens = t; dirty++ }
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        Themes.custom.firstOrNull { it.path == theme.path }?.let { ThemeFiles.save(data, it, tokens, name.ifBlank { theme.name }) }
    }
    SectionTitle(nameT())
    Field(name, { name = it; dirty++ }, Modifier.fillMaxWidth())
    SwitchLine(darkT(), tokens.dark) { change(tokens.copy(dark = it)) }
    ValueSlider(radiusT(), tokens.radius.value, 0f..16f, "dp") { change(tokens.copy(radius = it.toInt().dp)) }
    ValueSlider(borderT(), tokens.borderWidth.value, 0f..3f, "dp", decimals = 1) { change(tokens.copy(borderWidth = ((it * 2).toInt() / 2f).dp)) }
    SwitchLine(squareT(), tokens.square) { change(tokens.copy(square = it)) }
    SwitchLine(monoT(), tokens.mono) { change(tokens.copy(mono = it)) }
    for ((title, keys) in groups) {
        SectionTitle(title())
        for (k in keys) {
            val get = ThemeFiles.colorKeys.first { it.first == k }.second
            ColorRow(colorNames[k]?.invoke() ?: k, get(tokens)) { change(ThemeFiles.withColor(tokens, k, it)) }
        }
    }
    SectionTitle(boundsT())
    ColorRow(boundLineT(), if (tokens.boundLine != Color.Unspecified) tokens.boundLine else tokens.bound.copy(alpha = 0.55f)) { change(tokens.copy(boundLine = it)) }
    ValueSlider(boundWidthT(), tokens.boundWidth, 0.5f..4f, "px", decimals = 1) { change(tokens.copy(boundWidth = ((it * 2).toInt().coerceAtLeast(1) / 2f))) }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, t) in listOf("dash" to dashT, "dot" to dotT, "solid" to solidT)) Chip(t(), tokens.boundStyle == k) { change(tokens.copy(boundStyle = k)) }
    }
    SectionTitle(tierColorsT())
    ColorList(tokens.tierColors, minCount = 1) { change(tokens.copy(tierColors = it)) }
    SectionTitle(spectrogramT())
    Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(c.radius)).background(Brush.horizontalGradient(tokens.spectrogram)))
    ColorList(tokens.spectrogram, minCount = 2) { change(tokens.copy(spectrogram = it)) }
    Row(Modifier.padding(top = 16.dp)) {
        Btn(deleteT()) {
            app.update { it.copy(theme = "modern-dark") }
            ThemeFiles.delete(data, theme)
        }
    }
}

@Composable
private fun SwitchLine(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = T.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(value, onChange, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Composable
private fun ColorList(colors: List<Color>, minCount: Int, onChange: (List<Color>) -> Unit) {
    for ((i, col) in colors.withIndex()) {
        ColorRow("${i + 1}", col, onRemove = if (colors.size > minCount) ({ onChange(colors.filterIndexed { j, _ -> j != i }) }) else null) { v ->
            onChange(colors.mapIndexed { j, x -> if (j == i) v else x })
        }
    }
    Row(Modifier.padding(top = 4.dp)) { Btn(addStop(), icon = Icons.plus) { onChange(colors + colors.last()) } }
}

/** A colour: swatch, hex field; the swatch opens hue / saturation / brightness / opacity sliders. */
@Composable
fun ColorRow(title: String, color: Color, onRemove: (() -> Unit)? = null, onChange: (Color) -> Unit) {
    val c = T.c
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(ThemeFiles.hex(color)) }
    // follow changes made elsewhere (sliders) without disturbing what is being typed
    LaunchedEffect(color) { if (ThemeFiles.color(text) != color) text = ThemeFiles.hex(color) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(c.radius)).border(c.borderWidth, c.border, RoundedCornerShape(c.radius))
                    .background(checker()).background(color).clickable { open = !open },
            )
            Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Field(text, { v -> text = v; ThemeFiles.color(v)?.let(onChange) }, Modifier.width(110.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
            if (onRemove != null) IconBtn(Icons.close, S.removeFromList(), size = 26.dp) { onRemove() }
        }
        if (open) {
            val hsv = remember(color) { toHsv(color) }
            Column(Modifier.padding(start = 38.dp)) {
                ValueSlider(L("Hue", "Оттенок")(), hsv[0], 0f..360f, "°") { onChange(Color.hsv(it.coerceIn(0f, 359.9f), hsv[1], hsv[2], color.alpha)) }
                ValueSlider(L("Saturation", "Насыщенность")(), hsv[1], 0f..1f, "%", factor = 100f) { onChange(Color.hsv(hsv[0], it, hsv[2], color.alpha)) }
                ValueSlider(L("Brightness", "Яркость")(), hsv[2], 0f..1f, "%", factor = 100f) { onChange(Color.hsv(hsv[0], hsv[1], it, color.alpha)) }
                ValueSlider(L("Opacity", "Непрозрачность")(), color.alpha, 0f..1f, "%", factor = 100f) { onChange(color.copy(alpha = it)) }
            }
        }
    }
}

private fun checker() = Brush.linearGradient(listOf(Color(0xFF888888), Color(0xFFCCCCCC)))

private fun toHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0) it + 360f else it }
    val s = if (mx == 0f) 0f else d / mx
    return floatArrayOf(h, s, mx)
}

private val crispT = L("No smoothing (hard pixel edges)", "Без сглаживания (чёткие пиксели)")
private val crispNote = L("Square corners and text without smoothing, as in old programs. Text stays smooth on Android.",
    "Углы без скругления, текст без сглаживания, как в старых программах. На Android текст останется сглаженным.")
private val fontT = L("Interface font", "Шрифт интерфейса")
private val fontTheme = L("As in the theme", "Как в теме")
private val fontSearch = L("Find a font", "Найти шрифт")
private val fontSample = L("Sample: a i u e o  ка са на  0:01.250", "Пример: a i u e o  ка са на  0:01.250")

/** Any font installed in the system, with a preview of each. */
@Composable
private fun FontPicker(app: AppState) {
    val c = T.c
    val names = remember { mlabeler.app.systemFontNames() }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val cur = app.settings.font
    SectionTitle(fontT())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // the mouse wheel over the font name steps through the fonts, as in text editors
        Box(Modifier.pointerInput(names) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll || names.isEmpty()) continue
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy == 0f) continue
                    val i = names.indexOf(app.settings.font)
                    val next = (if (i < 0) 0 else i + (if (dy > 0) 1 else -1)).coerceIn(0, names.size - 1)
                    app.update { it.copy(font = names[next]) }
                    ev.changes.forEach { it.consume() }
                }
            }
        }) {
            Btn((cur.ifEmpty { fontTheme() }) + "  ▾") { open = !open }
        }
        if (cur.isNotEmpty()) Btn(fontTheme()) { app.update { it.copy(font = "") } }
    }
    Text(fontSample(), color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    SwitchLine(crispT(), app.settings.crisp) { v -> app.update { it.copy(crisp = v) } }
    Text(crispNote(), color = c.muted, fontSize = 11.sp)
    if (!open || names.isEmpty()) return
    Field(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = fontSearch())
    val shown = remember(query, names) { names.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) } }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(240.dp).border(c.borderWidth, c.border, RoundedCornerShape(c.radius)),
    ) {
        items(shown.size) { i ->
            val n = shown[i]
            val fam = remember(n) { mlabeler.app.systemFontFamily(n) }
            Text(
                n, fontFamily = fam, fontSize = 15.sp, color = if (n == cur) c.accent else c.text, maxLines = 1,
                modifier = Modifier.fillMaxWidth().background(if (n == cur) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { app.update { it.copy(font = n) } }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
