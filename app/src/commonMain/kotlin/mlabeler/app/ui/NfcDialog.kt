package mlabeler.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T

private val titleT = L("Normalise file names", "Нормализация имён файлов")
private val aboutT = L(
    "Files copied from a Mac often have names in decomposed form: \"が\" is stored as \"か\" and a separate mark. They look the same but UTAU, oto.ini and other programs don't find them. The names and the text of oto.ini become composed (Unicode NFC); the old oto.ini goes to .mlabeler/backup.",
    "У файлов, скопированных с Mac, имена часто в разложенной форме: «が» хранится как «か» и отдельный знак. Выглядят они так же, но UTAU, oto.ini и другие программы их не находят. Имена и текст oto.ini приводятся к составной форме (Unicode NFC); старый oto.ini уходит в .mlabeler/backup.",
)
private val namesT = L("Names to fix: {0}", "Имён для исправления: {0}")
private val otosT = L("oto.ini to fix: {0}", "oto.ini для исправления: {0}")
private val nothingT = L("Every name is already composed", "Все имена уже в составной форме")
private val runT = L("Normalise", "Нормализовать")
private val doneT = L("Fixed: {0}", "Исправлено: {0}")
private val failedT = L("Could not rename: {0}", "Не удалось переименовать: {0}")

@Composable
fun NfcDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showNfc = false; ed.requestFocus() }
    val (names, otos) = remember { ed.workspace.decomposedNames() }
    Overlay({ close() }, 560) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(runT(), primary = true, enabled = names.isNotEmpty() || otos.isNotEmpty()) {
                ed.finishEditing()
                if (ed.labelsDirty) ed.saveLabels(quiet = true)
                if (ed.oto.dirty) ed.oto.save(quiet = true)
                val (n, failed) = ed.workspace.normalizeNames()
                ed.oto.invalidate()
                ed.rescan()
                app.message(doneT.format(n) + if (failed.isEmpty()) "" else "\n" + failedT.format(failed.joinToString(", ")), error = failed.isNotEmpty())
                close()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (names.isEmpty() && otos.isEmpty()) Text(nothingT(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            else {
                Text(namesT.format(names.size) + " · " + otosT.format(otos.size), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Column(Modifier.padding(top = 6.dp).heightIn(max = 240.dp).scrollWithHint()) {
                    for (p in otos + names) Text(ed.workspace.relative(p), color = c.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
