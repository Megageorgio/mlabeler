package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import mlabeler.app.toolkit.ToolkitLanguage
import mlabeler.core.io.Paths
import mlabeler.core.oto.AutoOtoSettings
import mlabeler.core.oto.RecStyle
import mlabeler.core.oto.Syllables
import mlabeler.core.oto.AliasScript
import mlabeler.core.oto.OtoEntryKind
import mlabeler.core.oto.OtoRule
import kotlin.math.roundToInt

private val title = L("Automatic oto", "Автоматическое oto")
private val hint = L("Entries are made from the file names (kana, romaji or Cyrillic syllables) and the recordings.",
    "Записи строятся по именам файлов (кана, ромадзи или кириллица) и по самим записям.")
private val which = L("Which files", "Какие файлы")
private val thisOne = L("This file", "Этот файл")
private val withoutEntries = L("Files without entries", "Файлы без записей")
private val everyFile = L("Every file in the folder", "Все файлы папки")
private val styleT = L("Recording style", "Тип записи")
private val auto = L("Detect", "Определить")
private val bpmT = L("Tempo of the recording, BPM (0 = find from the audio)", "Темп записи, BPM (0 — искать по звуку)")
private val leftT = L("Offset before the overlap, ms", "Offset до overlap, мс")
private val fixedT = L("Consonant part into the vowel, ms", "Consonant заходит в гласную, мс")
private val existing = L("Existing entries of these files", "Существующие записи этих файлов")
private val keep = L("Keep, add missing aliases", "Оставить, добавить недостающие")
private val replaceT = L("Replace", "Заменить")
private val methodT = L("How to find syllables", "Как искать слоги")
private val builtIn = L("Built in (loudness and voicing) — recommended", "Встроенный (громкость и голос) — рекомендуется")
private val aligner = L("Aligner model from the toolkit (slower)", "Модель выравнивания из тулкита (медленнее)")
private val methodAbout = L(
    "The built-in way is usually better and takes seconds. Aligner models learned on sung phrases, not on single syllables with silence around them: they place consonant starts less exactly, need the syllables turned into their own phonemes (which fails for some banks) and load a large model first.",
    "Встроенный способ обычно точнее и работает за секунды. Модели выравнивания обучены на пропетых фразах, а не на отдельных слогах с тишиной вокруг: они менее точно ставят начало согласной, требуют перевода слогов в фонемы (для некоторых банков это невозможно) и сначала загружают большую модель.")
private val preview = L("This file: {0}", "Этот файл: {0}")
private val start = L("Make entries", "Сделать записи")

