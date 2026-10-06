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
private val leftT = L("Offset before the consonant, ms", "Offset до согласной, мс")
private val fixedT = L("Consonant part into the vowel, ms", "Consonant заходит в гласную, мс")
private val existing = L("Existing entries of these files", "Существующие записи этих файлов")
private val keep = L("Keep, add missing aliases", "Оставить, добавить недостающие")
private val replaceT = L("Replace", "Заменить")
private val methodT = L("How to find syllables", "Как искать слоги")
private val builtIn = L("Built in (loudness and voicing) — recommended", "Встроенный (громкость и голос) — рекомендуется")
private val aligner = L("Aligner model from the toolkit (slower)", "Модель выравнивания из тулкита (медленнее)")
private val methodAbout = L(
    "The built-in way is usually better and takes seconds. Aligner models learned on sung phrases, not on single syllables with silence around them: they place consonant starts less exactly, need the syllables turned into their own phonemes (which fails for some banks) and load a large model first.",
    "Встроенный способ обычно точнее и работает за секунды. Модели выравнивания обучены на пропетых фразах, а не на отдельных слогах с тишиной вокруг: начало согласной они ставят менее точно, слоги надо переводить в их фонемы (для части банков это не выходит), и сначала грузится большая модель.")
private val preview = L("This file: {0}", "Этот файл: {0}")
private val start = L("Make entries", "Сделать записи")

@Composable
fun AutoOtoDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    fun close() { app.showAutoOto = false; ed.requestFocus() }
    var scope by remember { mutableStateOf(0) }
    var style by remember { mutableStateOf(RecStyle.Auto) }
    var bpm by remember { mutableStateOf("0") }
    var left by remember { mutableStateOf(60f) }
    var fixed by remember { mutableStateOf(50f) }
    var replace by remember { mutableStateOf(true) }
    var useAligner by remember { mutableStateOf(false) }
    var langs by remember { mutableStateOf<List<ToolkitLanguage>?>(null) }
    var model by remember { mutableStateOf(app.settings.toolkit.lastModel) }
    var lang by remember { mutableStateOf(app.settings.toolkit.lastLanguage) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = rememberToolkitModels(app, "align", enabled = useAligner)
    LaunchedEffect(loaded) { langs = loaded.first; error = loaded.second }
    Overlay({ close() }, 620) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Text(hint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
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
            ValueSlider(leftT(), left, 10f..200f, S.msUnit()) { left = it }
            ValueSlider(fixedT(), fixed, 10f..200f, S.msUnit()) { fixed = it }
            SectionTitle(existing())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(replaceT(), replace) { replace = true }
                Chip(keep(), !replace) { replace = false }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Btn(S.cancel()) { close() }
                Btn(start(), primary = true, enabled = !useAligner || model.isNotEmpty()) {
                    val settings = AutoOtoSettings(style = style, bpm = bpm.replace(',', '.').toDoubleOrNull() ?: 0.0, leftMarginMs = left.toDouble(), fixedMs = fixed.toDouble())
                    ed.oto.autoOto(scope, settings, replace, if (useAligner) model else null, lang.takeIf { it.isNotEmpty() && it != "*" })
                    close()
                }
            }
        }
    }
}
