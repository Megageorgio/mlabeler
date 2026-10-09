package mlabeler.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mlabeler.app.readyToDraw
import kotlin.math.roundToInt

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
    // the picture is read and decoded away from the interface, once, and kept no bigger than a large screen
    // (a camera photo is tens of megapixels: drawn as is, every frame copied and scaled it down again)
    val image by produceState<ImageBitmap?>(null, c.bgImage) {
        value = if (c.bgImage.isBlank()) null else withContext(Dispatchers.Default) {
            runCatching {
                mlabeler.app.decodeImage(mlabeler.core.io.PlatformFs.read(c.bgImage))?.let { fitWithin(it, MAX_PICTURE_SIDE) }
            }.getOrNull()
        }
    }
    // colour, gradient and picture are put together once into one image the size of the window, so a frame only
    // copies it, unscaled (playback and the cursor redraw the window many times a second)
    var px by remember { mutableStateOf(IntSize.Zero) }
    val alpha = c.bgImageAlpha.coerceIn(0f, 1f)
    val baked by produceState<ImageBitmap?>(null, image, px, c.bg, c.bgGradient, alpha) {
        val img = image
        if (img == null || px.width <= 0 || px.height <= 0) { value = null; return@produceState }
        // while the window is being resized (or the theme edited) the last one is stretched until it settles
        if (value != null) delay(150)
        value = withContext(Dispatchers.Default) { composeBackground(px, c.bg, c.bgGradient, img, alpha) }
    }
    return this.then(androidx.compose.ui.Modifier.onSizeChanged { px = it }.drawBehind {
        val b = if (image != null) baked else null
        if (b != null) {
            drawImage(b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        } else {
            drawRect(c.bg)
            if (c.bgGradient.size >= 2) drawRect(Brush.linearGradient(c.bgGradient))
        }
    })
}

/** The longest side a background picture is kept at (4K). */
private const val MAX_PICTURE_SIDE = 3840

/** [image] made smaller (smoothly) when a side is longer than [max]; as is otherwise. */
private fun fitWithin(image: ImageBitmap, max: Int): ImageBitmap {
    val scale = max.toFloat() / maxOf(image.width, image.height)
    if (scale >= 1f) return image.readyToDraw()
    val w = (image.width * scale).roundToInt().coerceAtLeast(1); val h = (image.height * scale).roundToInt().coerceAtLeast(1)
    val out = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
        drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(w, h), filterQuality = FilterQuality.High)
    }
    return out.readyToDraw()
}

/** The whole background, [px] in size: [bg], the [gradient] over it and the picture over that, covering it and cut to fit. */
private fun composeBackground(px: IntSize, bg: Color, gradient: List<Color>, image: ImageBitmap, alpha: Float): ImageBitmap {
    val out = ImageBitmap(px.width, px.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(px.width.toFloat(), px.height.toFloat())) {
        drawRect(bg)
        if (gradient.size >= 2) drawRect(Brush.linearGradient(gradient))
        val iw = image.width.toFloat(); val ih = image.height.toFloat()
        val scale = maxOf(size.width / iw, size.height / ih)
        val w = iw * scale; val h = ih * scale
        drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = alpha, filterQuality = FilterQuality.High)
    }
    return out.readyToDraw()
}
