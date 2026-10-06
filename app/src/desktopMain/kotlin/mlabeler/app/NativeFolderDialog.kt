package mlabeler.app

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NativeFileDialog

/** System folder picker through nativefiledialog (LWJGL). Throws when the native part can't load. */
internal object NativeFolderDialog {
    private val lock = Any()

    /** Returns the picked file, or null when cancelled. */
    fun pickFile(extensions: List<String>, start: String?): String? = synchronized(lock) {
        check(NativeFileDialog.NFD_Init() == NativeFileDialog.NFD_OKAY) { NativeFileDialog.NFD_GetError() ?: "init failed" }
        try {
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                val filters = if (extensions.isEmpty()) null else org.lwjgl.util.nfd.NFDFilterItem.malloc(1, stack).also { f ->
                    f[0].name(stack.UTF8("Files")).spec(stack.UTF8(extensions.joinToString(",")))
                }
                when (NativeFileDialog.NFD_OpenDialog(out, filters, start?.takeIf { java.io.File(it).isDirectory })) {
                    NativeFileDialog.NFD_OKAY -> {
                        val path = out.getStringUTF8(0)
                        NativeFileDialog.NFD_FreePath(out.get(0))
                        path
                    }
                    NativeFileDialog.NFD_CANCEL -> null
                    else -> error(NativeFileDialog.NFD_GetError() ?: "dialog failed")
                }
            }
        } finally {
            NativeFileDialog.NFD_Quit()
        }
    }

    /** Returns the picked folder, or null when cancelled. */
    fun pick(start: String?): String? = synchronized(lock) {
        check(NativeFileDialog.NFD_Init() == NativeFileDialog.NFD_OKAY) { NativeFileDialog.NFD_GetError() ?: "init failed" }
        try {
            MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                when (NativeFileDialog.NFD_PickFolder(out, start?.takeIf { java.io.File(it).isDirectory })) {
                    NativeFileDialog.NFD_OKAY -> {
                        val path = out.getStringUTF8(0)
                        NativeFileDialog.NFD_FreePath(out.get(0))
                        path
                    }
                    NativeFileDialog.NFD_CANCEL -> null
                    else -> error(NativeFileDialog.NFD_GetError() ?: "dialog failed")
                }
            }
        } finally {
            NativeFileDialog.NFD_Quit()
        }
    }
}
