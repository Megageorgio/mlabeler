package mlabeler.app.ui

import androidx.compose.foundation.background
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
    val list = Commands.all.filter { query.isBlank() || it.title().contains(query.trim(), ignoreCase = true) }
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

@Composable
fun SettingsDialog(app: AppState) {
    val c = T.c
    val s = app.settings
    Overlay({ app.showSettings = false; app.editor?.requestFocus?.invoke() }, 620) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.settings(), color = c.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconBtn(Icons.close, S.close()) { app.showSettings = false }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            SectionTitle(S.language())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
            }
            SectionTitle(S.theme())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val names = mapOf("modern-dark" to S.themeModernDark(), "modern-light" to S.themeModernLight(), "retro" to S.themeRetro(), "contrast" to S.themeContrast())
                for (t in Themes.all) Chip(names[t.id] ?: t.id, s.theme == t.id) { app.update { it.copy(theme = t.id) } }
            }
            SliderRow(S.interfaceScale(), s.scale, 0.8f..1.5f, "${(s.scale * 100).roundToInt()}%") { v -> app.update { it.copy(scale = (v * 20).roundToInt() / 20f) } }

            SectionTitle(S.view())
            Text(S.colors(), color = c.muted, fontSize = 12.sp)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.theme(), s.view.palette.isEmpty()) { app.update { it.copy(view = it.view.copy(palette = "")) } }
                for (p in Themes.palettes.keys) Chip(p, s.view.palette == p) { app.update { it.copy(view = it.view.copy(palette = p)) } }
            }
            SliderRow(S.brightness(), s.view.brightness, -0.5f..0.5f, "${(s.view.brightness * 100).roundToInt()}") { v -> app.update { it.copy(view = it.view.copy(brightness = v)) } }
            SliderRow(S.contrast(), s.view.contrast, 0.5f..3f, "${(s.view.contrast * 100).roundToInt()}%") { v -> app.update { it.copy(view = it.view.copy(contrast = v)) } }
            SliderRow(S.maxFrequency(), s.view.maxFreq, 2000f..16000f, "${s.view.maxFreq.roundToInt()}") { v -> app.update { it.copy(view = it.view.copy(maxFreq = (v / 500).roundToInt() * 500f)) } }

            SectionTitle(S.editing())
            SliderRow(S.nudgeStep(), s.edit.nudgeMs, 1f..50f, "${s.edit.nudgeMs.roundToInt()}") { v -> app.update { it.copy(edit = it.edit.copy(nudgeMs = v.roundToInt().toFloat())) } }
            SliderRow(S.minInterval(), s.edit.minIntervalMs, 0f..20f, "${s.edit.minIntervalMs.roundToInt()}") { v -> app.update { it.copy(edit = it.edit.copy(minIntervalMs = v.roundToInt().toFloat())) } }
            SwitchRow(S.saveOnSwitch(), s.edit.saveOnSwitch) { v -> app.update { it.copy(edit = it.edit.copy(saveOnSwitch = v)) } }
            Text(S.newFilesFormat(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in LabelFormat.entries) Chip(f.title, s.edit.newFormat == f) { app.update { it.copy(edit = it.edit.copy(newFormat = f)) } }
            }

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

            SectionTitle(S.shortcuts())
            for (cmd in Commands.all) {
                if (cmd.keys.isEmpty()) continue
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(cmd.title(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cmd.keys.joinToString("   ") { it.label() }, color = c.muted, fontSize = 12.sp)
                }
            }
            SectionTitle(S.about())
            Text(S.aboutText(), color = c.muted, fontSize = 13.sp)
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
