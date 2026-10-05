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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import mlabeler.app.toolkit.ToolkitClient
import mlabeler.app.toolkit.ToolkitLanguage
import mlabeler.core.model.IntervalTier

private val title = L("Autolabel a part", "Авторазметка фрагмента")
private val noRange = L("Select a part first: drag over the waveform, or click an interval.",
    "Сначала выделите фрагмент: протяните по волне или выберите интервал.")
private val partLine = L("{0} – {1} ({2})", "{0} – {1} ({2})")
private val textTitle = L("What is sung there", "Что там поётся")
private val asPhonemes = L("These are phonemes", "Это фонемы")
private val asText = L("These are words", "Это слова")
private val replaceHere = L("Replace this part", "Заменить этот фрагмент")
private val forCompare = L("Show next to it for comparison", "Показать рядом для сравнения")
private val run = L("Start", "Запустить")
private val loadingModels = L("Asking the toolkit for models…", "Запрашиваю модели у тулкита…")
private val notInstalled = L("downloads on first use", "скачается при первом запуске")

@Composable
fun AutolabelDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val settings = app.settings.toolkit
    fun close() { app.showAutolabel = false; ed.requestFocus() }
    val range: Pair<Double, Double>? = ed.range ?: ed.selectedInterval()?.let { r ->
        (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.let { it.startOf(r.index) to it.endOf(r.index) }
    }
    var langs by remember { mutableStateOf<List<ToolkitLanguage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lang by remember { mutableStateOf(settings.lastLanguage) }
    var model by remember { mutableStateOf(settings.lastModel) }
    val doc = ed.doc
    val wordTier = doc?.let { d -> d.wordTierIndex().takeIf { it >= 0 }?.let { d.tiers[it] as IntervalTier } }
    val phoneTier = doc?.let { it.tiers[it.phonemeTierIndex()] as? IntervalTier }
    fun textsIn(t: IntervalTier?): String {
        if (t == null || range == null) return ""
        return (0 until t.size).filter { i -> (t.startOf(i) + t.endOf(i)) / 2 in range.first..range.second }
            .map { t.texts[it] }.filter { it.isNotEmpty() && it !in setOf("SP", "AP", "pau", "sil", "br") }.joinToString(" ")
    }
    var phonemes by remember { mutableStateOf(wordTier == null) }
    var text by remember { mutableStateOf(if (wordTier != null) textsIn(wordTier) else textsIn(phoneTier)) }
    var replace by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val l = ToolkitClient(settings.url, settings.token).languages("align")
            langs = l
            if (l.none { it.code == lang }) lang = l.firstOrNull()?.code ?: ""
        } catch (e: Exception) {
            error = e.message
        }
    }
    Overlay({ close() }, 620) {
        Column(Modifier.padding(18.dp)) {
            Text(title(), color = c.text, fontSize = 17.sp)
            if (range == null) {
                Text(noRange(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) { Btn(S.close()) { close() } }
                return@Column
            }
            Text(partLine.format(formatTime(range.first), formatTime(range.second), formatMs(range.second - range.first)), color = c.muted, fontSize = 12.sp)
            SectionTitle(S.language())
            when {
                error != null -> Text(error ?: "", color = c.danger, fontSize = 13.sp)
                langs == null -> Text(loadingModels(), color = c.muted, fontSize = 13.sp)
                else -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in langs!!) Chip(if (l.code == "*") "*" else l.name, l.code == lang) { lang = l.code }
                    }
                    val models = langs!!.firstOrNull { it.code == lang }?.models.orEmpty()
                    SectionTitle(mlabeler.app.i18n.L("Model", "Модель")())
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(models, key = { it.id }) { m ->
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
            SectionTitle(textTitle())
            Field(text, { text = it }, Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(asText(), !phonemes) { phonemes = false }
                Chip(asPhonemes(), phonemes) { phonemes = true }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(forCompare(), !replace) { replace = false }
                Chip(replaceHere(), replace) { replace = true }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { close() }
                Btn(run(), primary = true, enabled = model.isNotEmpty() && error == null) {
                    app.update { it.copy(toolkit = it.toolkit.copy(lastModel = model, lastLanguage = lang)) }
                    ed.autolabel(range.first, range.second, model, lang.takeIf { it.isNotEmpty() && it != "*" }, text, phonemes, replace)
                    close()
                }
            }
        }
    }
}
