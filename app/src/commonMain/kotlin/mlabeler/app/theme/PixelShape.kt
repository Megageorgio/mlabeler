package mlabeler.app.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Set by the theme: shapes are drawn without smoothing (corners as pixel steps). */
object CrispShapes {
    var on: Boolean = false
}

/** Rounded corners; without smoothing they are pixel steps instead of a smoothed curve. */
fun RoundedCornerShape(size: Dp): CornerBasedShape = RoundedCornerShape(size, size, size, size)

fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): CornerBasedShape =
    if (CrispShapes.on) PixelRoundedShape(CornerSize(topStart), CornerSize(topEnd), CornerSize(bottomEnd), CornerSize(bottomStart))
    else androidx.compose.foundation.shape.RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)

/**
 * A rounded rectangle made only of horizontal and vertical edges on whole pixels: the corners are stairs, like in
 * old programs, so nothing gets a smoothed edge.
 */
class PixelRoundedShape(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) :
    CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        PixelRoundedShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float, layoutDirection: LayoutDirection): Outline {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val ltr = layoutDirection == LayoutDirection.Ltr
        fun px(v: Float) = v.roundToInt().coerceIn(0, minOf(w, h) / 2)
        val tl = px(if (ltr) topStart else topEnd)
        val tr = px(if (ltr) topEnd else topStart)
        val br = px(if (ltr) bottomEnd else bottomStart)
        val bl = px(if (ltr) bottomStart else bottomEnd)
        if (tl == 0 && tr == 0 && br == 0 && bl == 0) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        // pixels cut from the edge on row k (0 = the outer row) of a corner of radius r
        fun inset(r: Int, k: Int): Int {
            val d = r - k - 0.5
            return (r - floor(sqrt((r * r - d * d).coerceAtLeast(0.0)) + 0.5)).toInt().coerceAtLeast(0)
        }
        fun left(y: Int) = when { y < tl -> inset(tl, y); y >= h - bl -> inset(bl, h - 1 - y); else -> 0 }
        fun right(y: Int) = w - when { y < tr -> inset(tr, y); y >= h - br -> inset(br, h - 1 - y); else -> 0 }
        val p = Path()
        // down the right side, then up the left side, adding a corner only where the edge moves
        var x = right(0)
        p.moveTo(left(0).toFloat(), 0f)
        p.lineTo(x.toFloat(), 0f)
        for (y in 1 until h) {
            val nx = right(y)
            if (nx != x) { p.lineTo(x.toFloat(), y.toFloat()); p.lineTo(nx.toFloat(), y.toFloat()); x = nx }
        }
        p.lineTo(x.toFloat(), h.toFloat())
        x = left(h - 1)
        p.lineTo(x.toFloat(), h.toFloat())
        for (y in h - 2 downTo 0) {
            val nx = left(y)
            if (nx != x) { p.lineTo(x.toFloat(), (y + 1).toFloat()); p.lineTo(nx.toFloat(), (y + 1).toFloat()); x = nx }
        }
        p.lineTo(x.toFloat(), 0f)
        p.close()
        return Outline.Generic(p)
    }

    override fun equals(other: Any?) = other is PixelRoundedShape && other.topStart == topStart && other.topEnd == topEnd &&
        other.bottomEnd == bottomEnd && other.bottomStart == bottomStart
    override fun hashCode() = listOf(topStart, topEnd, bottomEnd, bottomStart).hashCode()
}
