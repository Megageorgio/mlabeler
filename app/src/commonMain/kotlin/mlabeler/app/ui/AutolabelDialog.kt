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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier
import mlabeler.app.state.autolabel
import mlabeler.app.state.autolabelFiles
import mlabeler.app.state.transcribePart

private val title = L("Autolabel", "Авторазметка")
private val partLine = L("{0} – {1} ({2})", "{0} – {1} ({2})")
private val whatPart = L("What to label", "Что размечать")
private val selectedPart = L("Selected part", "Выделенный фрагмент")
private val wholeFile = L("Whole recording", "Всю запись")
private val howT = L("How", "Как")
private val byText = L("Place a known text", "Расставить известный текст")
private val byText2 = L("SOFA, HubertFA, TIFA · the text is needed", "SOFA, HubertFA, TIFA · нужен текст")
private val recognize = L("Recognise phonemes", "Распознать фонемы")
private val recognize2 = L("WFL · no text needed", "WFL · текст не нужен")
private val byTextAbout = L("The aligner places what is sung: words (turned into phonemes with the model's dictionary) or phonemes. It doesn't guess the text; without text it can only work after Whisper has recognised the words.",
    "Выравниватель расставляет то, что поётся: слова (разбивая их на фонемы по словарю модели) или фонемы. Текст он не определяет; при отсутствии текста сначала нужно распознать слова через Whisper.")
private val recognizeAbout = L("The model hears the phonemes itself, no text needed. If you know the phonemes, enter them: then it only places them.",
    "Модель распознаёт фонемы самостоятельно, текст не нужен. Если фонемы известны, введите их — тогда она только расставит их по местам.")
private val textTitle = L("What is sung there", "Что там поётся")
private val wordsHint = L("e.g. twinkle twinkle little star", "например: в лесу родилась ёлочка")
private val phonemesHint = L("e.g. SP t w i ng k ax l SP", "например: SP v l e s u SP")
private val fromFile = L("From a file…", "Из файла…")
private val fromFileNote = L("Lyrics, subtitles (.lrc, .srt) or a .lab: timings, tags and punctuation are removed.",
    "Текст песни, субтитры (.lrc, .srt) или .lab: тайминги, теги и знаки препинания убираются.")
private val needText = L("Enter the text, load it from a file or recognise it with Whisper", "Впишите текст, загрузите его из файла или распознайте его через Whisper")
private val whisperDirectT = L("Recognise the words with Whisper and place them straight away", "Распознать слова через Whisper и сразу их расставить")
private val directNoteT = L("Whisper hears the words when autolabelling starts. To check or correct them first, press Recognise: the text appears here.",
    "Whisper распознает слова, когда начнётся авторазметка. Чтобы сначала проверить или поправить их, нажмите «Распознать»: текст появится здесь.")
private val recogniseBtnT = L("Recognise", "Распознать")
private val breathsT = L("Breaths (AP) where they can be heard", "Вдохи (AP) там, где они слышны")
private val splitT = L("Align long recordings again in pieces between pauses", "Длинные записи выравнивать ещё раз по кускам между паузами")
private val splitLenT = L("Longest piece", "Самый длинный кусок")
private val splitNoteT = L("The aligner is most accurate on phrases. The recording is cut only at clear pauses and breaths, never inside a word.",
    "На фразах выравниватель точнее всего. Запись режется только на явных паузах и вдохах, слово не разрывается.")
private val recognisingT = L("Recognising…", "Распознаю…")
private val whisperBatchT = L("Files without text: recognise the words with Whisper", "Файлы без текста: распознать слова через Whisper")
private val allFiles = L("All files of the folder", "Все файлы папки")
private val whichFiles = L("Which files", "Какие файлы")
private val filesNoLabels = L("Without labels ({0})", "Без разметки ({0})")
private val filesNotDone = L("Not done ({0})", "Не готовые ({0})")
private val filesAll = L("All ({0})", "Все ({0})")
private val batchReplaces = L("Their labels are replaced by the result and saved. Stopping keeps what is done; running again on files without labels goes on from there.",
    "Их разметка заменится результатом и сохранится. Остановка сохраняет сделанное; повторный запуск по файлам без разметки продолжит с того места.")
