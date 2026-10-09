package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.resizeHorizontalIcon
import mlabeler.app.theme.T

/** Touch targets are larger on phones. */
/** Smallest button: on touch screens at least 44 dp at the chosen interface size (a finger doesn't shrink with it). */
val targetSize: Dp get() = if (Platform.isMobile) (44f / UiScale.current.coerceAtMost(1f)).coerceAtMost(60f).dp else 32.dp

/** The interface scale currently applied (set where it's applied). */
object UiScale {
    var current by androidx.compose.runtime.mutableFloatStateOf(1f)
}

@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    if (text.isEmpty()) return content()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            val c = T.c
            PlainTooltip(
                containerColor = c.panelAlt,
                contentColor = c.text,
                shape = RoundedCornerShape(if (c.square) 0.dp else c.radius),
                modifier = Modifier.border(c.borderWidth, c.border, RoundedCornerShape(if (c.square) 0.dp else c.radius)),
            ) { Text(text, fontSize = 12.sp, color = c.text) }
        },
        state = rememberTooltipState(),
    ) { content() }
}

/** Icon button; [hint] becomes the tooltip, [keys] are shown after it. */
@Composable
fun IconBtn(
    icon: ImageVector,
    hint: String,
    keys: String = "",
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color? = null,
    size: Dp = targetSize,
    onClick: () -> Unit,
) {
    val c = T.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(c.radius)
    Tip(if (keys.isEmpty()) hint else "$hint  ·  $keys") {
        Box(
            Modifier.size(size).clip(shape)
                .background(
                    when {
                        active -> c.accent.copy(alpha = if (c.square) 1f else 0.22f)
                        hovered && enabled -> c.text.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                )
                .then(if (c.square && active) Modifier.border(c.borderWidth, c.border, shape) else Modifier)
                .hoverable(source)
                .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = hint, modifier = Modifier.size(if (Platform.isMobile) 22.dp else 18.dp),
                tint = when {
                    !enabled -> c.muted.copy(alpha = 0.45f)
                    active && c.square -> c.onAccent
                    active -> c.accent
                    else -> tint ?: c.text
                },
            )
        }
    }
}

/** A text button in the theme's style. */
@Composable
fun Btn(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = T.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(c.radius)
    val bg = when {
        primary -> if (hovered) c.accent.copy(alpha = 0.88f) else c.accent
        hovered -> c.panelAlt
        else -> c.panel
    }
    Row(
        modifier.height(if (Platform.isMobile) 44.dp else 32.dp).clip(shape).background(animatedColor(if (enabled) bg else c.panelAlt).value)
            .border(c.borderWidth, if (primary) c.accent else c.border, shape)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val fg = if (!enabled) c.muted else if (primary) c.onAccent else c.text
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(text, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(if (c.square) 0.dp else 50.dp)
    Box(
        Modifier.height(if (Platform.isMobile) 40.dp else 26.dp).clip(shape)
            .background(animatedColor(if (selected) c.accent.copy(alpha = if (c.square) 1f else 0.2f) else Color.Transparent).value)
            .border(c.borderWidth, animatedColor(if (selected) c.accent else c.border).value, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, color = if (selected && c.square) c.onAccent else if (selected) c.accent else c.text, maxLines = 1)
    }
}

/** Plain single-line field. */
/** True while a text field has the keyboard: single-key shortcuts must not fire then. */
object TextFocus {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
    val active: Boolean get() = count > 0
}

/** Marks a text field so that typing in it doesn't run shortcuts. */
@Composable
fun Modifier.trackTextFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (focused) TextFocus.count-- } }
    return this.onFocusChanged { st ->
        if (st.isFocused != focused) {
            focused = st.isFocused
            if (focused) TextFocus.count++ else TextFocus.count--
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onDone: (() -> Unit)? = null,
    textStyle: TextStyle = TextStyle(fontSize = 14.sp),
) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = androidx.compose.material3.LocalTextStyle.current.merge(textStyle.copy(color = c.text, fontFamily = textStyle.fontFamily ?: T.font)),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.height(if (Platform.isMobile) 44.dp else 32.dp).trackTextFocus(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().clip(shape).background(c.bg).border(c.borderWidth, c.border, shape).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = c.muted, fontSize = 14.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = T.c
    Row(modifier.fillMaxWidth().settingFocus(text).padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.square) text.uppercase() else text, color = c.muted, fontSize = 11.sp,
                letterSpacing = if (c.square) 1.sp else 0.3.sp, modifier = Modifier.weight(1f, fill = false),
            )
            SettingHelpMark(text)
        }
        trailing()
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    val c = T.c
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = c.muted, fontSize = 13.sp, modifier = Modifier.widthIn(min = 90.dp).padding(end = 8.dp))
        Text(value, color = c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A vertical drag handle between panels; reports the drag in dp. */
@Composable
fun VSplitter(enabled: Boolean = true, onDrag: (Float) -> Unit) {
    val c = T.c
    if (!enabled) {
        Box(Modifier.width(c.borderWidth).fillMaxHeight().background(c.border))
        return
    }
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier.width(if (Platform.isMobile) 12.dp else 6.dp).fillMaxHeight()
            .hoverable(source)
            .pointerHoverIcon(resizeHorizontalIcon)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(if (hovered || dragging) 2.dp else c.borderWidth).fillMaxHeight().background(if (hovered || dragging) c.accent else c.border))
    }
}

