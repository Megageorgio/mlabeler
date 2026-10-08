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

private val title = L("Rename in bulk", "Пакетное переименование")
private val modePattern = L("Pattern", "Шаблон")
private val modeExact = L("Replace a name", "Заменить имя")
private val modeSuffix = L("Add to the end", "Добавить в конец")
private val modePrefix = L("Add to the start", "Добавить в начало")
private val find = L("Find (regular expression)", "Найти (регулярное выражение)")
private val replace = L("Replace with ($1, $2 … for groups)", "Заменить на ($1, $2 … — группы)")
private val exactFrom = L("Name (exactly, e.g. ax)", "Имя (целиком, например ax)")
private val exactTo = L("New name", "Новое имя")
private val addText = L("Text to add (e.g. G4)", "Что добавить (например G4)")
private val onlyMatching = L("Only names matching (regular expression, empty = all)", "Только имена, подходящие под (регулярное выражение, пусто — все)")
private val scopeThis = L("This file", "Этот файл")
private val scopeAll = L("The whole folder", "Вся папка")
private val scopeOto = L("Aliases of oto.ini.", "Псевдонимы oto.ini.")
private val scopeLabels = L("Labels of the tier \"{0}\".", "Метки слоя «{0}».")
private val allNote = L("Files of the folder that aren't open are written at once (the previous version goes to .mlabeler/backup); the open file can be undone.",
    "Неоткрытые файлы папки записываются сразу (прежняя версия уходит в .mlabeler/backup); в открытом файле можно отменить.")
private val preview = L("{0} will change", "изменится: {0}")
private val badRegex = L("Pattern error: {0}", "Ошибка в шаблоне: {0}")
private val apply = L("Rename", "Переименовать")
private val renamed = L("Renamed: {0}", "Переименовано: {0}")
private val renamedFiles = L("Renamed: {0} in {1} files", "Переименовано: {0} в файлах: {1}")

@Composable
fun BatchRenameDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    var mode by remember { mutableStateOf(0) }
    var everywhere by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val oto = ed.mode == Mode.Oto
    val tierName = (ed.doc?.tiers?.getOrNull(ed.activeTier) as? IntervalTier)?.name ?: "phones"
    // the renaming as a function of a name; null while the pattern is broken or empty
    val built: Result<((String) -> String)?> = remember(mode, from, to, filter) {
        runCatching {
            val only = if (filter.isBlank()) null else Regex(filter)
            fun guard(f: (String) -> String): (String) -> String = { s -> if (s.isEmpty() || only != null && !only.containsMatchIn(s)) s else f(s) }
            when (mode) {
                0 -> if (from.isEmpty()) null else Regex(from).let { r -> { s: String -> r.replace(s, to) } }
                1 -> if (from.isEmpty()) null else guard { s -> if (s == from) to else s }
                2 -> if (to.isEmpty()) null else guard { s -> s + to }
                else -> if (to.isEmpty()) null else guard { s -> to + s }
            }
        }
    }
    val fn = built.getOrNull()
    val changes: List<Pair<String, String>> = remember(fn, everywhere, oto, tierName, ed.docVersion, ed.oto.version) {
        val f = fn ?: return@remember emptyList()
        when {
            oto && everywhere -> ed.oto.previewEverywhere(f)
            oto -> ed.oto.entries.mapNotNull { e -> f(e.alias).takeIf { it != e.alias }?.let { e.alias to it } }
            everywhere -> ed.previewEverywhere(tierName, f).map { (file, a, b) -> "$file: $a" to b }
            else -> ((ed.doc?.tiers?.getOrNull(ed.activeTier) as? IntervalTier)?.texts ?: emptyList()).mapNotNull { x -> f(x).takeIf { it != x }?.let { x to it } }
        }
    }
    fun close() { app.showBatchRename = false; ed.requestFocus() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Overlay({ close() }, 560) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title(), color = c.text, fontSize = 17.sp)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, t) in listOf(modePattern, modeExact, modeSuffix, modePrefix).withIndex()) Chip(t(), mode == k) { mode = k }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(scopeThis(), !everywhere) { everywhere = false }
                Chip(scopeAll(), everywhere) { everywhere = true }
            }
            Text(if (oto) scopeOto() else scopeLabels.format(tierName), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            val ff = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
            when (mode) {
                0 -> {
                    Text(find(), color = c.muted, fontSize = 12.sp)
                    Field(from, { from = it }, ff.focusRequester(focus))
                    Text(replace(), color = c.muted, fontSize = 12.sp)
                    Field(to, { to = it }, ff)
                }
                1 -> {
                    Text(exactFrom(), color = c.muted, fontSize = 12.sp)
                    Field(from, { from = it }, ff.focusRequester(focus))
                    Text(exactTo(), color = c.muted, fontSize = 12.sp)
                    Field(to, { to = it }, ff)
                }
                else -> {
                    Text(addText(), color = c.muted, fontSize = 12.sp)
                    Field(to, { to = it }, ff.focusRequester(focus))
                    Text(onlyMatching(), color = c.muted, fontSize = 12.sp)
                    Field(filter, { filter = it }, ff)
                }
            }
            val err = built.exceptionOrNull()
            Text(if (err != null) badRegex.format(err.message ?: "") else preview.format(changes.size), color = if (err != null) c.danger else c.muted, fontSize = 12.sp)
            // the first changes, to see what will happen
            for ((a, b) in changes.take(8)) Text("$a → $b", color = c.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            if (changes.size > 8) Text("…", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            if (everywhere && !oto) Text(allNote(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(mlabeler.app.i18n.S.cancel()) { close() }
                Btn(apply(), primary = true, enabled = fn != null && changes.isNotEmpty()) {
                    val f = fn ?: return@Btn
                    when {
                        oto && everywhere -> app.message(renamed.format(ed.oto.renameEverywhere(f)))
                        oto -> app.message(renamed.format(ed.oto.renameAll(ed.oto.entries.indices.toList(), f)))
                        everywhere -> ed.renameEverywhere(tierName, f).let { (files, n) -> app.message(renamedFiles.format(n, files)) }
                        else -> {
                            val k = ed.activeTier
                            val t = ed.doc?.tiers?.getOrNull(k) as? IntervalTier
                            if (t != null) ed.updateDoc { d -> Edits.setTexts(d, (0 until t.size).map { IntervalRef(k, it) }, f) }
                            app.message(renamed.format(changes.size))
                        }
                    }
                    close()
                }
            }
        }
    }
}
