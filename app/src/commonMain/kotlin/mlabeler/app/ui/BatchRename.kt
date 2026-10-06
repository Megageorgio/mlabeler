package mlabeler.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.state.Mode
import mlabeler.app.theme.T
import mlabeler.core.edit.Edits
import mlabeler.core.edit.IntervalRef
import mlabeler.core.model.IntervalTier

private val title = L("Rename by pattern", "Переименовать по шаблону")
private val find = L("Find (regular expression)", "Найти (регулярное выражение)")
private val replace = L("Replace with ($1, $2 … for groups)", "Заменить на ($1, $2 … — группы)")
private val scopeOto = L("Applies to all entries of this oto.ini.", "Применяется ко всем записям этого oto.ini.")
private val scopeLabels = L("Applies to the active tier of this file.", "Применяется к активному слою этого файла.")
private val preview = L("{0} will change", "изменится: {0}")
private val badRegex = L("Pattern error: {0}", "Ошибка в шаблоне: {0}")
private val apply = L("Rename", "Переименовать")
private val renamed = L("Renamed: {0}", "Переименовано: {0}")

@Composable
fun BatchRenameDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    val regex = remember(from) { runCatching { if (from.isEmpty()) null else Regex(from) } }
    val fn: ((String) -> String)? = regex.getOrNull()?.let { r -> { s: String -> r.replace(s, to) } }
    val oto = ed.mode == Mode.Oto
    val targets: List<String> = if (oto) {
        ed.oto.entries.map { it.alias }
    } else {
        (ed.doc?.tiers?.getOrNull(ed.activeTier) as? IntervalTier)?.texts ?: emptyList()
    }
    val changes = if (fn == null) 0 else targets.count { fn(it) != it }
    fun close() { app.showBatchRename = false; ed.requestFocus() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Overlay({ close() }, 520) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Text(if (oto) scopeOto() else scopeLabels(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Text(find(), color = c.muted, fontSize = 12.sp)
            Field(from, { from = it }, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).then(androidx.compose.ui.Modifier.focusRequester(focus)))
            Text(replace(), color = c.muted, fontSize = 12.sp)
            Field(to, { to = it }, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp))
            val err = regex.exceptionOrNull()
            Text(if (err != null) badRegex.format(err.message ?: "") else preview.format(changes), color = if (err != null) c.danger else c.muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(mlabeler.app.i18n.S.cancel()) { close() }
                Btn(apply(), primary = true, enabled = fn != null && changes > 0) {
                    val f = fn ?: return@Btn
                    val n = if (oto) {
                        ed.oto.renameAll(ed.oto.entries.indices.toList(), f)
                    } else {
                        val k = ed.activeTier
                        val t = ed.doc?.tiers?.getOrNull(k) as? IntervalTier
                        if (t != null) ed.updateDoc { d -> Edits.setTexts(d, (0 until t.size).map { IntervalRef(k, it) }, f) }
                        changes
                    }
                    app.message(renamed.format(n))
                    close()
                }
            }
        }
    }
}