@Composable
fun HSplitter(onDrag: (Float) -> Unit) {
    val c = T.c
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(if (Platform.isMobile) 12.dp else 5.dp)
            .hoverable(source)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(if (hovered) 2.dp else c.borderWidth).background(if (hovered) c.accent else c.border))
    }
}

@Composable
fun Divider(vertical: Boolean = false) {
    val c = T.c
    if (vertical) Box(Modifier.width(c.borderWidth).fillMaxHeight().background(c.border))
    else Box(Modifier.fillMaxWidth().height(c.borderWidth).background(c.border))
}

/** A floating card used for dialogs and sheets. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(if (c.square) 0.dp else c.radius * 1.5f)
    Column(modifier.clip(shape).background(c.panel).border(c.borderWidth, c.border, shape)) { content() }
}

fun formatTime(seconds: Double, precise: Boolean = true): String {
    if (seconds.isNaN()) return "–"
    val neg = seconds < 0
    val ms = kotlin.math.round(kotlin.math.abs(seconds) * 1000).toLong()
    val m = ms / 60000
    val s = (ms / 1000) % 60
    val rest = ms % 1000
    val body = if (precise) "$m:${s.toString().padStart(2, '0')}.${rest.toString().padStart(3, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
    return if (neg) "-$body" else body
}

fun formatMs(seconds: Double): String {
    val ms = seconds * 1000
    val unit = mlabeler.app.i18n.S.msUnit()
    return if (kotlin.math.abs(ms) >= 100) "${kotlin.math.round(ms).toLong()} $unit" else "${kotlin.math.round(ms * 10) / 10.0} $unit"
}

/** Set from the settings: whether the camera cutout and system bars are kept free. */
val LocalKeepBarsFree = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Insets screens keep free: everything when the system bars are shown, only the keyboard in full screen. */
@Composable
fun screenInsets(): androidx.compose.foundation.layout.WindowInsets =
    if (LocalKeepBarsFree.current) androidx.compose.foundation.layout.WindowInsets.safeDrawing
    else androidx.compose.foundation.layout.WindowInsets.ime

/**
 * Slider with a number field next to it: drag for rough values, type for exact ones.
 * [factor] converts the stored value to the number the user sees (e.g. 100 for percent).
 */
