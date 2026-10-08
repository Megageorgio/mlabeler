package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import mlabeler.app.theme.RoundedCornerShape
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

/** The notepad's text with its own scroll bar; kept in the folder (.mlabeler/notepad.txt) as it is typed. */
@Composable
fun NotepadText(app: AppState, ed: EditorState, modifier: Modifier) {
    val c = T.c
    val l = app.settings.layout
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
    val scroll = rememberScrollState()
    Box(modifier) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 8.dp, top = 6.dp, end = 14.dp, bottom = 6.dp)) {
            BasicTextField(
                text, { text = it },
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(TextStyle(color = c.text, fontSize = l.notepadFont.sp, fontFamily = T.font)),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().trackTextFocus(),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(notepadHint(), color = c.muted, fontSize = 12.sp)
                        inner()
                    }
                },
            )
        }
        // scroll bar: shown when the text is longer than the window, the thumb can be dragged
        if (scroll.maxValue > 0) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.foundation.Canvas(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp).padding(vertical = 4.dp, horizontal = 2.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            val total = scroll.maxValue + size.height
                            scope.launch { scroll.scrollBy(d.y * total / size.height) }
                        }
                    },
            ) {
                val total = scroll.maxValue + size.height
                val th = (size.height * size.height / total).coerceAtLeast(16f)
                val ty = scroll.value.toFloat() / scroll.maxValue * (size.height - th)
                drawRoundRect(c.muted.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(0f, ty),
                    androidx.compose.ui.geometry.Size(size.width, th), androidx.compose.ui.geometry.CornerRadius(if (c.square) 0f else size.width / 2))
            }
        }
    }
}

/**
 * The notepad as a window over the picture: dragged by its top strip, resized by the corner. The strip also has a
 * button to put it into a side panel.
 */
@Composable
fun FloatingNotepad(app: AppState, ed: EditorState) {
    val c = T.c
    val l = app.settings.layout
    val density = LocalDensity.current
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
            Row(
                Modifier.fillMaxWidth().height(24.dp).background(c.panelAlt)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { keep() }) { ch, d ->
                            ch.consume()
                            x = (x + d.x / density.density).coerceAtLeast(0f)
                            y = (y + d.y / density.density).coerceAtLeast(0f)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notepadTitle(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                IconBtn(Icons.panelRight, toPanelT(), size = 24.dp) { app.update { it.copy(layout = it.layout.copy(notepadDocked = true)) } }
                IconBtn(Icons.close, mlabeler.app.i18n.S.close(), size = 24.dp) { app.update { it.copy(layout = it.layout.copy(showNotepad = false)) } }
            }
            NotepadText(app, ed, Modifier.weight(1f).fillMaxWidth())
        }
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

val toPanelT = L("Into a side panel", "В боковую панель")
val toWindowT = L("Into a window over the picture", "В окно поверх картинки")
