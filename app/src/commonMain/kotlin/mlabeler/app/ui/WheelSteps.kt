package mlabeler.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent

/** Wheel movement in notches, including fractional notches from a trackpad. */
internal expect fun PointerEvent.wheelSteps(delta: Offset): Offset
