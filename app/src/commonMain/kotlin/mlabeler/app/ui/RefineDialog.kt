package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.QuickLabel
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import mlabeler.app.state.refineBoundaries
import mlabeler.app.state.refineFiles

private val titleT = L("Refine boundaries", "Уточнение границ")
private val aboutT = L(
    "The model that comes with mLabeler (MCFL) finds again where your phonemes are in the sound; the phonemes and their order stay. It works after any labelling (an aligner's, a recogniser's or your own), right here, without the toolkit.",
    "Встроенная модель mLabeler (MCFL) заново находит по звуку, где стоят ваши фонемы; сами фонемы и их порядок не меняются. Работает после любой разметки — выравнивателя, распознавания или ручной — прямо здесь, без тулкита.",
)
private val whatT = L("What to refine", "Что уточнять")
private val selectedT = L("Selected part", "Выделенный фрагмент")
private val wholeT = L("Whole recording", "Всю запись")
private val allT = L("All labelled files ({0})", "Все размеченные файлы ({0})")
private val allNoteT = L("Their labels are changed and saved.", "Их разметка изменится и сохранится.")
private val runT = L("Refine", "Уточнить")

internal val markUnsureT = L("Mark the phonemes the model isn't sure of (checks)", "Отмечать фонемы, в которых модель не уверена (проверка)")
internal val markUnsureAboutT = L(
    "The model's confidence in every phoneme is kept (WFL-ASR, MCFL, quick labelling); the checks mark those below the threshold of Settings → Checks, with the percent. A phoneme set by hand, even confirmed with Enter unchanged, loses its mark.",
    "Уверенность модели в каждой фонеме сохраняется (WFL-ASR, MCFL, быстрая разметка); проверка отмечает фонемы ниже порога из Настройки → Проверки, с процентом. Фонема, проставленная вручную (даже подтверждённая Enter без изменений), отметку теряет.",
)

/** The switch: keep the model's confidence and mark the unsure phonemes (off by default). */
@Composable
fun MarkUnsureSwitch(app: AppState, hint: Boolean = true) {
    val c = T.c
    SwitchRow(markUnsureT(), app.settings.toolkit.markUnsure) { v -> app.update { it.copy(toolkit = it.toolkit.copy(markUnsure = v)) } }
    if (hint && app.settings.toolkit.markUnsure) Text(markUnsureAboutT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
}

/** Tools → Refine boundaries: the built-in model places the phonemes of the labels anew (the language as quick labelling). */
@Composable
fun RefineDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showRefine = false; ed.requestFocus() }
    val selected: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    // 0 selected part, 1 whole recording, 2 all labelled files
    var what by remember { mutableStateOf(if (selected != null) 0 else 1) }
    val labelled = remember(ed.items) { ed.items.filter { it.labelPath != null } }
    val lang = QuickLabel.language(app.settings)
    Overlay({ close() }, 600) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(runT(), primary = true, enabled = (what != 2 || labelled.isNotEmpty()) && (what == 2 || ed.doc != null)) {
                when (what) {
                    0 -> selected?.let { ed.refineBoundaries(it.first, it.second, lang) }
                    1 -> ed.refineBoundaries(0.0, ed.duration, lang)
                    else -> {
                        if (ed.dirty) ed.save(quiet = true)
                        ed.refineFiles(labelled, lang)
                    }
                }
                close()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            SectionTitle(whatT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) Chip(selectedT(), what == 0) { what = 0 }
                Chip(wholeT(), what == 1) { what = 1 }
                Chip(allT.format(labelled.size), what == 2) { what = 2 }
            }
            if (what == 2) Text(allNoteT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            SectionTitle(S.language())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((code, name) in QuickLabel.languages()) Chip(name, code == lang) { app.update { it.copy(quick = it.quick.copy(language = code)) } }
            }
        }
    }
}
