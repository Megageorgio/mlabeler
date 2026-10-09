package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.RefineSettings
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import mlabeler.app.state.refineBoundaries
import mlabeler.app.state.refineFiles

private val titleT = L("Refine boundaries", "Уточнение границ")
private val aboutT = L(
    "A refiner model moves the phoneme boundaries to where they are in the sound; the phonemes and their order stay. It works after any labelling: an aligner's, a recogniser's or your own.",
    "Модель-уточнитель сдвигает границы фонем туда, где они на самом деле в звуке; сами фонемы и их порядок не меняются. Работает после любой разметки — выравнивателя, распознавания или ручной.",
)
private val whatT = L("What to refine", "Что уточнять")
private val selectedT = L("Selected part", "Выделенный фрагмент")
private val wholeT = L("Whole recording", "Всю запись")
private val allT = L("All labelled files ({0})", "Все размеченные файлы ({0})")
private val allNoteT = L("Their labels are changed and saved.", "Их разметка изменится и сохранится.")
private val modelT = L("Refiner model", "Модель-уточнитель")
private val modeT = L("How boldly", "Насколько смело")
private val autoT = L("Auto", "Авто")
private val normalT = L("As trained", "Как обучена")
private val safeT = L("Carefully", "Осторожно")
private val modeAboutT = L(
    "As trained: for labels of the model's language and style. Carefully: only confident boundaries and not far, for other languages or another labelling style; it gains less but practically never makes labels worse. Auto: carefully when most phonemes are unknown to the model.",
    "Как обучена — для разметки на языке и в стиле модели. Осторожно — только уверенные границы и недалеко, для других языков или другого стиля разметки: выигрыш меньше, но разметка практически не портится. Авто — осторожно, если большинство фонем модели незнакомы.",
)
private val noModelsT = L("No refiner models in the toolkit", "В тулките нет моделей-уточнителей")
private val runT = L("Refine", "Уточнить")
private val afterT = L("Refine the boundaries afterwards (a refiner model)", "Уточнить границы после этого (модель-уточнитель)")
private val firstUseT = L("downloads on first use", "загружается при первом использовании")

/** The refiner model and how boldly it moves boundaries; [r] changes through [onChange]. */
@Composable
fun RefineOptions(app: AppState, r: RefineSettings, onChange: (RefineSettings) -> Unit) {
    val c = T.c
    val (langs, error) = rememberToolkitModels(app, "refine")
    SectionTitle(modelT())
    when {
        error != null -> Text(error, color = c.danger, fontSize = 13.sp)
        langs == null -> Text("…", color = c.muted, fontSize = 13.sp)
        else -> {
            // models of every language in one list: a refiner is chosen by its phoneme set, not by a language
            val models = langs.flatMap { it.models }.distinctBy { it.id }
            if (models.isEmpty()) Text(noModelsT(), color = c.muted, fontSize = 13.sp)
            Column(Modifier.heightIn(max = 160.dp).scrollWithHint()) {
                for (m in models) {
                    val sel = m.id == r.model
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) c.accent.copy(alpha = 0.14f) else c.panel).clickable { onChange(r.copy(model = m.id)) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (!m.installed) Text(firstUseT(), color = c.muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    SectionTitle(modeT())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(autoT(), r.mode == "auto") { onChange(r.copy(mode = "auto")) }
        Chip(normalT(), r.mode == "normal") { onChange(r.copy(mode = "normal")) }
        Chip(safeT(), r.mode == "safe") { onChange(r.copy(mode = "safe")) }
    }
    Text(modeAboutT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

/** The switch in the autolabel dialog: refine after labelling (kept in the settings, off by default). */
@Composable
fun RefineAfterSwitch(app: AppState) {
    val c = T.c
    val r = app.settings.toolkit.refine
    fun set(v: RefineSettings) = app.update { it.copy(toolkit = it.toolkit.copy(refine = v)) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { set(r.copy(enabled = !r.enabled)) }, verticalAlignment = Alignment.CenterVertically) {
        Text(afterT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Toggle(r.enabled, { v -> set(r.copy(enabled = v)) })
    }
    if (r.enabled) Column(Modifier.padding(top = 4.dp).fillMaxWidth().background(c.panelAlt).padding(10.dp)) { RefineOptions(app, r) { set(it) } }
}

@Composable
fun RefineDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showRefine = false; ed.requestFocus() }
    val selected: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    // 0 selected part, 1 whole recording, 2 all labelled files
    var what by remember { mutableStateOf(if (selected != null) 0 else 1) }
    var r by remember { mutableStateOf(app.settings.toolkit.refine) }
    val labelled = remember(ed.items) { ed.items.filter { it.labelPath != null } }
    Overlay({ close() }, 600) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(runT(), primary = true, enabled = r.model.isNotBlank() && (what != 2 || labelled.isNotEmpty()) && (what == 2 || ed.doc != null)) {
                app.update { it.copy(toolkit = it.toolkit.copy(refine = it.toolkit.refine.copy(model = r.model, mode = r.mode))) }
                when (what) {
                    0 -> selected?.let { ed.refineBoundaries(it.first, it.second, r) }
                    1 -> ed.refineBoundaries(0.0, ed.duration, r)
                    else -> {
                        if (ed.dirty) ed.save(quiet = true)
                        ed.refineFiles(labelled, r)
                    }
                }
                close()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 10.dp)) { ToolkitStatus(app) }
            SectionTitle(whatT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) Chip(selectedT(), what == 0) { what = 0 }
                Chip(wholeT(), what == 1) { what = 1 }
                Chip(allT.format(labelled.size), what == 2) { what = 2 }
            }
            if (what == 2) Text(allNoteT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            RefineOptions(app, r) { r = it }
        }
    }
}
