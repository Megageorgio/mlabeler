package mlabeler.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

private fun DragAndDropEvent.transferable(): Transferable? = when (val e = nativeEvent) {
    is DropTargetDropEvent -> e.transferable
    is DropTargetDragEvent -> e.transferable
    is Transferable -> e
    // other wrappers: whatever offers getTransferable()
    null -> null
    else -> runCatching { e.javaClass.getMethod("getTransferable").invoke(e) as? Transferable }.getOrNull()
}

private fun DragAndDropEvent.hasFiles(): Boolean = transferable()?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true

private fun DragAndDropEvent.files(): List<String> = runCatching {
    @Suppress("UNCHECKED_CAST")
    (transferable()?.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)?.map { it.absolutePath }
}.getOrNull() ?: emptyList()

@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Boolean): Modifier = composed {
    val hover = rememberUpdatedState(onHover)
    val drop = rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover.value(true)
            override fun onExited(event: DragAndDropEvent) = hover.value(false)
            override fun onEnded(event: DragAndDropEvent) = hover.value(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover.value(false)
                val files = event.files()
                return files.isNotEmpty() && drop.value(files)
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { it.hasFiles() }, target = target)
}
