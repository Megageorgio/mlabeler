package mlabeler.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * How the interface moves: "normal" (short fades, slides and colour changes), "reduced" (fades only, quicker) or
 * "off". Only the interface moves: nothing that is edited (boundaries, the cursor, the zoom) is ever animated.
 */
object Motion {
    val Local = staticCompositionLocalOf { "normal" }

    @Composable
    fun on(): Boolean = Local.current != "off"

    /** Whether things may also slide and grow (not only fade). */
    @Composable
    fun full(): Boolean = Local.current == "normal"

    /** [ms] for this setting: as is, halved, or 0. */
    @Composable
    fun ms(ms: Int): Int = when (Local.current) { "off" -> 0; "reduced" -> ms / 2; else -> ms }
}

/** A colour that changes smoothly when the interface may move (at once otherwise). */
@Composable
fun animatedColor(target: Color, ms: Int = 140): State<Color> = animateColorAsState(target, tween(Motion.ms(ms)), label = "color")

/** The window's background in the theme: its colour, its gradient and picture when it has them. */
@Composable
fun androidx.compose.ui.Modifier.themeBackground(): androidx.compose.ui.Modifier {
    val c = mlabeler.app.theme.T.c
    val image = androidx.compose.runtime.remember(c.bgImage) {
        if (c.bgImage.isBlank()) null else runCatching { mlabeler.app.decodeImage(mlabeler.core.io.PlatformFs.read(c.bgImage)) }.getOrNull()
    }
    var m = this.then(androidx.compose.ui.Modifier.background(c.bg))
    if (c.bgGradient.size >= 2) m = m.then(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(c.bgGradient)))
    if (image != null) m = m.then(androidx.compose.ui.Modifier.drawBehind {
        // the picture covers the window, cut to fit, and shows as strongly as the theme says
        val iw = image.width.toFloat(); val ih = image.height.toFloat()
        val scale = maxOf(size.width / iw, size.height / ih)
        val w = iw * scale; val h = ih * scale
        // (cut at the edges: drawBehind does not clip, and what sticks out would cover the title and menus above)
        clipRect {
            drawImage(image, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()), alpha = c.bgImageAlpha.coerceIn(0f, 1f))
        }
    })
    return m
}
