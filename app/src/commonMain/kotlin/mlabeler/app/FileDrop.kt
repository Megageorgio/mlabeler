package mlabeler.app

import androidx.compose.ui.Modifier

/**
 * Files dragged from the system file manager onto this element (computers only).
 * [onHover] says whether files are over it; [onDrop] gets their paths and returns whether they were taken.
 */
expect fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Boolean): Modifier
