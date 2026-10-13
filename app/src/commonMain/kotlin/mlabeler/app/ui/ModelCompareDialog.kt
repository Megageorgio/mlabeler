package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.LanguageNames
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.ModelComparison
import mlabeler.app.state.ModelComparison.Candidate
import mlabeler.app.state.QuickLabel
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import kotlin.math.roundToInt

private val titleT = L("Compare autolabel models", "Сравнение моделей авторазметки")
private val aboutT = L(
    "Every ticked model labels the audio (without text, or placing the phonemes of your labels); the phonemes of your labels are the truth it is measured against. Aligners (SOFA, HubertFA, TIFA) without phonemes place Whisper's words spelt by their G2P; a file that fails is skipped. Nothing in the labels changes.",
    "Каждая отмеченная модель размечает звук (без текста или расставляя фонемы вашей разметки); фонемы вашей разметки — эталон, с которым её сравнивают. Выравниватели (SOFA, HubertFA, TIFA) без фонем расставляют слова Whisper, переведённые в фонемы их G2P; файл с ошибкой пропускается. В разметке ничего не меняется.",
)
private val whatT = L("What", "Что сравнивать")
private val selectedT = L("Selected part", "Выделенное")
private val fileT = L("This file", "Этот файл")
private val folderT = L("Labelled files of the folder ({0})", "Размеченные файлы папки ({0})")
private val modelsT = L("Models", "Модели")
private val allT = L("All", "Все")
private val noneT = L("None", "Ни одной")
private val noToolkitT = L("The models are those of the toolkit: start it to compare them", "Сравниваются модели тулкита: запустите его")
private val runT = L("Compare", "Сравнить")
private val loadingT = L("Asking the toolkit for models…", "Запрос моделей у тулкита…")
private val stopT = L("Stop", "Остановить")
private val resultT = L("Results", "Результаты")
private val colModel = L("Model", "Модель")
private val colPer = L("PER", "PER")
private val colSdi = L("sub / del / ins", "зам / проп / вст")
private val colMean = L("bound., ms", "границы, мс")
private val colMedian = L("median", "медиана")
private val col20 = L("≤ 20 ms", "≤ 20 мс")
private val col50 = L("≤ 50 ms", "≤ 50 мс")
private val colSpeed = L("speed", "скорость")
private val showT = L("Show", "Показать")
private val legendT = L(
    "PER: share of phonemes the model got wrong (substituted, missed or added), pauses (SP, AP…) left out, labels compared as they are. Boundaries: how far the starts and ends of the phonemes both have are, mean and median. Speed: seconds of audio per second of work. \"Show\" puts the model's phonemes on this file under your labels.",
    "PER — доля фонем, в которых модель ошиблась (заменила, пропустила или добавила); паузы (SP, AP…) не считаются, метки сравниваются как есть. Границы — насколько расходятся начала и концы общих фонем, среднее и медиана. Скорость — секунд звука на секунду работы. «Показать» выводит фонемы модели на этом файле под вашей разметкой.",
)
private val failedT = L("failed on {0} files", "ошибки на {0} файлах")
private val phonemesT = L("Phonemes", "Фонемы")
private val withoutT = L("Without phonemes", "Без фонем")
private val withT = L("With the phonemes of the labels", "С фонемами разметки")
private val bothT = L("Both", "Оба варианта")
private val withAboutT = L(
    "With phonemes a model only places the phonemes of your labels: the boundaries are what is compared.",
    "С фонемами модель только расставляет фонемы вашей разметки: сравниваются границы.",
)
private val withSuffix = L("with phonemes", "с фонемами")
private val withoutSuffix = L("without phonemes", "без фонем")

