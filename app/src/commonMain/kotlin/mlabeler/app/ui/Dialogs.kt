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
            .windowInsetsPadding(WindowInsets.safeDrawing)
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

private enum class Section { General, View, Spectrogram, Editing, Checks, Toolkit, Keys, About }

@Composable
private fun sectionTitle(s: Section) = when (s) {
    Section.General -> S.general()
    Section.View -> S.view()
    Section.Spectrogram -> S.spectrogramSection()
    Section.Editing -> S.editing()
    Section.Checks -> S.checks()
    Section.Toolkit -> S.toolkit()
    Section.Keys -> S.shortcuts()
    Section.About -> S.about()
}

/** Settings split into pages: a list on the left on wide windows, tabs on top on narrow ones. */
@Composable
fun SettingsDialog(app: AppState) {
    val c = T.c
    var section by remember { mutableStateOf(Section.General) }
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
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        when (section) {
            Section.General -> {
                SectionTitle(S.language())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                }
                SectionTitle(S.files())
                SwitchRow(S.saveOnSwitch(), s.edit.saveOnSwitch) { v -> app.update { it.copy(edit = it.edit.copy(saveOnSwitch = v)) } }
                SliderRow(S.autosave(), s.edit.autosaveSeconds.toFloat(), 0f..300f, "${s.edit.autosaveSeconds}") { v -> app.update { it.copy(edit = it.edit.copy(autosaveSeconds = (v / 10).roundToInt() * 10)) } }
            }
            Section.View -> {
                SectionTitle(S.theme())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val names = mapOf("modern-dark" to S.themeModernDark(), "modern-light" to S.themeModernLight(), "retro" to S.themeRetro(), "contrast" to S.themeContrast())
                    for (t in Themes.all) Chip(names[t.id] ?: t.id, s.theme == t.id) { app.update { it.copy(theme = t.id) } }
                }
                SliderRow(S.interfaceScale(), s.scale, 0.8f..1.5f, "${(s.scale * 100).roundToInt()}%") { v -> app.update { it.copy(scale = (v * 20).roundToInt() / 20f) } }
                SectionTitle(S.view())
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
                SliderRow(S.brightness(), s.view.brightness, -0.5f..0.5f, "${(s.view.brightness * 100).roundToInt()}") { v -> app.update { it.copy(view = it.view.copy(brightness = v)) } }
                SliderRow(S.contrast(), s.view.contrast, 0.5f..3f, "${(s.view.contrast * 100).roundToInt()}%") { v -> app.update { it.copy(view = it.view.copy(contrast = v)) } }
                SliderRow(S.maxFrequency(), s.view.maxFreq, 2000f..16000f, "${s.view.maxFreq.roundToInt()}") { v -> app.update { it.copy(view = it.view.copy(maxFreq = (v / 500).roundToInt() * 500f)) } }
            }
            Section.Editing -> {
                SectionTitle(S.editing())
                SliderRow(S.nudgeStep(), s.edit.nudgeMs, 1f..50f, "${s.edit.nudgeMs.roundToInt()}") { v -> app.update { it.copy(edit = it.edit.copy(nudgeMs = v.roundToInt().toFloat())) } }
                SliderRow(S.minInterval(), s.edit.minIntervalMs, 0f..20f, "${s.edit.minIntervalMs.roundToInt()}") { v -> app.update { it.copy(edit = it.edit.copy(minIntervalMs = v.roundToInt().toFloat())) } }
                SwitchRow(S.ripple() + " — " + S.rippleHint(), s.edit.ripple) { v -> app.update { it.copy(edit = it.edit.copy(ripple = v)) } }
                SwitchRow(S.linked() + " — " + S.linkedHint(), s.edit.linked) { v -> app.update { it.copy(edit = it.edit.copy(linked = v)) } }
                SwitchRow(S.loop(), s.edit.loop) { v -> app.update { it.copy(edit = it.edit.copy(loop = v)) } }
                SliderRow(S.speedSetting(), s.edit.speed, 0.25f..1f, "${s.edit.speed}×") { v -> app.update { it.copy(edit = it.edit.copy(speed = (v * 20).roundToInt() / 20f)) } }
                SwitchRow(S.playOnDrag(), s.edit.playOnDrag) { v -> app.update { it.copy(edit = it.edit.copy(playOnDrag = v)) } }
                SwitchRow(S.otoLocked(), s.edit.otoLockedDrag) { v -> app.update { it.copy(edit = it.edit.copy(otoLockedDrag = v)) } }
            }
            Section.Checks -> {
                SectionTitle(S.checks())
                SliderRow(S.shortThreshold(), s.checks.minDurationMs.toFloat(), 0f..150f, "${s.checks.minDurationMs.roundToInt()}") { v ->
                    app.update { it.copy(checks = it.checks.copy(minDurationMs = v.roundToInt().toDouble())) }
                }
                var phonemes by remember { mutableStateOf(s.checks.phonemeSet.joinToString(" ")) }
                Text(S.phonemeSet(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Field(phonemes, { v ->
                    phonemes = v
                    app.update { it.copy(checks = it.checks.copy(phonemeSet = v.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }.toSet())) }
                }, Modifier.fillMaxWidth())
            }
            Section.Toolkit -> {
                SectionTitle(S.toolkit())
                Text(S.toolkitHint(), color = c.muted, fontSize = 12.sp)
                var url by remember { mutableStateOf(s.toolkit.url) }
                var token by remember { mutableStateOf(s.toolkit.token) }
                var check by remember { mutableStateOf("") }
                Text(S.toolkitUrl(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Field(url, { url = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(url = it.trim())) } }, Modifier.fillMaxWidth())
                Text(S.toolkitToken(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Field(token, { token = it; app.update { st -> st.copy(toolkit = st.toolkit.copy(token = it.trim())) } }, Modifier.fillMaxWidth())
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn(S.toolkitCheck()) {
                        check = "…"
                        scope.launch {
                            check = try {
                                mlabeler.app.toolkit.ToolkitClient(url, token).health(); S.toolkitOk()
                            } catch (e: Exception) { e.message ?: "error" }
                        }
                    }
                    Text(check, color = c.muted, fontSize = 12.sp)
                }
            }
            Section.Keys -> KeymapPage(app)
            Section.About -> {
                SectionTitle(S.about())
                Text("mLabeler 0.1", color = c.text, fontSize = 15.sp)
                Text(S.aboutText(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row {
            Text(title, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(label, color = c.muted, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
            modifier = Modifier.height(32.dp),
        )
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
