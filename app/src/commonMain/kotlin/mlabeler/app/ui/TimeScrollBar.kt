package mlabeler.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import kotlin.math.max

/**
 * An ordinary horizontal scroll bar under the picture: a thumb as wide as the visible part, arrows at the ends
 * (held down they keep scrolling), a click beside the thumb turns a page. In the square themes it is drawn like the
 * classic Windows one.
 */
@Composable
fun TimeScrollBar(ed: EditorState) {
    val c = T.c
    val classic = c.square
    val h = if (classic) 17.dp else 12.dp
    val scope = rememberCoroutineScope()
    fun repeatWhilePressed(step: () -> Unit): Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            val job = scope.launch { step(); delay(350); while (true) { step(); delay(40) } }
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) break
            }
            job.cancel()
        }
    }
    Row(Modifier.fillMaxWidth().height(h).background(if (classic) Color(0xFFE6E3DC) else c.panel)) {
        if (classic) Canvas(Modifier.width(h).fillMaxHeight().then(repeatWhilePressed { ed.scrollBy(-ed.viewWidthPx * 0.05) })) { classicButton(left = true) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(
                Modifier.fillMaxWidth().fillMaxHeight().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val dur = ed.duration.takeIf { it > 0 } ?: return@awaitEachGesture
                        val vis = ed.visibleDuration.coerceAtMost(dur)
                        val thumbW = max(24f, (vis / dur * w).toFloat())
                        val travel = (w - thumbW).coerceAtLeast(1f)
                        val thumbX = (ed.viewStart / (dur - vis).coerceAtLeast(1e-9) * travel).toFloat().coerceIn(0f, travel)
                        val onThumb = down.position.x in thumbX..(thumbX + thumbW)
                        if (!onThumb) {
                            // a page towards the click, repeated while held
                            val dir = if (down.position.x < thumbX) -1 else 1
                            val job = scope.launch {
                                ed.scrollBy(dir * ed.viewWidthPx * 0.9); delay(350)
                                while (true) { ed.scrollBy(dir * ed.viewWidthPx * 0.9); delay(80) }
                            }
                            while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break }
                            job.cancel()
                            return@awaitEachGesture
                        }
                        val grab = down.position.x - thumbX
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val x = (ch.position.x - grab).coerceIn(0f, travel)
                            ed.viewStart = x / travel * (dur - vis)
                            ed.clampView()
                            ch.consume()
                        }
                    }
                },
            ) {
                val w = size.width
                val dur = ed.duration
                if (dur <= 0) return@Canvas
                val vis = ed.visibleDuration.coerceAtMost(dur)
                val thumbW = max(24f, (vis / dur * w).toFloat())
                val travel = (w - thumbW).coerceAtLeast(1f)
                val thumbX = (ed.viewStart / (dur - vis).coerceAtLeast(1e-9) * travel).toFloat().coerceIn(0f, travel)
                if (classic) {
                    // dithered track and a raised thumb
                    drawRect(Color(0xFFF1EFE9))
                    bevel(Offset(thumbX, 0f), Size(thumbW, size.height))
                } else {
                    val pad = 3.dp.toPx()
                    drawRoundRect(c.muted.copy(alpha = 0.45f), Offset(thumbX, pad), Size(thumbW, size.height - 2 * pad), CornerRadius(size.height / 2))
                }
            }
        }
        if (classic) Canvas(Modifier.width(h).fillMaxHeight().then(repeatWhilePressed { ed.scrollBy(ed.viewWidthPx * 0.05) })) { classicButton(left = false) }
    }
}

/** A raised 3D box: light top/left, dark bottom/right edges. */
private fun DrawScope.bevel(at: Offset, s: Size) {
    val face = Color(0xFFD4D0C8)
    drawRect(face, at, s)
    val px = 1f
    drawLine(Color.White, at, Offset(at.x + s.width - px, at.y), px)
    drawLine(Color.White, at, Offset(at.x, at.y + s.height - px), px)
    drawLine(Color(0xFF404040), Offset(at.x, at.y + s.height - px), Offset(at.x + s.width, at.y + s.height - px), px)
    drawLine(Color(0xFF404040), Offset(at.x + s.width - px, at.y), Offset(at.x + s.width - px, at.y + s.height), px)
    drawLine(Color(0xFF808080), Offset(at.x + px, at.y + s.height - 2 * px), Offset(at.x + s.width - px, at.y + s.height - 2 * px), px)
    drawLine(Color(0xFF808080), Offset(at.x + s.width - 2 * px, at.y + px), Offset(at.x + s.width - 2 * px, at.y + s.height - px), px)
}

private fun DrawScope.classicButton(left: Boolean) {
    bevel(Offset.Zero, size)
    val cx = size.width / 2; val cy = size.height / 2
    val a = size.height * 0.16f
    val p = Path().apply {
        if (left) { moveTo(cx - a, cy); lineTo(cx + a, cy - 2 * a); lineTo(cx + a, cy + 2 * a) }
        else { moveTo(cx + a, cy); lineTo(cx - a, cy - 2 * a); lineTo(cx - a, cy + 2 * a) }
        close()
    }
    drawPath(p, Color.Black)
}
