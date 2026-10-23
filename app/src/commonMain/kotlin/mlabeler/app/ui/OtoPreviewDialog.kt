package mlabeler.app.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.oto.OtoPlan

@Composable
fun OtoPreviewDialog(ed: EditorState) {
    val preview = ed.oto.autoPreview ?: return
    val plan = preview.plan
    val c = T.c
    var selected by remember(preview) { mutableStateOf(plan.rows.indices.filter { plan.rows[it].action != OtoPlan.Action.Unchanged && !plan.rows[it].conflict }.toSet()) }
    var query by remember(preview) { mutableStateOf("") }
    fun close() { ed.oto.dismissPreview(); ed.requestFocus() }
    val shown = plan.rows.indices.filter { i -> val e = plan.rows[i].entry; query.isBlank() || e.alias.contains(query, true) || e.sample.contains(query, true) }
    Overlay({ close() }, 820) {
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 660.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
          item { Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Text(L("Review automatic oto", "Предпросмотр автоото")(), color = c.text, fontSize = 17.sp)
            Text(L("Nothing is applied yet. Conflicts are unchecked; selecting one explicitly keeps that alias too.",
                "Изменения ещё не применены. Конфликты сняты: выберите строку явно, если этот алиас тоже нужен.")(), color = c.muted, fontSize = 12.sp)
            Text(plan.rows.groupingBy { it.action }.eachCount().entries.joinToString(" · ") { (a, n) -> "${actionName(a)}: $n" }, color = c.text, fontSize = 12.sp)
            Text(L("Failed files: {0}", "Файлов с ошибками: {0}").format(preview.errors.size), color = if (preview.errors.isEmpty()) c.muted else c.danger, fontSize = 12.sp)
            Field(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = L("Find alias or recording", "Найти алиас или запись")())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(L("Select shown (no conflicts)", "Выбрать показанные без конфликтов")()) {
                    selected = selected + shown.filter { plan.rows[it].action != OtoPlan.Action.Unchanged && !plan.rows[it].conflict }
                }
                Btn(L("Deselect shown", "Снять показанные")()) { selected = selected - shown.toSet() }
                Btn(L("Include shown conflicts", "Включить показанные конфликты")(), enabled = shown.any { plan.rows[it].conflict }) {
                    selected = selected + shown.filter { plan.rows[it].action != OtoPlan.Action.Unchanged && plan.rows[it].conflict }
                }
            }
          } }
            items(preview.errors) { Text(it, color = c.danger, fontSize = 12.sp) }
            items(shown, key = { it }) { i ->
                val row = plan.rows[i]
                val e = row.entry
                val old = row.beforeIndex?.let { plan.before[it] }
                val after = row.after
                Column(Modifier.fillMaxWidth().toggleable(value = i in selected, role = Role.Checkbox, enabled = row.action != OtoPlan.Action.Unchanged) { checked ->
                    selected = if (checked) selected + i else selected - i
                }.padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionCheckbox(i in selected, enabled = row.action != OtoPlan.Action.Unchanged, modifier = Modifier.padding(end = 4.dp))
                        Text("${actionName(row.action)}${if (row.conflict) " · " + L("alias conflict", "конфликт алиаса")() else ""} · ${e.alias} [${e.sample}]",
                            color = if (row.conflict) c.warn else if (row.action == OtoPlan.Action.Unchanged) c.muted else c.text, fontSize = 13.sp)
                    }
                    preview.syllables[e.sample]?.let { Text(it, color = c.muted, fontSize = 11.sp) }
                    fun values(v: mlabeler.core.format.OtoEntry) = listOf(v.offset, v.overlap, v.preutterance, v.consonant, v.cutoff).joinToString(" / ") { "${kotlin.math.round(it * 10) / 10}" }
                    Text("offset / overlap / preutterance / consonant / cutoff: " +
                        (old?.let { values(it) + if (after != null && it != after) " → ${values(after)}" else "" } ?: values(e)), color = c.muted, fontSize = 11.sp)
                }
                Divider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(S.cancel()) { close() }
            Btn(L("Apply selected ({0})", "Применить выбранные ({0})").format(selected.size), primary = true, enabled = selected.isNotEmpty()) {
                if (ed.oto.applyPreview(selected)) ed.requestFocus()
            }
        }
    }
}

private fun actionName(a: OtoPlan.Action) = when (a) {
    OtoPlan.Action.Add -> L("Add", "Добавить")()
    OtoPlan.Action.Replace -> L("Replace", "Заменить")()
    OtoPlan.Action.Remove -> L("Remove", "Удалить")()
    OtoPlan.Action.Unchanged -> L("Keep", "Без изменений")()
}