private val batchNew = L("Each file gets its own labels, saved next to it. Stopping keeps what is done; running again goes on from there.",
    "Каждый файл получит свою разметку, она сохранится рядом. Остановка сохраняет сделанное; повторный запуск продолжит с того места.")
private val textFrom = L("Text of each file", "Текст каждого файла")
private val fromTxt = L(".txt with the same name", ".txt с тем же именем")
private val fromLabels = L("Phonemes from its labels", "Фонемы из его разметки")
private val forcedFromLabels = L("Place the phonemes already in the labels", "Расставить фонемы, которые уже есть в разметке")
private val asPhonemes = L("These are phonemes", "Это фонемы")
private val asText = L("These are words", "Это слова")
private val replaceHere = L("Put into the labels", "Записать в разметку")
private val forCompare = L("Show next to them for comparison", "Показать рядом для сравнения")
private val run = L("Start", "Запустить")
private val loadingModels = L("Asking the toolkit for models…", "Запрос моделей у тулкита…")
private val notInstalled = L("downloads on first use", "загружается при первом использовании")
private val noModels = L("No models for this", "Для этого нет моделей")

private val forcedTitle = L("Phonemes, if you know them (optional)", "Фонемы, если они известны (необязательно)")
private val forcedHint = L("e.g. SP k a s a SP", "например: SP k a s a SP")
private val fileNone = L("No text files in the folder", "В папке нет текстовых файлов")
private val forcedNote = L("Empty: the model hears the phonemes itself. Filled: it only places these, in this order.",
    "Пусто — модель сама определит фонемы. Заполнено — она только расставит эти, в этом порядке.")
private val wflMore = L("Recognition settings", "Настройки распознавания")
private val wflConf = L("Confidence threshold (older models; −1 = the model's own)", "Порог уверенности (старые модели; −1 — как задано в модели)")
private val wflConfHint = L("Phonemes the model is less sure about than this are merged into their neighbours.",
    "Фонемы, в которых модель уверена меньше этого, сливаются с соседними.")
private val wflNew = L("For models of the newer WFL-ASR version", "Для моделей новой версии WFL-ASR")
private val wflDecoder = L("Decoding", "Декодирование")
private val wflViterbi = L("Viterbi (smoother)", "Витерби (ровнее)")
private val wflConstrained = L("Frame by frame", "По кадрам")
private val wflBias = L("Holding a phoneme (Viterbi): higher = fewer, longer phonemes", "Удержание фонемы (Витерби): больше — меньше и длиннее фонемы")
private val wflSilence = L("Silence level (share of full scale)", "Уровень тишины (доля от максимума)")
private val wflMinSilence = L("Shortest silence marked as SP", "Самая короткая тишина, которая станет SP")

/** WFL-ASR options, kept in the settings. */
@Composable
private fun WflOptions(app: AppState) {
    val c = T.c
    val w = app.settings.toolkit.wfl
    val d = mlabeler.app.state.WflSettings()
    fun set(f: (mlabeler.app.state.WflSettings) -> mlabeler.app.state.WflSettings) = app.update { it.copy(toolkit = it.toolkit.copy(wfl = f(it.toolkit.wfl))) }
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(c.panelAlt).padding(10.dp)) {
        ValueSlider(wflConf(), w.confidence, -1f..1f, decimals = 2, default = d.confidence) { v -> set { it.copy(confidence = if (v < 0f) -1f else v) } }
        Text(wflConfHint(), color = c.muted, fontSize = 11.sp)
        SectionTitle(wflNew())
        Text(wflDecoder(), color = c.text, fontSize = 13.sp)
        Row(Modifier.padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(wflViterbi(), w.decoder == "viterbi") { set { it.copy(decoder = "viterbi") } }
            Chip(wflConstrained(), w.decoder != "viterbi") { set { it.copy(decoder = "constrained") } }
        }
        if (w.decoder == "viterbi") ValueSlider(wflBias(), w.viterbiBias, 1f..20f, decimals = 1, default = d.viterbiBias) { v -> set { it.copy(viterbiBias = v) } }
        ValueSlider(wflSilence(), w.silenceThreshold, 0f..0.05f, decimals = 3, default = d.silenceThreshold) { v -> set { it.copy(silenceThreshold = v) } }
        ValueSlider(wflMinSilence(), w.minSilence, 0.1f..3f, " s", decimals = 2, default = d.minSilence) { v -> set { it.copy(minSilence = v) } }
    }
}

