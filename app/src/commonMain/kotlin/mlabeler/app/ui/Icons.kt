package mlabeler.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons on a 24 grid. Paths in SVG syntax; "F:" prefix = filled. */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            val filled = p.startsWith("F:")
            val nodes = PathParser().parsePathString(p.removePrefix("F:")).toNodes()
            if (filled) {
                b.addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val play = icon("play", "F:M7 4.5v15l12.5-7.5z")
    val stop = icon("stop", "F:M6.5 6.5h11v11h-11z")
    val loop = icon("loop", "M5 11V9a3 3 0 0 1 3-3h10", "M15 3l3 3-3 3", "M19 13v2a3 3 0 0 1-3 3H6", "M9 21l-3-3 3-3")
    val undo = icon("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val redo = icon("redo", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val save = icon("save", "M5 4h11l3 3v13H5z", "M8 4v5h7V4", "M8 20v-6h8v6")
    val split = icon("split", "M12 3v18", "M4 8h5v8H4", "M20 8h-5v8h5")
    val merge = icon("merge", "M4 8h16v8H4z", "M12 5v3", "M12 16v3")
    val folder = icon("folder", "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z")
    val file = icon("file", "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val menu = icon("menu", "M4 7h16", "M4 12h16", "M4 17h16")
    val more = icon("more", "F:M5 10.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z", "F:M12 10.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z", "F:M19 10.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z")
    val search = icon("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M15.5 15.5L20 20")
    val starOff = icon("star", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z")
    val starOn = icon("starOn", "F:M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.9z")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val circle = icon("circle", "M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14z")
    val zoomIn = icon("zoomIn", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M15.5 15.5L20 20", "M8 10.5h5", "M10.5 8v5")
    val zoomOut = icon("zoomOut", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M15.5 15.5L20 20", "M8 10.5h5")
    val fit = icon("fit", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val ripple = icon("ripple", "M5 5v14", "M10 12h10", "M16 8l4 4-4 4", "M10 8l3 4-3 4")
    val link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val left = icon("left", "M15 5l-7 7 7 7")
    val right = icon("right", "M9 5l7 7-7 7")
    val up = icon("up", "M5 15l7-7 7 7")
    val down = icon("down", "M5 9l7 7 7-7")
    val prevFile = icon("prevFile", "M11 6l-6 6 6 6", "M19 6l-6 6 6 6")
    val nextFile = icon("nextFile", "M13 6l6 6-6 6", "M5 6l6 6-6 6")
    val nudgeLeft = icon("nudgeLeft", "M14 5v14", "M10 9l-3 3 3 3")
    val nudgeRight = icon("nudgeRight", "M10 5v14", "M14 9l3 3-3 3")
    val panelLeft = icon("panelLeft", "M4 5h16v14H4z", "M9 5v14")
    val panelRight = icon("panelRight", "M4 5h16v14H4z", "M15 5v14")
    val settings = icon(
        "settings", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z",
    )
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val eye = icon("eye", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val eyeOff = icon("eye-off", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", "M4 4l16 16")
    val trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val tag = icon("tag", "M3 12V4h8l10 10-8 8z", "M7.5 7.5h.01")
    val warn = icon("warn", "M12 4l9 16H3z", "M12 10v4", "M12 17v.01")
    val command = icon("command", "M4 6h16v12H4z", "M7 10l3 2-3 2", "M12 14h5")
    val back = icon("back", "M15 5l-7 7 7 7")
    val layers = icon("layers", "M12 4l8 4-8 4-8-4z", "M4 12l8 4 8-4", "M4 16l8 4 8-4")
    val magic = icon("magic", "M4 20L15 9", "M14 4v3", "M19 9h-3", "M17.5 5.5l-2 2", "M20 14v.01", "M10 4v.01")
    val plugin = icon("plugin", "M9 3v4", "M15 3v4", "M6 7h12v4a6 6 0 0 1-12 0z", "M12 17v4")
    val pipette = icon("pipette", "M14.5 4.5l5 5", "M17 2.5a2.1 2.1 0 0 1 3 3l-2 2-3-3z", "M15.5 7.5L6 17l-1.5 3.5L8 19l9.5-9.5")
    val soundEdit = icon("soundEdit", "M3 12h2", "M7 8v8", "M11 5v14", "M15 9v6", "M15 21l2-5 5-5-2-2-5 5z")
    val lock = icon("lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val unlock = icon("unlock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2")
    val home = icon("home", "M4 11l8-7 8 7", "M6 9.5V20h12V9.5")
}
