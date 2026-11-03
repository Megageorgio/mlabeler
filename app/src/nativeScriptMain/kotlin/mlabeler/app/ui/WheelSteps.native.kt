package mlabeler.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun PointerEvent.wheelSteps(delta: Offset): Offset = delta
