package mlabeler.app

import java.io.File
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.windows.WindowsLibrary

// The taskbar groups windows by the program they belong to. In the portable build mLabeler.exe only starts
// runtime\bin\java.exe, so the window belongs to java.exe: a pinned mLabeler.exe opened a second, separate button.
// The window takes the name Windows gives mLabeler.exe itself (its path), so it lands on the pinned button.
// Must run before the first window is made.
internal fun joinLauncherOnTaskbar() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows") || Platform.legacyWindows) return
    runCatching {
        // only when Java runs as its own program (the .msi build runs inside mLabeler.exe already)
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        if (!File(exe).name.lowercase().startsWith("java")) return
        val launcher = File(System.getProperty("java.home")).parentFile?.let { File(it, "mLabeler.exe") }
        if (launcher == null || !launcher.isFile) return
        val id = windowsAppId(launcher.canonicalPath)
        val set = WindowsLibrary("shell32.dll").getFunctionAddress("SetCurrentProcessExplicitAppUserModelID")
        if (set == 0L) return
        MemoryStack.stackPush().use { s -> JNI.invokePI(MemoryUtil.memAddress(s.UTF16(id)), set) }
    }
}

// The name Windows gives a program without one of its own: its path, with the system folders written as their
// known-folder ids ("{6D809377-…}\mLabeler\mLabeler.exe" for Program Files), as Get-StartApps shows them.
internal fun windowsAppId(path: String, env: (String) -> String? = System::getenv): String {
    val root = env("SystemRoot") ?: "C:\\Windows"
    val folders = listOfNotNull(
        env("ProgramW6432")?.let { it to "{6D809377-6AF0-444B-8957-A3773F02200E}" },
        env("ProgramFiles(x86)")?.let { it to "{7C5A40EF-A0FB-4BFC-874A-C0F2E0B9FA8E}" },
        "$root\\System32" to "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}",
        "$root\\SysWOW64" to "{D65231B0-B2F1-4857-A4CE-A8E7C6EA7D27}",
        root to "{F38BF404-1D43-42F2-9305-67DE0B28FC23}",
    ).sortedByDescending { it.first.length }
    for ((dir, id) in folders) {
        val prefix = dir.trimEnd('\\') + "\\"
        if (path.startsWith(prefix, ignoreCase = true)) return id + path.substring(prefix.length - 1)
    }
    return path
}