@Composable
fun ModelCompareDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val mc = ed.modelComparison
    fun close() { app.showModelCompare = false; ed.requestFocus() }
    val selected: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    val labelled = remember(ed.items) { ed.items.count { it.labelPath != null } }
    var scope by remember { mutableStateOf(if (selected != null) ModelComparison.Scope.Selection else ModelComparison.Scope.File) }
    val (segmentLangs, error) = rememberToolkitModels(app, "segment")
    val (alignLangs, alignError) = rememberToolkitModels(app, "align")
    // recognisers and aligners together (an aligner gets Whisper's words or the phonemes of the labels)
    val toolkitLangs = remember(segmentLangs, alignLangs) {
        if (segmentLangs == null && alignLangs == null) null else segmentLangs.orEmpty() + alignLangs.orEmpty()
    }
    val codes = remember(toolkitLangs) {
        toolkitLangs.orEmpty().map { it.code }.filter { it != "*" }.distinct()
            .sortedBy { LanguageNames.of(it).lowercase() }
    }
    LaunchedEffect(codes) {
        if (mc.language.isEmpty() || mc.language !in codes) mc.language = QuickLabel.language(app.settings).takeIf { it in codes } ?: codes.firstOrNull().orEmpty()
    }
    val lang = mc.language
    val candidates = remember(toolkitLangs, lang) {
        buildList {
            val g = toolkitLangs.orEmpty().filter { it.code == lang || it.code == "*" }
            // recognisers first, then aligners
            for (m in g.flatMap { it.models }.distinctBy { it.id }.sortedBy { it.engine in ModelComparison.ALIGNERS }) add(Candidate(m.id, m.name, m.engine, m.author))
        }
    }
    val picked = candidates.filter { it.id in mc.chosen }.flatMap { m ->
        // the runs of each model: without phonemes, with them, or both
        val without = m.copy(name = if (mc.phonemeMode == 0) m.name else m.name + " · " + withoutSuffix())
        val with = m.copy(name = m.name + " · " + withSuffix(), withPhonemes = true)
        when (mc.phonemeMode) {
            0 -> listOf(m)
            1 -> listOf(with)
            else -> listOf(without, with)
        }
    }

    Overlay({ close() }, 860) {
        DialogContent(footer = {
            Btn(S.close()) { close() }
            if (mc.running) Btn(stopT()) { mc.stop() }
            else Btn(runT(), primary = true, enabled = picked.isNotEmpty() && (scope == ModelComparison.Scope.Folder || ed.doc != null)) {
                if (ed.dirty && scope == ModelComparison.Scope.Folder) ed.save(quiet = true)
                mc.start(lang, picked, scope, selected)
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 10.dp)) { ToolkitStatus(app) }
            SectionTitle(whatT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) Chip(selectedT(), scope == ModelComparison.Scope.Selection) { scope = ModelComparison.Scope.Selection }
                Chip(fileT(), scope == ModelComparison.Scope.File) { scope = ModelComparison.Scope.File }
                Chip(folderT.format(labelled), scope == ModelComparison.Scope.Folder) { scope = ModelComparison.Scope.Folder }
            }
            SectionTitle(S.language())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (code in codes) Chip(LanguageNames.of(code), code == lang) { mc.language = code }
            }
            SectionTitle(phonemesT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(withoutT(), mc.phonemeMode == 0) { mc.phonemeMode = 0 }
                Chip(withT(), mc.phonemeMode == 1) { mc.phonemeMode = 1 }
                Chip(bothT(), mc.phonemeMode == 2) { mc.phonemeMode = 2 }
            }
            if (mc.phonemeMode != 0) Text(withAboutT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            SectionTitle(modelsT()) {
                Text(allT(), color = c.accent, fontSize = 12.sp, modifier = Modifier.clickable {
                    mc.chosen.removeAll { id -> candidates.any { it.id == id } }; mc.chosen.addAll(candidates.map { it.id })
                }.padding(horizontal = 6.dp))
                Text(noneT(), color = c.accent, fontSize = 12.sp, modifier = Modifier.clickable { mc.chosen.removeAll { id -> candidates.any { it.id == id } } }.padding(horizontal = 6.dp))
            }
            when {
                (error ?: alignError) != null && toolkitLangs == null -> Text((error ?: alignError)!!, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                // still asking: the models come when the toolkit answers; "start it" only when it isn't running
                toolkitLangs == null -> Text(if (app.toolkit.status in setOf(mlabeler.app.toolkit.ToolkitManager.Status.Missing, mlabeler.app.toolkit.ToolkitManager.Status.Off,
                    mlabeler.app.toolkit.ToolkitManager.Status.Failed)) noToolkitT() else loadingT(),
                    color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Column {
                for (m in candidates) {
                    val on = m.id in mc.chosen
                    Row(
                        Modifier.fillMaxWidth().background(if (on) c.accent.copy(alpha = 0.12f) else c.panel)
                            .clickable { if (on) mc.chosen.remove(m.id) else mc.chosen.add(m.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "☑" else "☐", color = if (on) c.accent else c.muted, fontSize = 15.sp, modifier = Modifier.width(24.dp))
                        // the name with its author takes the room; the engine stays at the right edge
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                            if (m.author.isNotBlank()) Text(m.author, color = c.muted.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(m.engine, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            mc.progress?.let { Text(it, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (mc.running) androidx.compose.material3.LinearProgressIndicator(progress = { mc.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = c.accent, trackColor = c.panelAlt)
            if (mc.rows.isNotEmpty()) {
                SectionTitle(resultT())
                val best = mc.rows.filter { it.files > 0 }.minOfOrNull { it.score.per }
                @Composable
                fun cell(text: String, w: Float, color: androidx.compose.ui.graphics.Color = c.text, bold: Boolean = false) =
                    Text(text, color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else null, maxLines = 2, textAlign = TextAlign.End,
                        modifier = Modifier.width(w.dp).padding(horizontal = 3.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(colModel(), color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    for ((t, w) in listOf(colPer() to 56f, colSdi() to 100f, colMean() to 70f, colMedian() to 58f, col20() to 56f, col50() to 56f, colSpeed() to 60f, "" to 74f)) cell(t, w, c.muted)
                }
                for (r in mc.rows) {
                    val s = r.score
                    val top = r.files > 0 && s.per == best
                    Row(Modifier.fillMaxWidth().background(if (top) c.accent.copy(alpha = 0.10f) else c.panel).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(r.model.name, color = c.text, fontSize = 12.sp, fontWeight = if (top) FontWeight.SemiBold else null)
                            if (r.failed.isNotEmpty()) Text(failedT.format(r.failed.size) + ": " + r.failed.first(), color = c.danger, fontSize = 10.sp, maxLines = 2)
                        }
                        if (r.files == 0) {
                            for (w in listOf(56f, 100f, 70f, 58f, 56f, 56f, 60f, 74f)) cell("—", w, c.muted)
                        } else {
                            cell("${(s.per * 1000).roundToInt() / 10.0}%", 56f, if (top) c.accent else c.text, top)
                            cell("${s.substitutions} / ${s.deletions} / ${s.insertions}", 100f)
                            cell("${s.meanMs.roundToInt()}", 70f)
                            cell("${s.medianMs.roundToInt()}", 58f)
                            cell("${(s.within(20.0) * 100).roundToInt()}%", 56f)
                            cell("${(s.within(50.0) * 100).roundToInt()}%", 56f)
                            cell("${r.speed.roundToInt()}×", 60f)
                            Row(Modifier.width(74.dp), horizontalArrangement = Arrangement.End) {
                                if (mc.canShow(r.model)) Text(showT(), color = c.accent, fontSize = 12.sp, modifier = Modifier.clickable { mc.show(r.model) }.padding(horizontal = 6.dp))
                            }
                        }
                    }
                }
                Text(legendT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
