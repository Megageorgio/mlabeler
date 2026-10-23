package mlabeler.app

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** AWT marshals AppKit work onto the Cocoa main thread; a Swing dispatcher alone does not do that for NFD. */
internal object AwtFilePicker {
    // Accessed only on the EDT, including while a modal dialog runs its nested event loop.
    private var showing = false

    fun pick(title: String, start: String?, directories: Boolean, extensions: List<String> = emptyList()): String? = onEdt {
        if (showing) return@onEdt null
        showing = true
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        var dialog: FileDialog? = null
        try {
            if (Platform.isMac) System.setProperty(property, directories.toString())
            val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog = d
            start?.takeIf { File(it).isDirectory }?.let { d.directory = it }
            if (!directories && extensions.isNotEmpty()) d.setFilenameFilter { _, name ->
                extensions.any { name.endsWith(".$it", ignoreCase = true) }
            }
            d.isVisible = true
            val file = d.file ?: return@onEdt null
            val directory = d.directory ?: return@onEdt null
            if (directories && !Platform.isMac) directory else File(directory, file).path
        } finally {
            dialog?.dispose()
            if (Platform.isMac) {
                if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            }
            showing = false
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val task = FutureTask(Callable { block() })
        EventQueue.invokeLater(task)
        return try { task.get() }
        catch (e: ExecutionException) { throw e.cause ?: e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
    }
}
