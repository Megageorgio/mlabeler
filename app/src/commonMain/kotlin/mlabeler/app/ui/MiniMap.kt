package mlabeler.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole recording in a thin strip: its loudness, which parts the phoneme lane names, the problems found, and
 * the part on screen as a window, the rest dimmed. It is the scroll bar too: dragging the window moves the view,
 * dragging its edge zooms, a press elsewhere brings the view there, the wheel scrolls (with Ctrl it zooms).
 */
@Composable
fun MiniMap(ed: EditorState) {
    val c = T.c
    val audio = ed.audio ?: return
    // loudness in 600 columns, made once per recording (every few samples are enough for a picture)
    val cols = 600
    val env = remember(audio) {
        val n = audio.samples.size
        val per = max(1, n / cols)
        val step = max(1, per / 64)
        FloatArray(cols) { k ->
            var m = 0f
            var i = k * per
            val end = minOf(n, i + per)
            while (i < end) { val v = abs(audio.samples[i]); if (v > m) m = v; i += step }
            m
        }.let { e -> val top = e.maxOrNull()?.takeIf { it > 0f } ?: 1f; FloatArray(cols) { e[it] / top } }
    }
    val dur = ed.duration
    if (dur <= 0) return
    // what the pointer is over: 0 nothing, 1 the window, 2 its left edge, 3 its right edge, 4 outside it
    var over by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(0) }
    val edgeDp = 6.dp
    fun spanPx(w: Float): Pair<Float, Float> {
        val d = ed.duration
        val vs = (ed.viewStart / d * w).toFloat().coerceIn(0f, w)
        val ve = ((ed.viewStart + ed.visibleDuration) / d * w).toFloat().coerceIn(vs + 2f, w)
        return vs to ve
    }
    fun partAt(x: Float, w: Float, edge: Float): Int {
        val (vs, ve) = spanPx(w)
        val e = edge.coerceAtMost((ve - vs) / 3).coerceAtLeast(2f)
        return when {
            abs(x - vs) <= maxOf(e, 4f) && x <= vs + e -> 2
            abs(x - ve) <= maxOf(e, 4f) && x >= ve - e -> 3
            x in vs..ve -> 1
            else -> 4
        }
    }
    val icon = when (if (active != 0) active else over) {
        2, 3 -> mlabeler.app.resizeHorizontalIcon
        1 -> PointerIcon.Hand
        else -> PointerIcon.Default
    }
    Canvas(
        Modifier.fillMaxWidth().height(if (mlabeler.app.Platform.isMobile) 30.dp else 24.dp)
            .pointerHoverIcon(icon)
            .pointerInput(audio) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        val w = size.width.toFloat()
                        when (ev.type) {
                            PointerEventType.Exit -> over = 0
                            PointerEventType.Move, PointerEventType.Enter -> if (!ch.pressed) over = partAt(ch.position.x, w, edgeDp.toPx())
                            PointerEventType.Scroll -> {
                                val d = ch.scrollDelta.y.takeIf { it != 0f } ?: ch.scrollDelta.x
                                if (ev.keyboardModifiers.isCtrlPressed) {
                                    ed.zoom(if (d > 0) 1 / 1.25 else 1.25, (ch.position.x / w).toDouble() * ed.duration)
                                } else ed.scrollBy(d * ed.viewWidthPx * 0.15)
                                ch.consume()
                            }
                            PointerEventType.Press -> {
                                val d = ed.duration
                                val x0 = ch.position.x
                                val part = partAt(x0, w, edgeDp.toPx())
                                active = part
                                ch.consume()
                                fun timeAt(x: Float) = (x / w).coerceIn(0f, 1f).toDouble() * d
                                val start0 = ed.viewStart
                                val end0 = ed.viewStart + ed.visibleDuration
                                val minLen = (ed.viewWidthPx / 20000.0).coerceAtLeast(0.01)
                                // a press outside the window brings the view there, then it is dragged like the window
                                if (part == 4) { ed.viewStart = timeAt(x0) - ed.visibleDuration / 2; ed.clampView() }
                                val grab = timeAt(x0) - ed.viewStart
                                while (true) {
                                    val e2 = awaitPointerEvent()
                                    val c2 = e2.changes.firstOrNull() ?: break
                                    if (!c2.pressed) { c2.consume(); break }
                                    val t = timeAt(c2.position.x)
                                    when (part) {
                                        2 -> setSpan(ed, minOf(t, end0 - minLen).coerceAtLeast(0.0), end0)
                                        3 -> setSpan(ed, start0, maxOf(t, start0 + minLen).coerceAtMost(d))
                                        else -> { ed.viewStart = t - grab; ed.clampView() }
                                    }
                                    c2.consume()
                                }
                                active = 0
                                over = partAt(x0, w, edgeDp.toPx())
                            }
                            else -> {}
                        }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRect(c.laneBg)
        // loudness, mirrored around the middle
        val mid = h / 2
        val wave = c.wave.copy(alpha = 0.5f)
        for (x in 0 until w.toInt()) {
            val v = env[(x * cols / w).toInt().coerceIn(0, cols - 1)]
            val a = v * h * 0.38f
            if (a > 0.3f) drawRect(wave, Offset(x.toFloat(), mid - a), Size(1f, 2 * a))
        }
        // what the phoneme lane names, along the bottom edge
        val d = ed.doc
        val ph = d?.tiers?.getOrNull(d.phonemeTierIndex()) as? IntervalTier
        if (ph != null) for (i in 0 until ph.size) {
            if (ph.texts[i].isEmpty()) continue
            val a = (ph.startOf(i) / dur * w).toFloat()
            val b = (ph.endOf(i) / dur * w).toFloat()
            drawRect(c.accent.copy(alpha = 0.5f), Offset(a, h - 2.dp.toPx()), Size(max(1f, b - a), 2.dp.toPx()))
        }
        // problems as small marks at the top
        for (p in ed.problems) {
            val t = (d?.tiers?.getOrNull(p.ref.tier) as? IntervalTier)?.let { it.startOf(p.ref.index.coerceIn(0, it.size - 1)) } ?: continue
            val x = (t / dur * w).toFloat()
            drawRect(if (p.severity == mlabeler.core.check.Severity.Error) c.danger else c.warn, Offset(x, 0f), Size(max(1f, 1.5f * density), 3.dp.toPx()))
        }
        // the part on screen stays bright, the rest is dimmed
        val (vs, ve) = spanPx(w)
        val dim = c.bg.copy(alpha = if (c.dark) 0.55f else 0.45f)
        if (vs > 0f) drawRect(dim, Offset(0f, 0f), Size(vs, h))
        if (ve < w) drawRect(dim, Offset(ve, 0f), Size(w - ve, h))
        val hot = active != 0 || over in 1..3
        val r = CornerRadius(if (c.square) 0f else 3.dp.toPx())
        drawRoundRect(c.text.copy(alpha = if (hot) 0.45f else 0.25f), Offset(vs + 0.5f, 0.5f), Size(ve - vs - 1f, h - 1f), r, style = Stroke(1f * density))
        // grips on the edges while the pointer is near
        if (hot && ve - vs > 14.dp.toPx()) {
            val gh = h * 0.45f
            val gw = 2.dp.toPx()
            for ((x, side) in listOf(vs to 2, ve to 3)) {
                val strong = active == side || (active == 0 && over == side)
                drawRoundRect(if (strong) c.accent else c.text.copy(alpha = 0.5f), Offset(x - gw / 2 + (if (side == 2) 2f else -2f) * density, mid - gh / 2),
                    Size(gw, gh), CornerRadius(if (c.square) 0f else gw / 2))
            }
        }
        // the playhead
        ed.playhead?.let { t -> val x = (t / dur * w).toFloat(); drawRect(c.playhead, Offset(x, 0f), Size(max(1f, density), h)) }
    }
}

/** Shows [from]..[to] across the timeline (the minimap's edges zoom this way). */
private fun setSpan(ed: EditorState, from: Double, to: Double) {
    if (to <= from || ed.viewWidthPx <= 1f) return
    ed.pixelsPerSecond = (ed.viewWidthPx / (to - from)).coerceIn(ed.viewWidthPx / ed.duration, 20000.0)
    ed.viewStart = from
    ed.clampView()
}
