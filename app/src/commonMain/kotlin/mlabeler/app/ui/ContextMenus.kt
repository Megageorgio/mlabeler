package mlabeler.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.io.Item
import mlabeler.core.io.Paths

/** A click does [onClick]; a right click or a long press opens a menu ([onMenu]). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.withContextMenu(onMenu: () -> Unit, onClick: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) { ev.changes.forEach { it.consume() }; onMenu() }
            }
        }
    }
    .combinedClickable(onLongClick = onMenu, onClick = onClick)

private val renameFileT = L("Rename…", "Переименовать…")
private val trashFileT = L("Delete…", "Удалить…")
private val showFileT = L("Show in file manager", "Показать в проводнике")
private val openT = L("Open", "Открыть")
private val renameTitle = L("Rename the recording", "Переименование записи")
private val renameAbout = L(
    "Every file named after it gets the new name too: labels of any format, .trans, MIDI, UTAU caches (.frq…), here and in the label folders. Its oto.ini entries, transcriptions.csv rows, marks and drawn pitch follow.",
    "Новое имя получат и все файлы, названные по записи: разметка любого формата, .trans, MIDI, кэши UTAU (.frq…) — рядом с ней и в папках разметки. Её записи в oto.ini, строки transcriptions.csv, отметки и нарисованная высота тона тоже переедут.",
)
private val renameWill = L("Will be renamed: {0} files, oto entries: {1}, transcriptions.csv rows: {2}", "Будет переименовано: файлов — {0}, записей oto — {1}, строк transcriptions.csv — {2}")
private val nameTaken = L("Can't: {0}", "Нельзя: {0}")
private val badName = L("the name has characters a file name can't have", "в имени есть символы, недопустимые в имени файла")
private val exists = L("already exists", "уже существует")
private val trashTitle = L("Delete the recording?", "Удалить запись?")
private val trashAbout = L(
    "{0} and every file named after it are moved to .mlabeler/trash inside the folder, where they can be taken back. Its oto.ini entries and transcriptions.csv rows are removed (the previous versions of those files are kept there too).",
    "{0} и все файлы, названные по нему, переносятся в .mlabeler/trash внутри папки — оттуда их можно вернуть. Его записи в oto.ini и строки transcriptions.csv удаляются (прежние версии этих файлов тоже сохраняются там).",
)
private val alsoT = L("Also: {0}", "Также: {0}")

/** The menu of a file in the file list. */
@Composable
fun FileMenu(ed: EditorState, index: Int, item: Item, onOpened: () -> Unit, onRename: () -> Unit, onTrash: () -> Unit, close: () -> Unit) {
    val marks = ed.marks(item)
    MenuItems(buildList {
        add(MItem(openT()) { ed.open(index); onOpened() })
        add(MSep)
        add(MItem(S.toggleDone(), checked = marks.done) { ed.setMarks(item) { it.copy(done = !it.done) } })
        add(MItem(S.toggleStar(), checked = marks.star) { ed.setMarks(item) { it.copy(star = !it.star) } })
        add(MSep)
        add(MItem(renameFileT()) { onRename() })
        if (!mlabeler.app.Platform.isMobile) add(MItem(showFileT()) { mlabeler.app.Platform.openInFileManager(Paths.parent(item.audioPath)) })
        add(MSep)
        add(MItem(trashFileT()) { onTrash() })
    }, close)
}

@Composable
fun RenameFileDialog(ed: EditorState, index: Int, close: () -> Unit) {
    val c = T.c
    val item = ed.items.getOrNull(index) ?: return close()
    var name by remember { mutableStateOf(Paths.stem(item.audioPath)) }
    val plan = remember(name) { if (name.trim() == Paths.stem(item.audioPath)) null else ed.planRename(index, name) }
    Overlay(close, 520) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(S.rename(), primary = true, enabled = plan != null && plan.problems.isEmpty()) { close(); ed.renameFile(index, name) }
        }) {
            Text(renameTitle(), color = c.text, fontSize = 17.sp)
            Text(renameAbout(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Field(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus))
            if (plan != null) {
                if (plan.problems.isNotEmpty()) {
                    Text(nameTaken.format(plan.problems.joinToString(", ") { p ->
                        when { p == "name" -> badName(); p.startsWith("oto.ini") -> p; else -> "$p — ${exists()}" }
                    }), color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text(renameWill.format(plan.files.size, plan.otoEntries, plan.csvRows), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    val others = plan.files.drop(1).map { Paths.name(it.first) }
                    if (others.isNotEmpty()) Text(alsoT.format(others.joinToString(", ")), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
fun TrashFileDialog(ed: EditorState, index: Int, close: () -> Unit) {
    val c = T.c
    val item = ed.items.getOrNull(index) ?: return close()
    val others = remember(item) { ed.workspace.companions(item).map { Paths.name(it) } }
    Overlay(close, 520) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(trashFileT().trimEnd('…'), primary = true) { close(); ed.trashFile(index) }
        }) {
            Text(trashTitle(), color = c.text, fontSize = 17.sp)
            Text(trashAbout.format(Paths.name(item.audioPath)), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (others.isNotEmpty()) Text(alsoT.format(others.joinToString(", ")), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
