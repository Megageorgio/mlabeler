package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import kotlin.math.roundToInt

val notepadTitle = L("Notepad", "Блокнот")
private val notepadHint = L("Any text: lyrics, notes… Kept with the folder.", "Любой текст: слова песни, заметки… Хранится вместе с папкой.")

/**
 * A plain text window over the picture: no buttons, only the text. Dragged by its top strip, resized by the corner,
 * kept in the folder (.mlabeler/notepad.txt).
 */
@Composable
fun FloatingNotepad(app: AppState, ed: EditorState) {
    val c = T.c
    val l = app.settings.layout
    val density = LocalDensity.current
    val path = remember(ed.workspace.root) { Paths.join(Paths.join(ed.workspace.root, ".mlabeler"), "notepad.txt") }
    var text by remember(path) { mutableStateOf(runCatching { PlatformFs.read(path).decodeToString() }.getOrDefault("")) }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(500)
        runCatching {
            if (text.isNotEmpty() || PlatformFs.exists(path)) {
                PlatformFs.mkdirs(Paths.parent(path))
                PlatformFs.write(path, text.encodeToByteArray())
            }
        }
    }
    var x by remember { mutableStateOf(l.notepadX) }
    var y by remember { mutableStateOf(l.notepadY) }
    var w by remember { mutableStateOf(l.notepadW) }
    var h by remember { mutableStateOf(l.notepadH) }
    fun keep() = app.update { it.copy(layout = it.layout.copy(notepadX = x, notepadY = y, notepadW = w, notepadH = h)) }
    val shape = RoundedCornerShape(c.radius)
    Box(
        Modifier.offset { IntOffset((x * density.density).roundToInt(), (y * density.density).roundToInt()) }
            .size(w.dp, h.dp).shadow(8.dp, shape).clip(shape).background(c.panel).border(c.borderWidth, c.border, shape),
    ) {
        Column(Modifier.fillMaxSize()) {
            // the strip to drag it by, with a close mark
            Row(
                Modifier.fillMaxWidth().height(14.dp).background(c.panelAlt)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { keep() }) { ch, d ->
                            ch.consume()
                            x = (x + d.x / density.density).coerceAtLeast(0f)
                            y = (y + d.y / density.density).coerceAtLeast(0f)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f))
                Text("×", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp)
                    .clickable { app.update { it.copy(layout = it.layout.copy(showNotepad = false)) } })
            }
            BasicTextField(
                text, { text = it },
                textStyle = TextStyle(color = c.text, fontSize = l.notepadFont.sp, fontFamily = T.font),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()).trackTextFocus(),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(notepadHint(), color = c.muted, fontSize = 12.sp)
                        inner()
                    }
                },
            )
        }
        // corner to resize
        Box(
            Modifier.align(Alignment.BottomEnd).size(14.dp)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { keep() }) { ch, d ->
                        ch.consume()
                        w = (w + d.x / density.density).coerceIn(140f, 1600f)
                        h = (h + d.y / density.density).coerceIn(80f, 1200f)
                    }
                },
        ) { Text("◢", color = c.muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd)) }
    }
}