@Composable
fun ValueSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    factor: Float = 1f,
    decimals: Int = 0,
    /** Default value: a reset button appears when the value differs from it. */
    default: Float? = null,
    /** false: dragging moves only the slider, the value is applied when it's let go (for things that relayout). */
    live: Boolean = true,
    onChange: (Float) -> Unit,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val c = T.c
    fun shown(v: Float): String {
        val x = v * factor
        if (decimals <= 0) return kotlin.math.round(x).toInt().toString()
        var p = 1f; repeat(decimals) { p *= 10f }
        val r = kotlin.math.round(x * p) / p
        return r.toString().trimEnd('0').trimEnd('.')
    }
    var text by remember { mutableStateOf(shown(value)) }
    var editing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(value) { if (!editing) text = shown(value) }
    fun commit() {
        editing = false
        val v = text.replace(',', '.').trim().toFloatOrNull()
        if (v != null) onChange((v / factor).coerceIn(range))
        text = shown(value)
    }
    Column(Modifier.fillMaxWidth().settingFocus(title).padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                SettingHelpMark(title)
            }
            Field(
                text, { text = it; editing = true },
                Modifier.width(72.dp).onFocusChanged { if (!it.isFocused && editing) commit() },
                onDone = { commit() },
                textStyle = TextStyle(fontSize = 13.sp),
            )
            if (unit.isNotEmpty()) Text(unit, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).widthIn(min = 22.dp))
            if (default != null) {
                val differs = kotlin.math.abs(value - default) > 1e-4f
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    if (differs) Tip(mlabeler.app.i18n.S.resetToDefault() + ": " + shown(default) + (if (unit.isNotEmpty()) " $unit" else "")) {
                        Text("↺", color = c.accent, fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(c.radius)).clickable { editing = false; onChange(default) }.padding(horizontal = 4.dp))
                    }
                }
            }
        }
        val move: (Float) -> Unit = { editing = false; if (live) onChange(it) else { dragValue = it; text = shown(it) } }
        val done: () -> Unit = { dragValue?.let { onChange(it) }; dragValue = null }
        if (c.crisp || c.checkboxes) ClassicSlider((dragValue ?: value).coerceIn(range), range, move, done)
        else androidx.compose.material3.Slider(
            value = (dragValue ?: value).coerceIn(range),
            onValueChange = move,
            onValueChangeFinished = done,
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
            modifier = Modifier.height(32.dp),
        )
    }
}

val dropOpen = mlabeler.app.i18n.L("Drop to open the recording (or the folder)", "Отпустите — запись (или папка) откроется")

/** Shown over a place while files are dragged over it: what dropping them will do. */
@Composable
fun DropHint(text: String) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius * 2)
    Box(
        Modifier.fillMaxSize().padding(6.dp).clip(shape).background(c.accent.copy(alpha = 0.16f)).border(2.dp, c.accent, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.text, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(c.radius)).background(c.panel).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}


/**
 * An on/off control: a switch, or a square check box when the theme asks for it (a white box with a dark frame
 * and a tick, as in classic desktop forms).
 */
@Composable
fun Toggle(value: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = T.c
    if (!c.checkboxes && !c.crisp) {
        // a little smaller than Material's 52×32, to sit with 13 sp text rows
        androidx.compose.foundation.layout.Box(modifier.size(42.dp, 26.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
                androidx.compose.material3.Switch(value, onChange, Modifier.requiredSize(52.dp, 32.dp).scale(0.8f),
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }
        }
        return
    }
    val box = if (c.dark) c.panelAlt else androidx.compose.ui.graphics.Color.White
    val tick = if (c.dark) c.text else androidx.compose.ui.graphics.Color.Black
    androidx.compose.foundation.Canvas(
        modifier.size(16.dp).clickable(remember { MutableInteractionSource() }, null) { onChange(!value) },
    ) {
        val px = size.width / 16f
        // sunken frame: dark top-left, light bottom-right
        drawRect(if (c.dark) c.border else c.muted)
        drawRect(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(px, px), androidx.compose.ui.geometry.Size(size.width - px, size.height - px))
        drawRect(box, androidx.compose.ui.geometry.Offset(px, px), androidx.compose.ui.geometry.Size(size.width - 2 * px, size.height - 2 * px))
        drawRect(c.border.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(px, px), androidx.compose.ui.geometry.Size(size.width - 2 * px, px))
        drawRect(c.border.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(px, px), androidx.compose.ui.geometry.Size(px, size.height - 2 * px))
        if (value) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(4 * px, 8 * px); lineTo(7 * px, 11 * px); lineTo(12.5f * px, 4.5f * px)
            }
            drawPath(p, tick, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * px))
        }
    }
}


/**
 * A slider of classic desktop forms: a thin sunken groove and a raised rectangular thumb, drawn on whole pixels
 * without smoothing.
 */
