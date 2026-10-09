package mlabeler.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) {
    // where the graphics and folder-dialog libraries unpack themselves if they are not next to the program:
    // the program's own data folder (its path comes from the environment, so any user name works)
    for ((key, sub) in listOf("skiko.data.path" to "skiko", "org.lwjgl.system.SharedLibraryExtractPath" to "lwjgl")) {
        if (System.getProperty(key) == null) runCatching {
            val d = java.io.File(Platform.dataDir(), sub)
            d.mkdirs()
            if (d.isDirectory && d.canWrite()) System.setProperty(key, d.path)
        }
    }
    // old Windows has no DirectX 12, which the graphics library starts with: OpenGL (software drawing when that fails too)
    if (Platform.legacyWindows && System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")
    // a crash leaves its report for the next start
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> CrashLog.write(e.stackTraceToString()); previous?.uncaughtException(t, e) }
    run(args)
}

private fun run(args: Array<String>) = application {
    // MLABELER_WINDOW=1280x800 sets the first window size (used for screenshots)
    val size = System.getenv("MLABELER_WINDOW")?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
    val state = rememberWindowState(size = if (size != null) DpSize(size[0].dp, size[1].dp) else DpSize(1280.dp, 800.dp))
    val app = rememberAppState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        args.firstOrNull()?.let { app.openFolder(it) }
    }
    Window(
        onCloseRequest = { app.close(); exitApplication() },
        state = state,
        title = app.editor?.let { e -> e.item?.name?.let { "$it — mLabeler" } } ?: "mLabeler",
    ) {
        window.minimumSize = java.awt.Dimension(360, 480)
        // every size drawn on its own: the system takes the one that fits the title bar and the taskbar,
        // instead of shrinking one big picture pixel by pixel
        androidx.compose.runtime.LaunchedEffect(Unit) { appIcons?.let { window.iconImages = it } }
        App(app)
    }
}

private val appIcons: List<java.awt.Image>? by lazy {
    runCatching {
        listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).mapNotNull { n ->
            Thread.currentThread().contextClassLoader.getResourceAsStream("icons/icon-$n.png")?.use { javax.imageio.ImageIO.read(it) }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
