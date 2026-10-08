package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import mlabeler.app.state.CleanSettings
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier

private val title = L("Clean the recording", "Чистка записи")
private val promise = L(
    "The file keeps its sample rate, bit depth and channels. Only what is repaired changes; every other sample stays exactly as it was. The first version is kept in the folder's .mlabeler/backup.",
    "Файл сохраняет частоту, разрядность и каналы. Меняется только то, что чинится, — все остальные сэмплы остаются точно такими же. Первая версия хранится в .mlabeler/backup папки.",
)
private val whereT = L("Where", "Где")
private val selectionT = L("Selected part", "Выделенный кусок")
private val wholeT = L("Whole recording", "Вся запись")
private val clicksT = L("Clicks", "Щелчки")
private val clicksHint = L("Finds short pops and clicks and rebuilds just those few samples from the sound around them.",
    "Находит короткие щелчки и восстанавливает только эти несколько сэмплов по звуку вокруг.")
private val sensitivityT = L("Sensitivity", "Чувствительность")
private val maxWidthT = L("Longest click", "Самый длинный щелчок")
private val findT = L("Find", "Найти")
private val repairFoundT = L("Repair found ({0})", "Исправить найденные ({0})")
private val clearT = L("Clear marks", "Убрать отметки")
private val repairT = L("Repair the selected part", "Починить выделенный кусок")
private val repairHint = L("Select a click by hand (up to 100 ms) and rebuild it from both sides.",
    "Выделите щелчок вручную (до 100 мс) и восстановите его по звуку с обеих сторон.")
private val repairBtn = L("Repair", "Починить")
private val muteT = L("Silence", "Заглушить")
private val muteHint = L("Turns the selected part of the recording into silence (Ctrl+Shift+M in the editor: the selection or the selected phoneme). Undo below.",
    "Превращает выделенный кусок записи в тишину (в редакторе Ctrl+Shift+M: выделение или выбранная фонема). Отмена — ниже.")
private val muteBtn = L("Silence the selection", "Заглушить выделенное")
private val noiseT = L("Noise", "Шум")
private val noiseHint = L(
    "Lowers steady noise (hum, hiss) where it's quieter than the voice. Unlike the tools above it changes the whole processed part: listen first.",
    "Снижает ровный шум (гул, шипение) там, где он тише голоса. В отличие от инструментов выше меняет весь обрабатываемый кусок — сначала послушайте.",
)
private val profileT = L("Take the noise from the selected part", "Взять шум из выделенного куска")
private val profileFromT = L("Noise taken from {0} – {1}", "Шум взят из {0} – {1}")
private val noProfile = L("Select a part with noise only (a pause) and take it first.", "Сначала выделите кусок, где только шум (паузу), и возьмите его.")
private val reductionT = L("Lower by", "Снизить на")
private val noiseSensT = L("Sensitivity", "Чувствительность")
private val smoothingT = L("Frequency smoothing", "Сглаживание по частоте")
private val previewT = L("Listen", "Послушать")
private val applyT = L("Apply", "Применить")
private val undoT = L("Undo the last change", "Отменить последнее изменение")
private val originalT = L("Put back the original", "Вернуть исходник")
private val bandsT = L("bands", "полос")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CleanupDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val cl = ed.cleanup
    val cs = app.settings.clean
    fun set(f: (CleanSettings) -> CleanSettings) = app.update { it.copy(clean = f(it.clean)) }
    val dflt = CleanSettings()
    val selected: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    var whole by remember { mutableStateOf(selected == null) }
    val scope = if (whole) null else selected
    fun close() { app.showCleanup = false; ed.requestFocus() }
    Overlay({ close() }, 640) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title(), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.close, S.close()) { close() }
            }
            Text(promise(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

            SectionTitle(whereT())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) Chip(selectionT(), !whole) { whole = false }
                Chip(wholeT(), whole || selected == null) { whole = true }
                scope?.let { Text("${formatTime(it.first)} – ${formatTime(it.second)}", color = c.muted, fontSize = 12.sp) }
            }

            SectionTitle(clicksT())
            Text(clicksHint(), color = c.muted, fontSize = 12.sp)
            ValueSlider(sensitivityT(), cs.clickSensitivity, 1f..10f, decimals = 1, default = dflt.clickSensitivity) { v -> set { it.copy(clickSensitivity = v) } }
            ValueSlider(maxWidthT(), cs.clickMaxMs, 0.5f..20f, S.msUnit(), decimals = 1, default = dflt.clickMaxMs) { v -> set { it.copy(clickMaxMs = v) } }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(findT(), enabled = !cl.busy) { cl.findClicks(scope) }
                Btn(repairFoundT.format(cl.found.size), primary = true, enabled = cl.found.isNotEmpty() && !cl.busy) { cl.repairFound() }
                if (cl.found.isNotEmpty()) Btn(clearT()) { cl.clearFound() }
            }

            SectionTitle(repairT())
            Text(repairHint(), color = c.muted, fontSize = 12.sp)
            Row(Modifier.padding(top = 6.dp)) { Btn(repairBtn(), enabled = selected != null && !cl.busy) { selected?.let { cl.repairRange(it) } } }

            SectionTitle(muteT())
            Text(muteHint(), color = c.muted, fontSize = 12.sp)
            Row(Modifier.padding(top = 6.dp)) { Btn(muteBtn(), enabled = selected != null && !cl.busy) { selected?.let { cl.silence(it) } } }

            SectionTitle(noiseT())
            Text(noiseHint(), color = c.muted, fontSize = 12.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn(profileT(), enabled = selected != null && !cl.busy) { selected?.let { cl.takeNoiseProfile(it) } }
            }
            val pf = cl.profileFrom
            Text(if (pf != null) profileFromT.format(formatTime(pf.first), formatTime(pf.second)) else noProfile(), color = c.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            ValueSlider(reductionT(), cs.noiseReductionDb, 0f..40f, "dB", default = dflt.noiseReductionDb) { v -> set { it.copy(noiseReductionDb = v) } }
            ValueSlider(noiseSensT(), cs.noiseSensitivityDb, 0f..24f, "dB", decimals = 1, default = dflt.noiseSensitivityDb) { v -> set { it.copy(noiseSensitivityDb = v) } }
            ValueSlider(smoothingT(), cs.noiseSmoothing.toFloat(), 0f..12f, bandsT(), default = dflt.noiseSmoothing.toFloat()) { v -> set { it.copy(noiseSmoothing = v.toInt()) } }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(previewT(), enabled = cl.noiseProfile != null && !cl.busy) { cl.previewNoise(scope ?: (ed.viewStart to ed.viewStart + ed.visibleDuration)) }
                Btn(applyT(), primary = true, enabled = cl.noiseProfile != null && !cl.busy) { cl.reduceNoise(scope) }
            }

            Divider()
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(undoT(), enabled = cl.canUndo && !cl.busy) { cl.undo() }
                Btn(originalT(), enabled = cl.hasOriginal() && !cl.busy) { cl.restoreOriginal() }
            }
        }
    }
}