@Composable
fun ClassicSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val c = T.c
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val thumbW = kotlin.math.round(11 * density)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    fun valueAt(x: Float, width: Float) = (range.start + ((x - thumbW / 2) / (width - thumbW).coerceAtLeast(1f)).coerceIn(0f, 1f) * span)
    val light = androidx.compose.ui.graphics.Color.White
    val dark = if (c.dark) c.border else c.muted
    androidx.compose.foundation.Canvas(
        modifier.fillMaxWidth().height(32.dp).pointerInput(range) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onChange(valueAt(down.position.x, size.width.toFloat()))
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    onChange(valueAt(ch.position.x, size.width.toFloat()))
                    ch.consume()
                }
                onFinished()
            }
        },
    ) {
        val w = size.width
        val px = kotlin.math.max(1f, kotlin.math.round(density))
        val cy = kotlin.math.round(size.height / 2)
        fun rect(color: androidx.compose.ui.graphics.Color, x: Float, y: Float, rw: Float, rh: Float) =
            drawRect(color, androidx.compose.ui.geometry.Offset(kotlin.math.round(x), kotlin.math.round(y)), androidx.compose.ui.geometry.Size(kotlin.math.round(rw), kotlin.math.round(rh)))
        // the groove: dark top and left, light bottom and right
        val gy = cy - 2 * px
        rect(dark, thumbW / 2, gy, w - thumbW, px)
        rect(dark, thumbW / 2, gy, px, 4 * px)
        rect(light, thumbW / 2, gy + 3 * px, w - thumbW, px)
        rect(light, w - thumbW / 2 - px, gy, px, 4 * px)
        // the thumb: raised, filled with the panel colour
        val f = ((value - range.start) / span).coerceIn(0f, 1f)
        val tx = kotlin.math.round(f * (w - thumbW))
        val th = kotlin.math.round(20 * density)
        val ty = cy - kotlin.math.round(th / 2)
        rect(c.panel, tx, ty, thumbW, th)
        rect(light, tx, ty, thumbW - px, px)
        rect(light, tx, ty, px, th - px)
        rect(dark, tx, ty + th - px, thumbW, px)
        rect(dark, tx + thumbW - px, ty, px, th)
        rect(dark.copy(alpha = 0.5f), tx + px, ty + th - 2 * px, thumbW - 2 * px, px)
        rect(dark.copy(alpha = 0.5f), tx + thumbW - 2 * px, ty + px, px, th - 2 * px)
    }
}

/**
 * Vertical scrolling with a quiet hint that there is more: a thin bar at the right edge, shown only when the
 * content does not fit (brighter while it moves).
 */
@Composable
fun Modifier.scrollWithHint(state: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState()): Modifier {
    val c = T.c
    val color = c.muted
    return this.drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val h = size.height
        val total = h + max
        val thumb = (h * h / total).coerceAtLeast(24.dp.toPx()).coerceAtMost(h)
        val top = (h - thumb) * state.value / max
        val w = 3.dp.toPx()
        drawRoundRect(
            color.copy(alpha = if (state.isScrollInProgress) 0.7f else 0.35f),
            topLeft = androidx.compose.ui.geometry.Offset(size.width - w - 2.dp.toPx(), top),
            size = androidx.compose.ui.geometry.Size(w, thumb),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
        )
    }.verticalScroll(state)
}

/**
 * A scroll bar for a lazy list, to be put next to it: the thumb shows the part in view (by items) and can be
 * dragged, a click on the track jumps there.
 */
@Composable
fun LazyScrollbar(state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val c = T.c
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible >= total) return
    androidx.compose.foundation.layout.Box(
        modifier.width(if (mlabeler.app.Platform.isMobile) 14.dp else 10.dp)
            .pointerInput(total) {
                fun jump(y: Float) {
                    val f = (y / size.height).coerceIn(0f, 1f)
                    scope.launch { state.scrollToItem(((total - visible) * f).toInt().coerceIn(0, total - 1)) }
                }
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        if (ch.pressed) { jump(ch.position.y); ch.consume() }
                    }
                }
            }
            .then(Modifier.drawBehind {
                val h = size.height
                val thumb = (h * visible / total).coerceAtLeast(24.dp.toPx()).coerceAtMost(h)
                val first = state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
                val top = (h - thumb) * first.coerceIn(0f, 1f)
                val w = 4.dp.toPx()
                val x = (size.width - w) / 2
                drawRoundRect(c.border.copy(alpha = 0.35f), topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
                drawRoundRect(c.muted.copy(alpha = if (state.isScrollInProgress) 0.9f else 0.6f), topLeft = androidx.compose.ui.geometry.Offset(x, top),
                    size = androidx.compose.ui.geometry.Size(w, thumb), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
            }),
    )
}
