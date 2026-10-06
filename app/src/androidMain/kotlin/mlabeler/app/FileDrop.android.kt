package mlabeler.app

import androidx.compose.ui.Modifier

actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Boolean): Modifier = this