private val ownModelLink = L("Add your own model…", "Добавить свою модель…")
private val checkTextsLink = L("Check the texts of the files, recognise the empty ones…", "Проверить тексты файлов, распознать пустые…")
private val checkWordsLink = L("Check the words and own words of the model…", "Проверить слова и собственные слова модели…")
private val otherLangs = L("Other languages in the text", "Другие языки в тексте")
private val otherLangsNote = L("For example English words in Chinese lyrics. Their phonemes get the language in front: en/s.",
    "Например, английские слова в китайском тексте. Их фонемы будут с языком впереди: en/s.")

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
    var batch by remember { mutableStateOf(false) }
    var which by remember { mutableStateOf(mlabeler.app.state.FileFilter.NoLabels) }
    var batchSource by remember { mutableStateOf(mlabeler.app.state.EditorState.BatchText.TxtNextToIt) }
    var whisper by remember { mutableStateOf(settings.whisper) }
    var direct by remember { mutableStateOf(settings.whisperDirect) }
    var recognising by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val range = if (whole || selected == null) 0.0 to ed.duration else selected
    var recognizeMode by remember { mutableStateOf(false) }
    val task = if (recognizeMode) "segment" else "align"
    val (langs, error) = rememberToolkitModels(app, task)
    var lang by remember(task) { mutableStateOf(settings.lastLanguage) }
    var model by remember(task) { mutableStateOf(if (recognizeMode) settings.lastSegmentModel else settings.lastModel) }
    var extraLangs by remember { mutableStateOf(settings.extraLanguages) }
    // a model that reads several languages in one text (TIFA): the other languages it knows
    val modelEngine = langs?.firstNotNullOfOrNull { g -> g.models.firstOrNull { it.id == model }?.engine }
    val otherLangCodes = if (modelEngine != "tifa" || recognizeMode) emptyList()
        else langs.orEmpty().filter { g -> g.code != lang && g.code != "*" && g.models.any { it.id == model } }.map { it.code }
    val extra = extraLangs.filter { it in otherLangCodes }
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
    var forced by remember { mutableStateOf("") }
    var showWfl by remember { mutableStateOf(false) }
    var text by remember(whole) { mutableStateOf(if (wordTier != null) textsIn(wordTier, range) else textsIn(phoneTier, range)) }
    // the whole file goes into the labels by default; a part is compared first
    var replace by remember(whole) { mutableStateOf(whole && (doc == null || doc.tiers.all { t -> (t as? IntervalTier)?.texts?.all { it.isEmpty() } ?: true })) }
    val textMissing = !recognizeMode && !batch && text.isBlank() && !direct
    var reviewTexts by remember { mutableStateOf(false) }
    if (reviewTexts) TextsDialog(ed, ed.batchFiles(which), lang.takeIf { it.isNotEmpty() && it != "*" }) { reviewTexts = false }
    Overlay({ close() }, 640) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            val modelOk = langs?.any { g -> g.models.any { it.id == model } } == true
            val files = if (batch) ed.batchFiles(which) else emptyList()
            Btn(run(), primary = true, enabled = modelOk && error == null && !textMissing && (!batch || files.isNotEmpty())) {
                app.update {
                    val t = if (recognizeMode) it.toolkit.copy(lastSegmentModel = model, lastLanguage = lang)
                    else it.toolkit.copy(lastModel = model, lastLanguage = lang, whisper = whisper, whisperDirect = if (batch) it.toolkit.whisperDirect else direct,
                        extraLanguages = if (otherLangCodes.isEmpty()) it.toolkit.extraLanguages else extra)
                    it.copy(toolkit = t)
                }
                val language = lang.takeIf { it.isNotEmpty() && it != "*" }
                if (batch) {
                    val src = if (recognizeMode && batchSource == mlabeler.app.state.EditorState.BatchText.TxtNextToIt) mlabeler.app.state.EditorState.BatchText.None else batchSource
                    ed.autolabelFiles(files, model, language, recognizeMode, src, phonemes, whisper && !recognizeMode, extra)
                } else {
                    // Whisper's words straight away: no text goes with the request
                    val sung = if (recognizeMode) forced else if (direct) "" else text
                    ed.autolabel(range.first, range.second, model, language, sung, phonemes && !direct && text.isNotBlank(), replace,
                        recognize = recognizeMode, whisper = direct && !recognizeMode, extraLanguages = extra)
                }
                close()
            }
        }) {
            Text(title(), color = c.text, fontSize = 17.sp)
            Column(Modifier.padding(top = 10.dp)) { ToolkitStatus(app) }
            SectionTitle(whatPart())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) Chip(selectedPart(), !whole && !batch) { whole = false; batch = false }
                Chip(wholeFile(), whole && !batch) { whole = true; batch = false }
                Chip(allFiles(), batch) { batch = true }
            }
            if (!batch) Text(partLine.format(formatTime(range.first), formatTime(range.second), formatMs(range.second - range.first)), color = c.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            val batchCounts = remember(batch, ed.items) {
                if (!batch) emptyMap() else mlabeler.app.state.FileFilter.entries.associateWith { ed.batchFiles(it).size }
            }
            if (batch) {
                SectionTitle(whichFiles())
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(filesNoLabels.format(batchCounts[mlabeler.app.state.FileFilter.NoLabels] ?: 0), which == mlabeler.app.state.FileFilter.NoLabels) { which = mlabeler.app.state.FileFilter.NoLabels }
                    Chip(filesNotDone.format(batchCounts[mlabeler.app.state.FileFilter.NotDone] ?: 0), which == mlabeler.app.state.FileFilter.NotDone) { which = mlabeler.app.state.FileFilter.NotDone }
                    Chip(filesAll.format(batchCounts[mlabeler.app.state.FileFilter.All] ?: 0), which == mlabeler.app.state.FileFilter.All) { which = mlabeler.app.state.FileFilter.All }
                }
                Text(if (which == mlabeler.app.state.FileFilter.NoLabels) batchNew() else batchReplaces(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            SectionTitle(howT())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(byText(), byText2(), !recognizeMode, Modifier.weight(1f)) { recognizeMode = false }
                Choice(recognize(), recognize2(), recognizeMode, Modifier.weight(1f)) { recognizeMode = true }
            }
            Text(if (recognizeMode) recognizeAbout() else byTextAbout(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            SectionTitle(S.language() + " · " + L("model", "модель")())
            when {
                error != null -> Text(error, color = c.danger, fontSize = 13.sp)
                langs == null -> Text(if (app.toolkit.status == mlabeler.app.toolkit.ToolkitManager.Status.Ready) loadingModels() else "—", color = c.muted, fontSize = 13.sp)
                langs.isEmpty() -> Text(noModels(), color = c.muted, fontSize = 13.sp)
                else -> {
                    // all languages visible at once: chips wrap onto more lines
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in langs.sortedWith(compareBy({ it.code == "*" }, { mlabeler.app.i18n.LanguageNames.of(it.code, it.name) }))) Chip(mlabeler.app.i18n.LanguageNames.of(l.code, l.name), l.code == lang) { lang = l.code }
                    }
                    val models = langs.firstOrNull { it.code == lang }?.models.orEmpty()
                    Column(Modifier.padding(top = 6.dp).heightIn(max = 200.dp).scrollWithHint()) {
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
            if (otherLangCodes.isNotEmpty()) {
                Text(otherLangs(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (code in otherLangCodes) {
                        val name = mlabeler.app.i18n.LanguageNames.of(code, langs.orEmpty().firstOrNull { it.code == code }?.name ?: code)
                        Chip(name, code in extra) { extraLangs = if (code in extraLangs) extraLangs - code else extraLangs + code }
                    }
                }
                Text(otherLangsNote(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!recognizeMode && modelEngine in setOf("sofa", "tifa", "hubertfa")) {
                val tk = app.settings.toolkit
                fun set(f: (mlabeler.app.state.ToolkitSettings) -> mlabeler.app.state.ToolkitSettings) = app.update { it.copy(toolkit = f(it.toolkit)) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { set { it.copy(breaths = !it.breaths) } }, verticalAlignment = Alignment.CenterVertically) {
                    Text(breathsT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Toggle(tk.breaths, { v -> set { it.copy(breaths = v) } })
                }
                // HubertFA has no second pass in pieces
                if (modelEngine != "hubertfa") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clickable { set { it.copy(splitLong = !it.splitLong) } }, verticalAlignment = Alignment.CenterVertically) {
                        Text(splitT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Toggle(tk.splitLong, { v -> set { it.copy(splitLong = v) } })
                    }
                    if (tk.splitLong) {
                        ValueSlider(splitLenT(), tk.splitSeconds, 5f..60f, " s", default = 25f) { v -> set { it.copy(splitSeconds = kotlin.math.round(v)) } }
                        Text(splitNoteT(), color = c.muted, fontSize = 11.sp)
                    }
                }
            }
            Text(ownModelLink(), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                .clickable { app.settingsPage = "Toolkit"; app.showAutolabel = false; app.showSettings = true })
            if (recognizeMode) {
                if (batch) {
                    Row(Modifier.padding(top = 10.dp)) {
                        Chip(forcedFromLabels(), batchSource == mlabeler.app.state.EditorState.BatchText.Labels) {
                            batchSource = if (batchSource == mlabeler.app.state.EditorState.BatchText.Labels) mlabeler.app.state.EditorState.BatchText.None
                            else mlabeler.app.state.EditorState.BatchText.Labels
                        }
                    }
                } else {
                    SectionTitle(forcedTitle())
                    Field(forced, { forced = it }, Modifier.fillMaxWidth(), placeholder = forcedHint())
                    Text(forcedNote(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 10.dp)) { Chip(wflMore(), showWfl) { showWfl = !showWfl } }
                if (showWfl) WflOptions(app)
            }
            if (!recognizeMode) {
                if (batch) {
                    SectionTitle(textFrom())
                    if (batchSource != mlabeler.app.state.EditorState.BatchText.Labels) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { whisper = !whisper }, verticalAlignment = Alignment.CenterVertically) {
                            Text(whisperBatchT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Toggle(whisper, { whisper = it })
                        }
                        if (whisper) Column(Modifier.padding(bottom = 6.dp)) {
                            WhisperModelChoice(settings.whisperModel) { id -> app.update { it.copy(toolkit = it.toolkit.copy(whisperModel = id)) } }
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(fromTxt(), batchSource != mlabeler.app.state.EditorState.BatchText.Labels) { batchSource = mlabeler.app.state.EditorState.BatchText.TxtNextToIt }
                        Chip(fromLabels(), batchSource == mlabeler.app.state.EditorState.BatchText.Labels) { batchSource = mlabeler.app.state.EditorState.BatchText.Labels }
                    }
                    if (batchSource != mlabeler.app.state.EditorState.BatchText.Labels) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(asText(), !phonemes) { phonemes = false }
                            Chip(asPhonemes(), phonemes) { phonemes = true }
                        }
                        Text(fromFileNote(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(checkTextsLink(), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { reviewTexts = true })
                    }
                } else {
                    SectionTitle(textTitle())
                    // Whisper first: its words are placed as they are, or put into the field to be checked
                    Row(Modifier.fillMaxWidth().clickable { direct = !direct }, verticalAlignment = Alignment.CenterVertically) {
                        Text(whisperDirectT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Toggle(direct, { direct = it })
                    }
                    Column(Modifier.padding(top = 6.dp)) {
                        WhisperModelChoice(settings.whisperModel) { id -> app.update { it.copy(toolkit = it.toolkit.copy(whisperModel = id)) } }
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        Btn(if (recognising) recognisingT() else recogniseBtnT(), enabled = !recognising) {
                            recognising = true
                            scope.launch {
                                try {
                                    val heard = ed.transcribePart(range.first, range.second, lang.takeIf { it.isNotEmpty() && it != "*" })
                                    text = heard
                                    phonemes = false
                                    direct = false
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    app.message(e.message ?: e.toString(), error = true)
                                } finally {
                                    recognising = false
                                }
                            }
                        }
                    }
                    if (direct) Text(directNoteT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    else {
                        // the text and where it can come from in one row; what the text is (a choice) below it
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Field(text, { text = it }, Modifier.weight(1f), placeholder = if (phonemes) phonemesHint() else wordsHint())
                            TextFromFile(ed.workspace.root) { raw ->
                                val asPh = phonemes || mlabeler.core.format.TextImport.looksLikeLab(raw)
                                phonemes = asPh
                                text = mlabeler.core.format.TextImport.clean(raw, asPh)
                            }
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(asText(), !phonemes) { phonemes = false }
                            Chip(asPhonemes(), phonemes) { phonemes = true }
                        }
                    }
                }
            }
            if (!recognizeMode && !(batch && batchSource == mlabeler.app.state.EditorState.BatchText.Labels) && !(phonemes && !batch) && !(direct && !batch)
                && langs?.any { g -> g.models.any { it.id == model } } == true) {
                Text(checkWordsLink(), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).clickable {
                    val language = lang.takeIf { it.isNotEmpty() && it != "*" }
                    app.wordsCheck = if (batch) WordsCheck(model, language, files = ed.batchFiles(which)) else WordsCheck(model, language, text = text)
                })
            }
            RefineAfterSwitch(app)
            if (!batch) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(forCompare(), !replace) { replace = false }
                Chip(replaceHere(), replace) { replace = true }
            }
            if (textMissing) Text(needText(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Big two-line option card. */
@Composable
fun Choice(title: String, sub: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = T.c
    val shape = mlabeler.app.theme.RoundedCornerShape(c.radius)
    Column(
        modifier.clip(shape).background(if (selected) c.accent.copy(alpha = 0.16f) else c.panelAlt)
            .border(if (selected) 2.dp else c.borderWidth, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(title, color = c.text, fontSize = 14.sp)
        Text(sub, color = c.muted, fontSize = 11.sp)
    }
}

/**
 * Loads a text file for the text field: the system file dialog on computers, the text files of the open folder
 * on phones (no system dialog there).
 */
@Composable
private fun TextFromFile(folder: String, onText: (String) -> Unit) {
    val c = T.c
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val exts = listOf("txt", "lab", "lrc", "srt")
    fun load(path: String) {
        runCatching { mlabeler.core.io.PlatformFs.read(path).decodeToString() }.getOrNull()?.let(onText)
    }
    androidx.compose.foundation.layout.Box {
        Btn(fromFile()) {
            if (mlabeler.app.Platform.hasNativeFolderPicker) {
                scope.launch {
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        mlabeler.app.Platform.pickFileNative(fromFile(), exts, folder)
                    }
                    if (p != null) load(p)
                }
            } else menu = true
        }
        MenuPopup(menu, onDismiss = { menu = false }, focusable = true) {
            val files = remember(folder) {
                runCatching { mlabeler.core.io.PlatformFs.list(folder) }.getOrDefault(emptyList())
                    .filter { mlabeler.core.io.Paths.ext(it).lowercase() in exts }.sorted()
            }
            if (files.isEmpty()) androidx.compose.material3.DropdownMenuItem({ Text(fileNone(), color = c.muted, fontSize = 13.sp) }, onClick = { menu = false })
            for (f in files) androidx.compose.material3.DropdownMenuItem({ Text(mlabeler.core.io.Paths.name(f), fontSize = 13.sp) }, onClick = { menu = false; load(f) })
        }
    }
}
