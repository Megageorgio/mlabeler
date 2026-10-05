package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.Lang
import mlabeler.app.i18n.L
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.theme.Themes
import mlabeler.core.format.LabelFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Dim background with a centred card; full screen on narrow windows. */
@Composable
fun Overlay(onDismiss: () -> Unit, maxWidth: Int = 560, content: @Composable () -> Unit) {
    val c = T.c
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
            .windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
        contentAlignment = Alignment.TopCenter,
    ) {
        val narrow = this.maxWidth < 600.dp
        Card(
            (if (narrow) Modifier.fillMaxSize() else Modifier.padding(top = 56.dp).widthIn(max = maxWidth.dp).fillMaxWidth().heightIn(max = this.maxHeight - 96.dp))
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) { content() }
    }
}

@Composable
fun CommandPalette(app: AppState) {
    val c = T.c
    val ed = app.editor ?: return
    var query by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = Commands.visible(ed.mode).filter { query.isBlank() || it.title().contains(query.trim(), ignoreCase = true) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(index) { if (list.isNotEmpty()) state.scrollToItem(index.coerceIn(0, list.size - 1)) }
    fun runAt(i: Int) {
        val cmd = list.getOrNull(i) ?: return
        app.showCommands = false
        if (cmd.enabled(ed)) cmd.run(ed, app)
        ed.requestFocus()
    }
    Overlay({ app.showCommands = false; ed.requestFocus() }, 520) {
        Field(
            query, { query = it; index = 0 },
            Modifier.fillMaxWidth().padding(10.dp).focusRequester(focus).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> { index = (index + 1).coerceAtMost(list.size - 1); true }
                    Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                    Key.Enter, Key.NumPadEnter -> { runAt(index); true }
                    else -> false
                }
            },
            placeholder = S.typeCommand(),
            onDone = { runAt(index) },
        )
        Divider()
        LazyColumn(Modifier.heightIn(max = 420.dp), state = state) {
            itemsIndexed(list) { i, cmd ->
                Row(
                    Modifier.fillMaxWidth().background(if (i == index) c.accent.copy(alpha = if (c.square) 1f else 0.16f) else c.panel)
                        .clickable { runAt(i) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (i == index && c.square) c.onAccent else c.text
                    Text(cmd.title(), color = if (cmd.enabled(ed)) fg else c.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(cmd.keyLabel, color = if (i == index && c.square) c.onAccent else c.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private enum class Section { General, Interface, Themes, View, Spectrogram, Editing, Mouse, Checks, Toolkit, Keys, About }

@Composable
private fun sectionTitle(s: Section) = when (s) {
    Section.General -> S.general()
    Section.Interface -> MenuTitles.interfaceSet()
    Section.Themes -> S.theme()
    Section.View -> S.view()
    Section.Spectrogram -> S.spectrogramSection()
    Section.Editing -> S.editing()
    Section.Mouse -> MouseTitles.page()
    Section.Checks -> S.checks()
    Section.Toolkit -> S.toolkit()
    Section.Keys -> S.shortcuts()
    Section.About -> S.about()
}

/** Settings split into pages: a list on the left on wide windows, tabs on top on narrow ones. */
@Composable
fun SettingsDialog(app: AppState) {
    val c = T.c
    var section by remember {
        mutableStateOf(Section.entries.firstOrNull { it.name.equals(app.settingsPage, ignoreCase = true) } ?: Section.General)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.settingsPage = "" } }
    Overlay({ app.showSettings = false; app.editor?.requestFocus?.invoke() }, 820) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.settings(), color = c.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconBtn(Icons.close, S.close()) { app.showSettings = false }
        }
        Divider()
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
            val narrow = maxWidth < 560.dp
            if (narrow) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) { for (s in Section.entries) Chip(sectionTitle(s), s == section) { section = s } }
                    Divider()
                    SettingsPage(app, section, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().height(minOf(560.dp, maxHeight))) {
                    Column(Modifier.width(190.dp).fillMaxHeight().background(c.panelAlt).padding(vertical = 8.dp)) {
                        for (s in Section.entries) {
                            val sel = s == section
                            Text(
                                sectionTitle(s),
                                color = if (sel && c.square) c.onAccent else if (sel) c.accent else c.text,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (sel) c.accent.copy(alpha = if (c.square) 1f else 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { section = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Divider(vertical = true)
                    SettingsPage(app, section, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(app: AppState, section: Section, modifier: Modifier) {
    val c = T.c
    val s = app.settings
    val dE = mlabeler.app.state.EditSettings()
    val dV = mlabeler.app.state.ViewSettings()
    val dL = mlabeler.app.state.LayoutSettings()
    val dC = mlabeler.core.check.CheckSettings()
    val dScale = if (mlabeler.app.Platform.isMobile) 0.8f else 1f
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        when (section) {
            Section.General -> {
                SectionTitle(S.language())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                }
                if (mlabeler.app.Platform.isMobile) {
                    SectionTitle(S.screen())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((v, t) in listOf("landscape" to S.landscape(), "portrait" to S.portrait(), "auto" to S.autoRotate())) Chip(t, s.orientation == v) { app.update { it.copy(orientation = v) } }
                    }
                    SwitchRow(S.fullscreen(), s.fullscreen) { v -> app.update { it.copy(fullscreen = v) } }
                    if (s.fullscreen) SwitchRow(S.avoidCutout(), s.avoidCutout) { v -> app.update { it.copy(avoidCutout = v) } }
                }
                SectionTitle(S.files())
                SwitchRow(S.saveOnSwitch(), s.edit.saveOnSwitch) { v -> app.update { it.copy(edit = it.edit.copy(saveOnSwitch = v)) } }
                SwitchRow(S.otherAudio(), s.otherAudio) { v -> app.update { it.copy(otherAudio = v) } }
                ValueSlider(S.autosave(), s.edit.autosaveSeconds.toFloat(), 0f..300f, S.secondsShort(), default = dE.autosaveSeconds.toFloat()) { v -> app.update { it.copy(edit = it.edit.copy(autosaveSeconds = (v / 10).roundToInt() * 10)) } }
            }
            Section.View -> {
                ValueSlider(S.interfaceScale(), s.scale, 0.7f..2f, "%", factor = 100f, default = dScale) { v -> app.update { it.copy(scale = (v * 100).roundToInt() / 100f) } }
                SectionTitle(S.view())
                SectionTitle(S.view())
                SwitchRow(S.overlay(), s.layout.overlay) { v -> app.update { it.copy(layout = it.layout.copy(overlay = v)) } }
                if (s.layout.overlay) SwitchRow(S.overlayWaveFill(), s.layout.overlayWaveFill) { v -> app.update { it.copy(layout = it.layout.copy(overlayWaveFill = v)) } }
                if (s.layout.overlay && s.layout.overlayWaveFill) ValueSlider(S.overlayWaveFillAlpha(), s.layout.overlayWaveFillAlpha, 0.05f..1f, "%", factor = 100f,
                    default = mlabeler.app.state.LayoutSettings().overlayWaveFillAlpha) { v -> app.update { it.copy(layout = it.layout.copy(overlayWaveFillAlpha = v)) } }
                if (s.layout.overlay) ValueSlider(S.overlayDim(), s.layout.overlayDim, 0f..0.8f, "%", factor = 100f, default = dL.overlayDim) { v -> app.update { it.copy(layout = it.layout.copy(overlayDim = v)) } }
                SwitchRow(S.tiersOnTop(), s.layout.tiersOnTop) { v -> app.update { it.copy(layout = it.layout.copy(tiersOnTop = v)) } }
                SwitchRow(S.waveform(), s.layout.showWaveform) { v -> app.update { it.copy(layout = it.layout.copy(showWaveform = v)) } }
                SwitchRow(S.spectrogram(), s.layout.showSpectrogram) { v -> app.update { it.copy(layout = it.layout.copy(showSpectrogram = v)) } }
                SwitchRow(S.pitch(), s.layout.showPitch) { v -> app.update { it.copy(layout = it.layout.copy(showPitch = v)) } }
                if (s.layout.showPitch) SwitchRow(S.pitchOver(), s.layout.pitchOverSpectrogram) { v -> app.update { it.copy(layout = it.layout.copy(pitchOverSpectrogram = v)) } }
                SwitchRow(S.power(), s.layout.showPower) { v -> app.update { it.copy(layout = it.layout.copy(showPower = v)) } }
                SwitchRow(S.toggleFiles(), s.layout.showFiles) { v -> app.update { it.copy(layout = it.layout.copy(showFiles = v)) } }
                SwitchRow(S.toggleInspector(), s.layout.showInspector) { v -> app.update { it.copy(layout = it.layout.copy(showInspector = v)) } }
            }
            Section.Spectrogram -> {
                SectionTitle(S.colors())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(S.theme(), s.view.palette.isEmpty()) { app.update { it.copy(view = it.view.copy(palette = "")) } }
                    for (p in Themes.palettes.keys) Chip(p, s.view.palette == p) { app.update { it.copy(view = it.view.copy(palette = p)) } }
                }
                ValueSlider(S.brightness(), s.view.brightness, -0.5f..0.5f, factor = 100f, default = dV.brightness) { v -> app.update { it.copy(view = it.view.copy(brightness = v)) } }
                ValueSlider(S.contrast(), s.view.contrast, 0.5f..3f, "%", factor = 100f, default = dV.contrast) { v -> app.update { it.copy(view = it.view.copy(contrast = v)) } }
                ValueSlider(S.windowMs(), s.view.windowMs, 5f..80f, S.msUnit(), default = dV.windowMs) { v -> app.update { it.copy(view = it.view.copy(windowMs = v.roundToInt().toFloat())) } }
                ValueSlider(S.hopMs(), s.view.hopMs, 0f..20f, S.msUnit(), decimals = 1, default = dV.hopMs) { v -> app.update { it.copy(view = it.view.copy(hopMs = (v * 2).roundToInt() / 2f)) } }
                ValueSlider(S.bands(), s.view.bands.toFloat(), 64f..384f, default = dV.bands.toFloat()) { v -> app.update { it.copy(view = it.view.copy(bands = (v / 32).roundToInt() * 32)) } }
                ValueSlider(S.dbRange(), s.view.minDb, -140f..-40f, "dB", default = dV.minDb) { v -> app.update { it.copy(view = it.view.copy(minDb = v.roundToInt().toFloat())) } }
                ValueSlider(S.dbTop(), s.view.maxDb, -40f..10f, "dB", default = dV.maxDb) { v -> app.update { it.copy(view = it.view.copy(maxDb = v.roundToInt().toFloat())) } }
                ValueSlider(S.maxFrequency(), s.view.maxFreq, 1000f..24000f, S.hzShort(), default = dV.maxFreq) { v -> app.update { it.copy(view = it.view.copy(maxFreq = v.roundToInt().toFloat())) } }
            }
            Section.Editing -> {
                SectionTitle(S.editing())
                ValueSlider(S.nudgeStep(), s.edit.nudgeMs, 1f..50f, S.msUnit(), default = dE.nudgeMs) { v -> app.update { it.copy(edit = it.edit.copy(nudgeMs = v.roundToInt().toFloat())) } }
                ValueSlider(S.minInterval(), s.edit.minIntervalMs, 0f..20f, S.msUnit(), default = dE.minIntervalMs) { v -> app.update { it.copy(edit = it.edit.copy(minIntervalMs = v.roundToInt().toFloat())) } }
                SwitchRow(S.ripple() + " — " + S.rippleHint(), s.edit.ripple) { v -> app.update { it.copy(edit = it.edit.copy(ripple = v)) } }
                SwitchRow(S.linked() + " — " + S.linkedHint(), s.edit.linked) { v -> app.update { it.copy(edit = it.edit.copy(linked = v)) } }
                SwitchRow(S.loop(), s.edit.loop) { v -> app.update { it.copy(edit = it.edit.copy(loop = v)) } }
                ValueSlider(S.speedSetting(), s.edit.speed, 0.1f..1f, "×", decimals = 2, default = dE.speed) { v -> app.update { it.copy(edit = it.edit.copy(speed = (v * 100).roundToInt() / 100f)) } }
                SwitchRow(S.playOnDrag(), s.edit.playOnDrag) { v -> app.update { it.copy(edit = it.edit.copy(playOnDrag = v)) } }
                SwitchRow(S.otoLocked(), s.edit.otoLockedDrag) { v -> app.update { it.copy(edit = it.edit.copy(otoLockedDrag = v)) } }
                SwitchRow(MouseTitles.spaceRestarts(), s.edit.spaceRestarts) { v -> app.update { it.copy(edit = it.edit.copy(spaceRestarts = v)) } }
                Text(MouseTitles.owner(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Text(MouseTitles.ownerHint(), color = c.muted, fontSize = 12.sp)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(MouseTitles.ownerEnd(), s.edit.boundaryOwner == "end") { app.update { it.copy(edit = it.edit.copy(boundaryOwner = "end")) } }
                    Chip(MouseTitles.ownerStart(), s.edit.boundaryOwner != "end") { app.update { it.copy(edit = it.edit.copy(boundaryOwner = "start")) } }
                }
            }
            Section.Mouse -> MousePage(app)
            Section.Checks -> {
                SectionTitle(S.checks())
                ValueSlider(S.shortThreshold(), s.checks.minDurationMs.toFloat(), 0f..150f, S.msUnit(), default = dC.minDurationMs.toFloat()) { v ->
                    app.update { it.copy(checks = it.checks.copy(minDurationMs = v.roundToInt().toDouble())) }
                }
                var phonemes by remember { mutableStateOf(s.checks.phonemeSet.joinToString(" ")) }
                Text(S.phonemeSet(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Field(phonemes, { v ->
                    phonemes = v
                    app.update { it.copy(checks = it.checks.copy(phonemeSet = v.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }.toSet())) }
                }, Modifier.fillMaxWidth())
            }
            Section.Toolkit -> ToolkitPage(app)
            Section.Interface -> InterfacePage(app)
            Section.Themes -> ThemesPage(app)
            Section.Keys -> KeymapPage(app)
            Section.About -> {
                SectionTitle(S.about())
                Text("mLabeler 0.1", color = c.text, fontSize = 15.sp)
                Text(S.aboutText(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // every page can go back to the defaults (with a second click to confirm)
        val reset: ((mlabeler.app.state.AppSettings) -> mlabeler.app.state.AppSettings)? = when (section) {
            Section.General -> { st -> st.copy(otherAudio = false, edit = st.edit.copy(saveOnSwitch = dE.saveOnSwitch, autosaveSeconds = dE.autosaveSeconds)) }
            Section.View -> { st -> st.copy(scale = dScale, font = "", layout = dL.copy(showFiles = st.layout.showFiles, showInspector = st.layout.showInspector,
                filesWidth = st.layout.filesWidth, inspectorWidth = st.layout.inspectorWidth)) }
            Section.Spectrogram -> { st -> st.copy(view = dV) }
            Section.Editing -> { st -> st.copy(edit = dE.copy(tool = st.edit.tool, cutAskName = st.edit.cutAskName, cutPlay = st.edit.cutPlay,
                playOnDrag = st.edit.playOnDrag, newFormat = st.edit.newFormat)) }
            Section.Mouse -> { st -> st.copy(mouse = mlabeler.app.state.MouseSettings(), edit = st.edit.copy(tool = dE.tool, cutAskName = dE.cutAskName,
                cutPlay = dE.cutPlay, playOnDrag = dE.playOnDrag)) }
            Section.Checks -> { st -> st.copy(checks = dC) }
            Section.Toolkit -> { st -> st.copy(toolkit = mlabeler.app.state.ToolkitSettings(lastModel = st.toolkit.lastModel,
                lastLanguage = st.toolkit.lastLanguage, lastSegmentModel = st.toolkit.lastSegmentModel)) }
            Section.Themes -> { st -> st.copy(theme = "modern-dark", font = "") }
            Section.Keys -> { st -> Keymap.load(emptyMap()); st.copy(keymap = emptyMap()) }
            Section.Interface -> { st -> mlabeler.app.state.Environments.byId(st.environment)?.let { e -> mlabeler.app.state.Environments.apply(st, e) } ?: st }
            Section.About -> null
        }
        if (reset != null) {
            var confirm by remember(section) { mutableStateOf(false) }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!confirm) Btn(S.resetPage()) { confirm = true }
                else {
                    Btn(S.resetPage() + "?", primary = true) { confirm = false; app.update(reset) }
                    Btn(S.cancel()) { confirm = false }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = T.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Suppress("unused")
@Composable
private fun Gap() = Box(Modifier.height(8.dp))

private val pressKeys = mlabeler.app.i18n.L("Press the keys…", "Нажмите клавиши…")
private val resetAll = mlabeler.app.i18n.L("Reset all", "Сбросить все")
private val keysHint = mlabeler.app.i18n.L("Click a command and press new keys. Esc cancels, Backspace removes the binding.",
    "Нажмите на команду, затем новые клавиши. Esc — отмена, Backspace — убрать сочетание.")
private val usedBy = mlabeler.app.i18n.L("also used by: {0}", "также у: {0}")

@Composable
private fun KeymapPage(app: AppState) {
    val c = T.c
    var capturing by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun save(map: Map<String, List<Chord>>) {
        Keymap.overrides = map
        app.update { it.copy(keymap = Keymap.toSettings()) }
    }
    androidx.compose.runtime.LaunchedEffect(capturing) { if (capturing != null) runCatching { focus.requestFocus() } }
    Column(
        Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            val id = capturing ?: return@onPreviewKeyEvent false
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            val k = e.key
            if (k in setOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight)) return@onPreviewKeyEvent true
            when (k) {
                Key.Escape -> capturing = null
                Key.Backspace -> { save(Keymap.overrides + (id to emptyList<Chord>())); capturing = null }
                else -> {
                    val ctrl = if (mlabeler.app.Platform.isMac) e.isMetaPressed else e.isCtrlPressed
                    save(Keymap.overrides + (id to listOf<Chord>(Chord(k, ctrl, e.isShiftPressed, e.isAltPressed))))
                    capturing = null
                }
            }
            true
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(S.shortcuts(), Modifier.weight(1f))
            Btn(resetAll()) { save(emptyMap()) }
        }
        Text(keysHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        for (cmd in Commands.all) {
            val conflicts = cmd.keys.flatMap { k -> Commands.all.filter { o -> o !== cmd && o.keys.contains(k) && (o.mode == null || cmd.mode == null || o.mode == cmd.mode) } }
            Column(
                Modifier.fillMaxWidth().clickable { capturing = cmd.id }
                    .background(if (capturing == cmd.id) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            ) {
                Row {
                    Text(cmd.title(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (capturing == cmd.id) pressKeys() else cmd.keys.joinToString("   ") { it.label() }.ifEmpty { "—" },
                        color = if (capturing == cmd.id) c.accent else if (Keymap.overrides.containsKey(cmd.id)) c.text else c.muted, fontSize = 12.sp,
                    )
                }
                if (conflicts.isNotEmpty()) Text(usedBy.format(conflicts.joinToString { it.title() }), color = c.warn, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ToolkitPage(app: AppState) {
    val c = T.c
    val s = app.settings
    val tk = app.toolkit
    SectionTitle(S.toolkit())
    Text(S.toolkitHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    ToolkitStatus(app)
    var url by remember { mutableStateOf(s.toolkit.url) }
    var token by remember { mutableStateOf(s.toolkit.token) }
    Text(S.toolkitUrl(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    Field(url, { url = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(url = it.trim())) } }, Modifier.fillMaxWidth())
    Text(S.toolkitToken(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    Field(token, { token = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(token = it.trim())) } }, Modifier.fillMaxWidth())
    if (mlabeler.app.toolkit.LocalToolkit.supported) {
        SwitchRow(S.toolkitAutoStart(), s.toolkit.autoStart) { v -> app.update { it.copy(toolkit = it.toolkit.copy(autoStart = v)) } }
        SwitchRow(S.toolkitShare(), s.toolkit.shareOnNetwork) { v ->
            app.update { it.copy(toolkit = it.toolkit.copy(shareOnNetwork = v)) }
            if (tk.ownProcess || (v && tk.status != mlabeler.app.toolkit.ToolkitManager.Status.Ready)) tk.restart()
        }
        if (s.toolkit.shareOnNetwork) {
            val ips = remember { mlabeler.app.toolkit.LocalToolkit.lanAddresses() }
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(c.panelAlt).padding(10.dp)) {
                Text(S.toolkitShareHint(), color = c.muted, fontSize = 12.sp)
                for (ip in ips.ifEmpty { listOf("?") }) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text("http://$ip:${tk.port}", color = c.text, fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(S.toolkitToken() + ": " + tk.networkToken.ifEmpty { "…" }, color = c.text, fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
                if (!tk.ownProcess) Text(S.toolkitShareOwn(), color = c.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        var more by remember { mutableStateOf(false) }
        Row(Modifier.padding(top = 10.dp)) { Chip(S.more(), more) { more = !more } }
        if (more) {
            var mvt by remember { mutableStateOf(s.toolkit.mvtPath) }
            var src by remember { mutableStateOf(s.toolkit.installSource) }
            Text(S.toolkitMvtPath(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Field(mvt, { mvt = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(mvtPath = it.trim())) } }, Modifier.fillMaxWidth(),
                placeholder = mlabeler.app.toolkit.LocalToolkit.findMvt("") ?: "mvt")
            Text(S.toolkitSource(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Field(src, { src = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(installSource = it.trim())) } }, Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(S.toolkitReinstall(), enabled = !tk.installing) { tk.stop(); tk.install() }
            }
        }
    }
}

@Composable
private fun InterfacePage(app: AppState) {
    val c = T.c
    val s = app.settings
    SectionTitle(S.environments())
    EnvironmentsSection(app)
    SectionTitle(MenuTitles.panels())
    if (!mlabeler.app.Platform.isMobile) SwitchRow(MenuTitles.menuBar(), s.menuBar) { v -> app.update { it.copy(menuBar = v) } }
    SwitchRow(MenuTitles.statusBar(), s.statusBar) { v -> app.update { it.copy(statusBar = v) } }
    SwitchRow(MenuTitles.filesPanel(), s.layout.showFiles) { v -> app.update { it.copy(layout = it.layout.copy(showFiles = v)) } }
    SwitchRow(MenuTitles.detailsPanel(), s.layout.showInspector) { v -> app.update { it.copy(layout = it.layout.copy(showInspector = v)) } }
    SectionTitle(MenuTitles.toolbar())
    SwitchRow(MenuTitles.buttonLabels(), s.toolbar.labels) { v -> app.update { it.copy(toolbar = it.toolbar.copy(labels = v)) } }
    SwitchRow(MenuTitles.bigButtons(), s.toolbar.big) { v -> app.update { it.copy(toolbar = it.toolbar.copy(big = v)) } }
    Text(S.toolbarHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    // shown groups first, in their order, then the hidden ones
    val shown = s.toolbar.groups
    val order = shown + mlabeler.app.state.ToolbarGroups.all.filter { it !in shown }
    for (g in order) {
        val on = g in shown
        val i = shown.indexOf(g)
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(on, { v ->
                app.update { st -> st.copy(toolbar = st.toolbar.copy(groups = if (v) st.toolbar.groups + g else st.toolbar.groups - g)) }
            }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.muted, checkmarkColor = c.onAccent))
            Text(ToolLabels.group(g)(), color = if (on) c.text else c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (on) {
                IconBtn(Icons.up, S.moveUp(), enabled = i > 0, size = 28.dp) {
                    app.update { st -> st.copy(toolbar = st.toolbar.copy(groups = st.toolbar.groups.toMutableList().also { swapAt(it, i, i - 1) })) }
                }
                IconBtn(Icons.down, S.moveDown(), enabled = i < shown.size - 1, size = 28.dp) {
                    app.update { st -> st.copy(toolbar = st.toolbar.copy(groups = st.toolbar.groups.toMutableList().also { swapAt(it, i, i + 1) })) }
                }
            }
        }
    }
}

private fun swapAt(l: MutableList<String>, a: Int, b: Int) {
    if (a !in l.indices || b !in l.indices) return
    val t = l[a]; l[a] = l[b]; l[b] = t
}

object MouseTitles {
    val page = L("Mouse", "Мышь")
    val tool = L("Tool", "Инструмент")
    val cursor = L("Cursor: click selects, drag moves (1)", "Курсор: клик выбирает, перетаскивание двигает (1)")
    val cut = L("Scissors: click adds a boundary (2)", "Ножницы: клик ставит границу (2)")
    val toolHint = L("Near a boundary both tools drag it. Shift+click and dragging over the audio select a part in both.",
        "Рядом с границей оба инструмента её двигают. Shift+клик и протягивание по звуку выделяют кусок в обоих.")
    val askName = L("Type the name of the new part right away", "Сразу вводить название новой части")
    val playIt = L("Play the part before a new boundary", "Проигрывать часть перед новой границей")
    val onLabels = L("On label lanes", "На полосах разметки")
    val onAudio = L("On the waveform and spectrogram", "На волне и спектрограмме")
    val double = L("Double click", "Двойной клик")
    val right = L("Right click", "Правый клик")
    val middle = L("Middle click (dragging with it scrolls)", "Средний клик (с перетаскиванием — прокрутка)")
    val ctrl = L("Ctrl+click", "Ctrl+клик")
    val alt = L("Alt+click", "Alt+клик")
    val spaceRestarts = L("Space while playing starts again (instead of stopping)", "Пробел во время проигрывания начинает заново (а не останавливает)")
    val owner = L("A boundary belongs to the phoneme…", "Граница относится к фонеме…")
    val ownerHint = L("Delete on a selected boundary removes that phoneme; Space plays it.",
        "Delete на выбранной границе убирает эту фонему, пробел её проигрывает.")
    val ownerEnd = L("that ends at it", "которая на ней заканчивается")
    val ownerStart = L("that starts at it", "которая с неё начинается")

    fun action(id: String): String = when (id) {
        mlabeler.app.state.MouseActions.NONE -> L("Nothing", "Ничего")()
        mlabeler.app.state.MouseActions.SELECT -> L("Select", "Выбрать")()
        mlabeler.app.state.MouseActions.PLAY -> L("Play the phoneme", "Проиграть фонему")()
        mlabeler.app.state.MouseActions.PLAY_FROM -> L("Play from here", "Играть отсюда")()
        mlabeler.app.state.MouseActions.RENAME -> L("Rename", "Переименовать")()
        mlabeler.app.state.MouseActions.SPLIT -> L("Add a boundary", "Поставить границу")()
        mlabeler.app.state.MouseActions.SPLIT_NAME -> L("Add a boundary and name it", "Поставить границу и назвать")()
        mlabeler.app.state.MouseActions.DELETE -> L("Remove the phoneme", "Убрать фонему")()
        else -> id
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MousePage(app: AppState) {
    val c = T.c
    val s = app.settings
    SectionTitle(MouseTitles.tool())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(MouseTitles.cursor(), s.edit.tool != "cut") { app.update { it.copy(edit = it.edit.copy(tool = "cursor")) } }
        Chip(MouseTitles.cut(), s.edit.tool == "cut") { app.update { it.copy(edit = it.edit.copy(tool = "cut")) } }
    }
    Text(MouseTitles.toolHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    SwitchRow(MouseTitles.askName(), s.edit.cutAskName) { v -> app.update { it.copy(edit = it.edit.copy(cutAskName = v)) } }
    SwitchRow(MouseTitles.playIt(), s.edit.cutPlay) { v -> app.update { it.copy(edit = it.edit.copy(cutPlay = v)) } }
    SwitchRow(S.playOnDrag(), s.edit.playOnDrag) { v -> app.update { it.copy(edit = it.edit.copy(playOnDrag = v)) } }
    val m = s.mouse
    fun set(f: (mlabeler.app.state.MouseSettings) -> mlabeler.app.state.MouseSettings) = app.update { it.copy(mouse = f(it.mouse)) }
    SectionTitle(MouseTitles.onLabels())
    ActionRow(MouseTitles.double(), m.tierDouble) { v -> set { it.copy(tierDouble = v) } }
    ActionRow(MouseTitles.right(), m.tierRight) { v -> set { it.copy(tierRight = v) } }
    ActionRow(MouseTitles.middle(), m.tierMiddle) { v -> set { it.copy(tierMiddle = v) } }
    ActionRow(MouseTitles.ctrl(), m.tierCtrl) { v -> set { it.copy(tierCtrl = v) } }
    ActionRow(MouseTitles.alt(), m.tierAlt) { v -> set { it.copy(tierAlt = v) } }
    SectionTitle(MouseTitles.onAudio())
    ActionRow(MouseTitles.double(), m.audioDouble) { v -> set { it.copy(audioDouble = v) } }
    ActionRow(MouseTitles.right(), m.audioRight) { v -> set { it.copy(audioRight = v) } }
    ActionRow(MouseTitles.middle(), m.audioMiddle) { v -> set { it.copy(audioMiddle = v) } }
    ActionRow(MouseTitles.ctrl(), m.audioCtrl) { v -> set { it.copy(audioCtrl = v) } }
    ActionRow(MouseTitles.alt(), m.audioAlt) { v -> set { it.copy(audioAlt = v) } }
}

@Composable
private fun ActionRow(title: String, value: String, onChange: (String) -> Unit) {
    val c = T.c
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box {
            Btn(MouseTitles.action(value) + "  ▾") { open = true }
            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                for (a in mlabeler.app.state.MouseActions.all) {
                    androidx.compose.material3.DropdownMenuItem(
                        { Text((if (a == value) "✓  " else "     ") + MouseTitles.action(a), fontSize = 13.sp) },
                        onClick = { open = false; onChange(a) },
                    )
                }
            }
        }
    }
}