@Composable
fun AutoOtoDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    fun close() { app.showAutoOto = false; ed.requestFocus() }
    var scope by remember { mutableStateOf(0) }
    var options by remember { mutableStateOf(app.settings.autoOto) }
    var style by remember { mutableStateOf(options.style) }
    var bpm by remember { mutableStateOf(options.bpm.toString()) }
    var left by remember { mutableStateOf(options.leftMarginMs.toFloat()) }
    var fixed by remember { mutableStateOf(options.fixedMs.toFloat()) }
    var profileName by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var ruleKind by remember { mutableStateOf(OtoEntryKind.Initial) }
    fun settings() = options.copy(style = style, bpm = bpm.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
        leftMarginMs = left.toDouble(), fixedMs = fixed.toDouble())
    fun load(s: AutoOtoSettings) { options = s; style = s.style; bpm = s.bpm.toString(); left = s.leftMarginMs.toFloat(); fixed = s.fixedMs.toFloat() }
    var replace by remember { mutableStateOf(true) }
    var useAligner by remember { mutableStateOf(false) }
    var langs by remember { mutableStateOf<List<ToolkitLanguage>?>(null) }
    var model by remember { mutableStateOf(app.settings.toolkit.lastModel) }
    var lang by remember { mutableStateOf(app.settings.toolkit.lastLanguage) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = rememberToolkitModels(app, "align", enabled = useAligner)
    LaunchedEffect(loaded) { langs = loaded.first; error = loaded.second }
    Overlay({ close() }, 620) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(L("Generate preview", "Создать предпросмотр")(), primary = true, enabled = !useAligner || model.isNotEmpty()) {
                val settings = settings()
                app.update { it.copy(autoOto = settings) }
                ed.oto.autoOto(scope, settings, replace, if (useAligner) model else null, lang.takeIf { it.isNotEmpty() && it != "*" })
                close()
            }
        }) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Text(hint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            SectionTitle(L("Profile", "Профиль")())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(L("Original defaults", "Исходные настройки")(), settings() == AutoOtoSettings()) { load(AutoOtoSettings()); profileName = "" }
                for (p in app.settings.otoProfiles) Chip(p.name, settings() == p.settings) { load(p.settings); profileName = p.name }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(profileName, { profileName = it }, Modifier.weight(1f), placeholder = L("Profile name", "Имя профиля")())
                Btn(S.save(), enabled = profileName.isNotBlank()) {
                    val name = profileName.trim(); val s = settings()
                    app.update { it.copy(otoProfiles = it.otoProfiles.filter { p -> p.name != name } + mlabeler.app.state.OtoProfile(name, s)) }
                }
                Btn(L("Delete", "Удалить")(), enabled = app.settings.otoProfiles.any { it.name == profileName.trim() }) {
                    app.update { it.copy(otoProfiles = it.otoProfiles.filter { p -> p.name != profileName.trim() }) }; profileName = ""
                }
            }
            SectionTitle(L("Alias spelling", "Письменность алиасов")())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in AliasScript.entries) Chip(aliasScriptName(a), options.aliases == a) { options = options.copy(aliases = a) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(options.aliasPrefix, { options = options.copy(aliasPrefix = it) }, Modifier.weight(1f), placeholder = L("Alias prefix", "Префикс алиаса")())
                Field(options.aliasSuffix, { options = options.copy(aliasSuffix = it) }, Modifier.weight(1f), placeholder = L("Pitch/colour suffix", "Суффикс высоты/тембра")())
            }
            ed.item?.let { Text(preview.format(Syllables.fromName(Paths.stem(it.audioPath)).joinToString(" ") { s -> s.text }), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            SectionTitle(which())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(thisOne(), scope == 0) { scope = 0 }
                Chip(withoutEntries(), scope == 1) { scope = 1 }
                Chip(everyFile(), scope == 2) { scope = 2 }
            }
            SectionTitle(styleT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (st in RecStyle.entries) Chip(if (st == RecStyle.Auto) auto() else st.name, style == st) { style = st }
            }
            SectionTitle(methodT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(builtIn(), !useAligner) { useAligner = false }
                Chip(aligner(), useAligner) { useAligner = true }
            }
            Text(methodAbout(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (useAligner) {
                Column(Modifier.padding(vertical = 8.dp)) { ToolkitStatus(app) }
                when {
                    error != null -> Text(error ?: "", color = c.danger, fontSize = 12.sp)
                    langs == null -> Text(S.loading(), color = c.muted, fontSize = 12.sp)
                    else -> {
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (l in langs!!) Chip(l.name, l.code == lang) { lang = l.code }
                        }
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in langs!!.firstOrNull { it.code == lang }?.models.orEmpty()) Chip(m.name, m.id == model) { model = m.id }
                        }
                    }
                }
            } else {
                Text(bpmT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Field(bpm, { bpm = it }, Modifier.width(120.dp))
            }
            ValueSlider(leftT(), left, 10f..300f, S.msUnit()) { left = it }
            ValueSlider(fixedT(), fixed, 10f..200f, S.msUnit()) { fixed = it }
            Chip(L("Advanced rules", "Расширенные правила")(), advanced) { advanced = !advanced }
            if (advanced) {
                ValueSlider(L("Overlap before consonant, ms", "Overlap до согласной, мс")(), options.overlapBeforeMs.toFloat(), 0f..200f, S.msUnit()) { options = options.copy(overlapBeforeMs = it.toDouble()) }
                ValueSlider(L("Vowel offset margin, ms", "Отступ offset для гласной, мс")(), options.vowelLeftMs.toFloat(), 0f..300f, S.msUnit()) { options = options.copy(vowelLeftMs = it.toDouble()) }
                ValueSlider(L("Vowel overlap, ms", "Overlap гласной, мс")(), options.vowelOverlapMs.toFloat(), 0f..200f, S.msUnit()) { options = options.copy(vowelOverlapMs = it.toDouble()) }
                ValueSlider(L("End margin, ms", "Отступ конца, мс")(), options.endMarginMs.toFloat(), 0f..300f, S.msUnit()) { options = options.copy(endMarginMs = it.toDouble()) }
                Field(options.headPrefix, { options = options.copy(headPrefix = it) }, Modifier.fillMaxWidth(), placeholder = L("Initial alias prefix", "Префикс начальной записи")())
                Field(options.endAlias, { options = options.copy(endAlias = it) }, Modifier.fillMaxWidth(), placeholder = L("Ending alias (blank: none)", "Конечный алиас (пусто: не создавать)")())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Use initial prefix on CV entries", "Начальный префикс у CV-записей")(), modifier = Modifier.weight(1f), color = c.text)
                    Toggle(options.cvWithHead, { options = options.copy(cvWithHead = it) })
                }
                Text(L("Corrections to the original rules, ms. Positive moves the marker later; zero preserves the original rule.",
                    "Поправки к исходным правилам, мс. Плюс сдвигает маркер позже; ноль сохраняет исходное правило.")(), color = c.muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in OtoEntryKind.entries) Chip(entryKindName(k), k == ruleKind) { ruleKind = k }
                }
                val rule = options.rules.lastOrNull { it.kind == ruleKind } ?: OtoRule(ruleKind)
                fun change(r: OtoRule) { options = options.copy(rules = options.rules.filter { it.kind != r.kind } + r) }
                ValueSlider("Offset", rule.offset.toFloat(), -200f..200f, S.msUnit()) { change(rule.copy(offset = it.toDouble())) }
                ValueSlider("Overlap", rule.overlap.toFloat(), -200f..200f, S.msUnit()) { change(rule.copy(overlap = it.toDouble())) }
                ValueSlider("Preutterance", rule.preutterance.toFloat(), -200f..200f, S.msUnit()) { change(rule.copy(preutterance = it.toDouble())) }
                ValueSlider("Consonant", rule.consonant.toFloat(), -200f..200f, S.msUnit()) { change(rule.copy(consonant = it.toDouble())) }
                ValueSlider("Cutoff", rule.cutoff.toFloat(), -200f..200f, S.msUnit()) { change(rule.copy(cutoff = it.toDouble())) }
            }
            SectionTitle(existing())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(replaceT(), replace) { replace = true }
                Chip(keep(), !replace) { replace = false }
            }
        }
    }
}

internal fun aliasScriptName(a: AliasScript): String = when (a) {
    AliasScript.Auto -> L("Automatic (as before)", "Автоматически (как раньше)")()
    AliasScript.Kana -> L("Kana", "Кана")()
    AliasScript.Romaji -> L("Romaji", "Ромадзи")()
    AliasScript.Both -> L("Both", "Оба варианта")()
}

internal fun entryKindName(k: OtoEntryKind): String = when (k) {
    OtoEntryKind.Initial -> L("Initial", "Начальная")()
    OtoEntryKind.Ending -> L("Ending", "Конечная")()
    else -> k.name
}
