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
import androidx.compose.ui.unit.dp
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole recording in a thin strip: its loudness, which parts the phoneme lane names, the problems found, and
 * the part on screen as a frame. A click puts the view there, a drag moves it.
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
    Canvas(
        Modifier.fillMaxWidth().height(if (mlabeler.app.Platform.isMobile) 26.dp else 20.dp).pointerInput(audio) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun go(x: Float) {
                    val t = (x / size.width).coerceIn(0f, 1f) * ed.duration
                    ed.viewStart = t - ed.visibleDuration / 2
                    ed.clampView()
                }
                go(down.position.x); down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    go(ch.position.x); ch.consume()
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawRect(c.laneBg)
        // loudness
        val mid = h * 0.45f
        for (x in 0 until w.toInt()) {
            val v = env[(x * cols / w).toInt().coerceIn(0, cols - 1)]
            val a = v * h * 0.4f
            if (a > 0.3f) drawRect(c.wave.copy(alpha = 0.55f), Offset(x.toFloat(), mid - a), Size(1f, 2 * a))
        }
        // what the phoneme lane names, along the bottom edge
        val d = ed.doc
        val ph = d?.tiers?.getOrNull(d.phonemeTierIndex()) as? IntervalTier
        if (ph != null) for (i in 0 until ph.size) {
            if (ph.texts[i].isEmpty()) continue
            val a = (ph.startOf(i) / dur * w).toFloat()
            val b = (ph.endOf(i) / dur * w).toFloat()
            drawRect(c.accent.copy(alpha = 0.55f), Offset(a, h - 3.dp.toPx()), Size(max(1f, b - a), 2.dp.toPx()))
        }
        // problems as small marks at the top
        for (p in ed.problems) {
            val t = (d?.tiers?.getOrNull(p.ref.tier) as? IntervalTier)?.let { it.startOf(p.ref.index.coerceIn(0, it.size - 1)) } ?: continue
            val x = (t / dur * w).toFloat()
            drawRect(if (p.severity == mlabeler.core.check.Severity.Error) c.danger else c.warn, Offset(x, 0f), Size(max(1f, 1.5f * density), 3.dp.toPx()))
        }
        // the part on screen
        val vs = (ed.viewStart / dur * w).toFloat().coerceIn(0f, w)
        val ve = ((ed.viewStart + ed.visibleDuration) / dur * w).toFloat().coerceIn(vs + 2f, w)
        drawRect(c.accent.copy(alpha = 0.10f), Offset(vs, 0f), Size(ve - vs, h))
        drawRoundRect(c.accent.copy(alpha = 0.8f), Offset(vs, 0.5f), Size(ve - vs, h - 1f), CornerRadius(if (c.square) 0f else 3.dp.toPx()), style = Stroke(1.2f * density))
        // the playhead
        ed.playhead?.let { t -> val x = (t / dur * w).toFloat(); drawRect(c.playhead, Offset(x, 0f), Size(max(1f, density), h)) }
    }
}
