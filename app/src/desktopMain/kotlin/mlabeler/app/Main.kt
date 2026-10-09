package mlabeler.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toComposeImageBitmap

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
    // the window shares the taskbar button of a pinned mLabeler.exe
    joinLauncherOnTaskbar()
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
    app.quit = { app.close(); exitApplication() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        args.firstOrNull()?.let { app.openFolder(it) }
    }
    // menus in the window title: read once, the window is made with or without the system's title
    val ownTitle = androidx.compose.runtime.remember { app.settings.titleBarMenu }
    val mac = Platform.isMac
    Window(
        onCloseRequest = { app.close(); exitApplication() },
        state = state,
        title = app.editor?.let { e -> e.item?.name?.let { "$it — mLabeler" } } ?: "mLabeler",
        undecorated = ownTitle && !mac,
    ) {
        window.minimumSize = java.awt.Dimension(360, 480)
        // every size drawn on its own: the system takes the one that fits the title bar and the taskbar,
        // instead of shrinking one big picture pixel by pixel
        androidx.compose.runtime.LaunchedEffect(Unit) { appIcons?.let { window.iconImages = it } }
        if (!ownTitle) {
            App(app)
        } else {
            if (mac) androidx.compose.runtime.LaunchedEffect(Unit) {
                // the program's picture goes under the system title; the round buttons stay on top of it
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            val frame = androidx.compose.runtime.remember(window) { OwnFrame(window, state, mac) }
            androidx.compose.runtime.CompositionLocalProvider(mlabeler.app.ui.LocalWindowFrame provides frame) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    App(app)
                    if (!mac && state.placement == WindowPlacement.Floating) ResizeEdges(window)
                }
            }
        }
    }
}

/** The window moved, maximized and closed by the program's own title. */
private class OwnFrame(
    private val window: androidx.compose.ui.awt.ComposeWindow,
    private val state: WindowState,
    mac: Boolean,
) : mlabeler.app.ui.WindowFrame {
    override val maximized get() = state.placement != WindowPlacement.Floating
    override val systemButtons = mac
    override val icon: androidx.compose.ui.graphics.painter.Painter? = appIcons?.firstOrNull { it.getWidth(null) == 32 }
        ?.let { runCatching { androidx.compose.ui.graphics.painter.BitmapPainter((it as java.awt.image.BufferedImage).toComposeImageBitmap()) }.getOrNull() }
    override fun minimize() { state.isMinimized = true }
    override fun toggleMaximize() {
        if (state.placement == WindowPlacement.Floating) {
            // without the system's frame the window would cover the task bar
            if (!systemButtons) runCatching {
                val gc = window.graphicsConfiguration
                val b = gc.bounds
                val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
                window.maximizedBounds = java.awt.Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
            }
            state.placement = WindowPlacement.Maximized
        } else state.placement = WindowPlacement.Floating
    }
    override fun close() {
        window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING))
    }

    @androidx.compose.runtime.Composable
    override fun DragArea(modifier: androidx.compose.ui.Modifier, content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(
            modifier
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMaximize() }) }
                .pointerInput(Unit) {
                    var start = java.awt.Point()
                    var from = java.awt.Point()
                    detectDragGestures(
                        onDragStart = {
                            from = java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point()
                            if (state.placement != WindowPlacement.Floating) {
                                // pulling a maximized window down restores it under the pointer
                                val w0 = window.width.toDouble()
                                state.placement = WindowPlacement.Floating
                                val w1 = window.width.toDouble()
                                val frac = ((from.x - window.x) / w0).coerceIn(0.0, 1.0)
                                window.setLocation((from.x - frac * w1).toInt(), window.y)
                            }
                            start = window.location
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = java.awt.MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                            window.setLocation(start.x + p.x - from.x, start.y + p.y - from.y)
                        },
                    )
                },
        ) { content() }
    }
}

/** Edges and corners that resize a window without the system's frame. */
@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.BoxScope.ResizeEdges(window: java.awt.Window) {
    val edge = 5.dp
    val corner = 10.dp
    // dx/dy: which side moves (-1 left/top, 1 right/bottom, 0 none)
    @androidx.compose.runtime.Composable
    fun handle(align: androidx.compose.ui.Alignment, w: androidx.compose.ui.unit.Dp?, h: androidx.compose.ui.unit.Dp?, dx: Int, dy: Int, cursor: Int) {
        var m = androidx.compose.ui.Modifier.align(align)
        m = if (w == null) m.fillMaxWidth() else m.width(w)
        m = if (h == null) m.fillMaxHeight() else m.height(h)
        androidx.compose.foundation.layout.Box(
            m.pointerHoverIcon(PointerIcon(java.awt.Cursor(cursor)))
                .pointerInput(dx, dy) {
                    var b0 = java.awt.Rectangle()
                    var p0 = java.awt.Point()
                    detectDragGestures(
                        onDragStart = { b0 = window.bounds; p0 = java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point() },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = java.awt.MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                            val min = window.minimumSize
                            var x = b0.x; var y = b0.y; var wd = b0.width; var ht = b0.height
                            val ddx = p.x - p0.x
                            val ddy = p.y - p0.y
                            if (dx > 0) wd = maxOf(min.width, b0.width + ddx)
                            if (dx < 0) { wd = maxOf(min.width, b0.width - ddx); x = b0.x + b0.width - wd }
                            if (dy > 0) ht = maxOf(min.height, b0.height + ddy)
                            if (dy < 0) { ht = maxOf(min.height, b0.height - ddy); y = b0.y + b0.height - ht }
                            window.setBounds(x, y, wd, ht)
                        },
                    )
                },
        )
    }
    val A = androidx.compose.ui.Alignment
    handle(A.CenterStart, edge, null, -1, 0, java.awt.Cursor.W_RESIZE_CURSOR)
    handle(A.CenterEnd, edge, null, 1, 0, java.awt.Cursor.E_RESIZE_CURSOR)
    handle(A.TopCenter, null, edge, 0, -1, java.awt.Cursor.N_RESIZE_CURSOR)
    handle(A.BottomCenter, null, edge, 0, 1, java.awt.Cursor.S_RESIZE_CURSOR)
    handle(A.TopStart, corner, corner, -1, -1, java.awt.Cursor.NW_RESIZE_CURSOR)
    handle(A.TopEnd, corner, corner, 1, -1, java.awt.Cursor.NE_RESIZE_CURSOR)
    handle(A.BottomStart, corner, corner, -1, 1, java.awt.Cursor.SW_RESIZE_CURSOR)
    handle(A.BottomEnd, corner, corner, 1, 1, java.awt.Cursor.SE_RESIZE_CURSOR)
}

private val appIcons: List<java.awt.Image>? by lazy {
    runCatching {
        listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).mapNotNull { n ->
            Thread.currentThread().contextClassLoader.getResourceAsStream("icons/icon-$n.png")?.use { javax.imageio.ImageIO.read(it) }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
