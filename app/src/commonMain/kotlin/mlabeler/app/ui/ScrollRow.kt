package mlabeler.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import mlabeler.app.theme.T

/**
 * A row that may be wider than its place: the mouse wheel scrolls it sideways, and arrows at the edges show that
 * there is more and move to it.
 */
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val c = T.c
    val scope = rememberCoroutineScope()
    Box(
        modifier.pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.type != PointerEventType.Scroll || state.maxValue == 0) continue
                    val ch = ev.changes.firstOrNull() ?: continue
                    val d = ch.scrollDelta
                    val dx = if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) d.x else d.y
                    if (dx == 0f) continue
                    scope.launch { state.scrollBy(dx * 60f) }
                    ch.consume()
                }
            }
        },
    ) {
        Row(Modifier.horizontalScroll(state), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = horizontalArrangement, content = content)
        val step = with(LocalDensity.current) { 240.dp.toPx() }
        @Composable
        fun androidx.compose.foundation.layout.BoxScope.Arrow(left: Boolean) {
            val fade = if (left) listOf(c.panel, c.panel, c.panel.copy(alpha = 0f)) else listOf(c.panel.copy(alpha = 0f), c.panel, c.panel)
            Box(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).width(28.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(fade))
                    .clickable { scope.launch { state.animateScrollBy(if (left) -step else step) } },
                contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (left) Icons.left else Icons.right, null, tint = c.text, modifier = Modifier.padding(horizontal = 2.dp).width(18.dp))
            }
        }
        // matchParentSize: the arrows take the row's height without making it taller
        if (state.value > 0 || state.value < state.maxValue) Box(Modifier.matchParentSize()) {
            if (state.value > 0) Arrow(true)
            if (state.value < state.maxValue) Arrow(false)
        }
    }
}

private suspend fun ScrollState.animateScrollBy(d: Float) = animateScrollTo((value + d).toInt().coerceIn(0, maxValue))

/**
 * The open part of a menu: right under its title ([side] = false) or to the right of a menu row ([side] = true),
 * kept inside the window, scrolling when taller than it.
 */
@Composable
fun MenuPopup(expanded: Boolean, side: Boolean = false, onDismiss: () -> Unit = {}, focusable: Boolean = false, content: @Composable () -> Unit) {
    if (!expanded) return
    val c = T.c
    val density = LocalDensity.current
    val gap = with(density) { 2.dp.roundToPx() }
    val provider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            var x = if (side) anchorBounds.right else anchorBounds.left
            var y = if (side) anchorBounds.top - with(density) { 4.dp.roundToPx() } else anchorBounds.bottom + gap
            if (side && x + popupContentSize.width > windowSize.width) x = anchorBounds.left - popupContentSize.width
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            if (y + popupContentSize.height > windowSize.height) {
                y = if (!side && anchorBounds.top - gap - popupContentSize.height >= 0) anchorBounds.top - gap - popupContentSize.height
                else windowSize.height - popupContentSize.height
            }
            return IntOffset(x, y.coerceAtLeast(0))
        }
    }
    val maxH = with(density) { (LocalWindowInfo.current.containerSize.height.toDp() - 16.dp).coerceAtLeast(120.dp) }
    val shape = RoundedCornerShape(c.radius)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = focusable, dismissOnClickOutside = focusable)) {
        Column(
            Modifier.padding(4.dp).shadow(6.dp, shape).clip(shape).background(c.popup).border(c.borderWidth, c.border, shape)
                .heightIn(max = maxH).width(IntrinsicSize.Max).scrollWithHint().padding(vertical = 4.dp),
        ) { content() }
    }
}
