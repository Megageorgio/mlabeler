package mlabeler.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.abs

internal actual fun PointerEvent.wheelSteps(delta: Offset): Offset {
    val wheel = nativeEvent as? WheelEvent ?: return delta
    // Read the mode first: some browsers choose delta units based on whether it was read.
    val unitsPerNotch = when (wheel.deltaMode.toInt()) {
        1 -> 3.0     // lines
        2 -> 1.0     // pages
        else -> 100.0 // CSS pixels
    }
    fun steps(value: Double) = (value / unitsPerNotch).coerceIn(-1.0, 1.0).toFloat()
    val x = wheel.deltaX
    val y = wheel.deltaY
    return when {
        abs(x) >= abs(y) -> Offset(steps(x), 0f)
        wheel.shiftKey -> Offset(steps(y), 0f)
        else -> Offset(0f, steps(y))
    }
}
