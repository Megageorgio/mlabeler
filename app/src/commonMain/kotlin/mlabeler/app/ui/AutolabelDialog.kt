package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier

private val title = L("Autolabel", "Авторазметка")
private val partLine = L("{0} – {1} ({2})", "{0} – {1} ({2})")
private val whatPart = L("What to label", "Что размечать")
private val selectedPart = L("Selected part", "Выделенный фрагмент")
private val wholeFile = L("Whole recording", "Всю запись")
private val howT = L("How", "Как")
private val byText = L("By lyrics or phonemes", "По тексту или фонемам")
private val byText2 = L("aligner: SOFA, HubertFA", "выравниватель: SOFA, HubertFA")
private val recognize = L("Recognise phonemes", "Распознать фонемы")
private val recognize2 = L("no lyrics needed: WFL", "без текста: WFL")
private val textTitle = L("What is sung there", "Что там поётся")
private val textHint = L("Leave empty to have the words recognised first", "Оставьте пустым — слова сначала распознаются")
private val asPhonemes = L("These are phonemes", "Это фонемы")
private val asText = L("These are words", "Это слова")
private val replaceHere = L("Put into the labels", "Записать в разметку")
private val forCompare = L("Show next to them for comparison", "Показать рядом для сравнения")
private val run = L("Start", "Запустить")
private val loadingModels = L("Asking the toolkit for models…", "Запрашиваю модели у тулкита…")
private val notInstalled = L("downloads on first use", "скачается при первом запуске")
private val noModels = L("No models for this", "Для этого нет моделей")

private val ownModelLink = L("Add your own model…", "Добавить свою модель…")

@Composable
fun AutolabelDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val settings = app.settings.toolkit
    fun close() { app.showAutolabel = false; ed.requestFocus() }
    val selected: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    var whole by remember { mutableStateOf(selected == null) }
    val range = if (whole || selected == null) 0.0 to ed.duration else selected
    var recognizeMode by remember { mutableStateOf(false) }
    val task = if (recognizeMode) "segment" else "align"
    val (langs, error) = rememberToolkitModels(app, task)
    var lang by remember(task) { mutableStateOf(settings.lastLanguage) }
    var model by remember(task) { mutableStateOf(if (recognizeMode) settings.lastSegmentModel else settings.lastModel) }
    LaunchedEffect(langs) {
        val l = langs ?: return@LaunchedEffect
        if (l.none { it.code == lang }) lang = l.firstOrNull { g -> g.models.any { it.id == model } }?.code ?: l.firstOrNull()?.code ?: ""
    }
    LaunchedEffect(langs, lang) {
        val models = langs?.firstOrNull { it.code == lang }?.models ?: return@LaunchedEffect
        if (models.none { it.id == model }) model = (models.firstOrNull { it.installed } ?: models.firstOrNull())?.id ?: model
    }
    val doc = ed.doc
    val wordTier = doc?.let { d -> d.wordTierIndex().takeIf { it >= 0 }?.let { d.tiers[it] as IntervalTier } }
    val phoneTier = doc?.let { d -> d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier }
    fun textsIn(t: IntervalTier?, r: Pair<Double, Double>): String {
        if (t == null) return ""
        return (0 until t.size).filter { i -> (t.startOf(i) + t.endOf(i)) / 2 in r.first..r.second }
            .map { t.texts[it] }.filter { it.isNotEmpty() && it !in setOf("SP", "AP", "pau", "sil", "br") }.joinToString(" ")
    }
    var phonemes by remember { mutableStateOf(wordTier == null) }
    var text by remember(whole) { mutableStateOf(if (wordTier != null) textsIn(wordTier, range) else textsIn(phoneTier, range)) }
    // the whole file goes into the labels by default; a part is compared first
    var replace by remember(whole) { mutableStateOf(whole && (doc == null || doc.tiers.all { t -> (t as? IntervalTier)?.texts?.all { it.isEmpty() } ?: true })) }
    Overlay({ close() }, 640) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Column(Modifier.padding(top = 10.dp)) { ToolkitStatus(app) }
            SectionTitle(whatPart())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) Chip(selectedPart(), !whole) { whole = false }
                Chip(wholeFile(), whole) { whole = true }
                Text(partLine.format(formatTime(range.first), formatTime(range.second), formatMs(range.second - range.first)), color = c.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp))
            }
            SectionTitle(howT())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(byText(), byText2(), !recognizeMode, Modifier.weight(1f)) { recognizeMode = false }
                Choice(recognize(), recognize2(), recognizeMode, Modifier.weight(1f)) { recognizeMode = true }
            }
            SectionTitle(S.language() + " · " + L("model", "модель")())
            when {
                error != null -> Text(error, color = c.danger, fontSize = 13.sp)
                langs == null -> Text(if (app.toolkit.status == mlabeler.app.toolkit.ToolkitManager.Status.Ready) loadingModels() else "—", color = c.muted, fontSize = 13.sp)
                langs.isEmpty() -> Text(noModels(), color = c.muted, fontSize = 13.sp)
                else -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in langs.sortedWith(compareBy({ it.code == "*" }, { mlabeler.app.i18n.LanguageNames.of(it.code, it.name) }))) Chip(mlabeler.app.i18n.LanguageNames.of(l.code, l.name), l.code == lang) { lang = l.code }
                    }
                    val models = langs.firstOrNull { it.code == lang }?.models.orEmpty()
                    Column(Modifier.padding(top = 6.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        for (m in models) {
                            val sel = m.id == model
                            Row(
                                Modifier.fillMaxWidth().background(if (sel) c.accent.copy(alpha = 0.14f) else c.panel).clickable { model = m.id }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(if (m.installed) m.engine else m.engine + " · " + notInstalled(), color = c.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Text(ownModelLink(), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                .clickable { app.settingsPage = "Toolkit"; app.showAutolabel = false; app.showSettings = true })
            if (!recognizeMode) {
                SectionTitle(textTitle())
                Field(text, { text = it }, Modifier.fillMaxWidth(), placeholder = textHint())
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(asText(), !phonemes) { phonemes = false }
                    Chip(asPhonemes(), phonemes) { phonemes = true }
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(forCompare(), !replace) { replace = false }
                Chip(replaceHere(), replace) { replace = true }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { close() }
                val modelOk = langs?.any { g -> g.models.any { it.id == model } } == true
                Btn(run(), primary = true, enabled = modelOk && error == null) {
                    app.update {
                        it.copy(toolkit = if (recognizeMode) it.toolkit.copy(lastSegmentModel = model, lastLanguage = lang)
                        else it.toolkit.copy(lastModel = model, lastLanguage = lang))
                    }
                    ed.autolabel(range.first, range.second, model, lang.takeIf { it.isNotEmpty() && it != "*" }, text,
                        phonemes && text.isNotBlank(), replace, recognize = recognizeMode)
                    close()
                }
            }
        }
    }
}

/** Big two-line option card. */
@Composable
fun Choice(title: String, sub: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = T.c
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(c.radius)
    Column(
        modifier.clip(shape).background(if (selected) c.accent.copy(alpha = 0.16f) else c.panelAlt)
            .border(if (selected) 2.dp else c.borderWidth, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(title, color = c.text, fontSize = 14.sp)
        Text(sub, color = c.muted, fontSize = 11.sp)
    }
}
