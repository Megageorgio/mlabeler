package mlabeler.app.ui

import androidx.compose.foundation.layout.height
import mlabeler.app.i18n.L
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.Lang
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EnvironmentEntry
import mlabeler.app.state.Environments
import mlabeler.app.theme.T

/** Shown once, on the first start: language and the work environment. */
@Composable
fun SetupDialog(app: AppState) {
    val c = T.c
    Overlay({ app.applyEnvironment("basic") }, 760) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("mLabeler", color = c.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                for ((code, name) in Lang.available) {
                    Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                }
            }
            Text(S.chooseEnvironment(), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp))
            Text(S.chooseEnvironmentHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            EnvironmentCards(app, Environments.builtIns(), current = null) { app.applyEnvironment(it.id) }
        }
    }
}

/** Environments as cards: name and what's in it; [onDelete] shows a remove button on the user's own ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentCards(
    app: AppState,
    entries: List<EnvironmentEntry>,
    current: String?,
    onDelete: ((EnvironmentEntry) -> Unit)? = null,
    onPick: (EnvironmentEntry) -> Unit,
) {
    val c = T.c
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = when {
            maxWidth < 420.dp -> 1
            maxWidth < 700.dp -> 2
            else -> 4
        }
        val w = (maxWidth - 10.dp * (cols - 1)) / cols - 1.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (e in entries) {
                val sel = e.id == current
                val shape = RoundedCornerShape(c.radius * 2)
                Column(
                    Modifier.width(w).clip(shape).background(if (sel) c.accent.copy(alpha = 0.14f) else c.panelAlt)
                        .border(if (sel) 2.dp else c.borderWidth, if (sel) c.accent else c.border, shape)
                        .clickable { onPick(e) }.padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, color = if (sel) c.accent else c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (!e.builtIn && onDelete != null) IconBtn(Icons.trash, S.removeFromList(), size = 24.dp) { onDelete(e) }
                    }
                    Text(e.description, color = c.muted, fontSize = 12.sp, minLines = 3, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                    // what it looks like instead of a list of words; the words are in the tooltip
                    Tip(EnvContents.of(e.env).joinToString("\n") { (k, v) -> "$k: $v" }) {
                        EnvPreview(e.env, Modifier.padding(top = 8.dp).fillMaxWidth().height(64.dp))
                    }
                }
            }
        }
    }
}

/** List, save, update and remove environments (settings page and View menu entry point). */
@Composable
fun EnvironmentsSection(app: AppState) {
    val c = T.c
    val s = app.settings
    val version = app.environmentsVersion
    val entries = remember(version) { Environments.all() }
    val current = entries.firstOrNull { it.id == s.environment }
    Text(S.environmentHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    Text(EnvContents.notIncluded(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    EnvironmentCards(app, entries, s.environment, onDelete = { app.deleteEnvironment(it.id) }) { app.applyEnvironment(it.id) }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current != null && !Environments.matches(s, current)) {
            if (!current.builtIn) Btn(S.environmentUpdate.format(current.title), primary = true) { app.saveEnvironment(current.title) }
            Btn(S.reset()) { app.applyEnvironment(current.id) }
        }
    }
    var name by remember { mutableStateOf("") }
    Text(S.environmentSaveAs(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(name, { name = it }, Modifier.weight(1f), placeholder = S.environmentName(), onDone = { app.saveEnvironment(name); name = "" })
        Btn(S.save(), enabled = name.isNotBlank()) { app.saveEnvironment(name); name = "" }
    }
    if (!Platform.isMobile) {
        Row(Modifier.padding(top = 8.dp)) {
            Btn(S.environmentsFolder()) {
                mlabeler.core.io.PlatformFs.mkdirs(Environments.dir())
                Platform.openInFileManager(Environments.dir())
            }
        }
    }
}

/** What an environment contains, as "part: value" lines shown on its card. */
object EnvContents {
    private val panels = L("Panels", "Панели")
    private val lanes = L("Lanes", "Полосы")
    private val toolbar = L("Toolbar", "Кнопки")
    private val bars = L("Bars", "Строки")
    private val tools = L("Mouse", "Мышь")
    private val files = L("files", "файлы")
    private val details = L("details", "подробности")
    private val none = L("none", "нет")
    private val wave = L("waveform", "волна")
    private val spec = L("spectrogram", "спектрограмма")
    private val pitch = L("pitch", "высота тона")
    private val power = L("loudness", "громкость")
    private val overlay = L("laid over each other", "друг поверх друга")
    private val big = L("big", "крупные")
    private val small = L("compact", "компактные")
    private val named = L("with names", "с подписями")
    private val menu = L("menu", "меню")
    private val status = L("status", "статус")
    private val withTools = L("cursor and tools", "курсор и инструменты")
    private val cursorOnly = L("cursor only", "только курсор")
    val notIncluded = L("Not part of an environment: theme, language, shortcuts, mouse buttons and labeling options.",
        "В среду не входят: тема, язык, сочетания клавиш, кнопки мыши и настройки разметки.")

    fun of(e: mlabeler.app.state.Environment): List<Pair<String, String>> {
        val l = e.layout
        val p = listOfNotNull(files().takeIf { l.showFiles }, details().takeIf { l.showInspector }).ifEmpty { listOf(none()) }
        val ln = listOfNotNull(wave().takeIf { l.showWaveform }, spec().takeIf { l.showSpectrogram }, pitch().takeIf { l.showPitch },
            power().takeIf { l.showPower }).joinToString(", ") + if (l.overlay) " — " + overlay() else ""
        val t = e.toolbar
        val tb = (listOf(if (t.big) big() else small()) + listOfNotNull(named().takeIf { t.labels })).joinToString(", ") + ": " +
            t.groups.joinToString(", ") { ToolLabels.group(it)().lowercase() }
        val b = listOfNotNull(menu().takeIf { e.menuBar && !Platform.isMobile }, status().takeIf { e.statusBar }).ifEmpty { listOf(none()) }
        return listOf(
            panels() to p.joinToString(", "),
            lanes() to ln,
            toolbar() to tb,
            bars() to b.joinToString(", "),
            tools() to if (e.tools) withTools() else cursorOnly(),
        )
    }
}

/** A small drawing of an environment: menu and button bars, side panels, lanes, status bar. */
@Composable
fun EnvPreview(e: mlabeler.app.state.Environment, modifier: Modifier) {
    val c = T.c
    androidx.compose.foundation.Canvas(modifier) {
        val px = density
        val w = size.width
        val h = size.height
        val line = c.border
        val fill = c.text.copy(alpha = 0.10f)
        val r = androidx.compose.ui.geometry.CornerRadius(3 * px)
        drawRoundRect(c.bg, size = size, cornerRadius = r)
        drawRoundRect(line, size = size, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(px))
        var top = 3 * px
        if (e.menuBar && !mlabeler.app.Platform.isMobile) {
            for (i in 0 until 4) drawRect(c.muted.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(5 * px + i * 11 * px, top + px), androidx.compose.ui.geometry.Size(8 * px, 2 * px))
            top += 5 * px
        }
        // the button bar: big buttons are taller, groups as little blocks
        val barH = if (e.toolbar.big) 9 * px else 6 * px
        val groups = e.toolbar.groups.size.coerceAtLeast(1)
        val gw = (w - 10 * px) / groups
        for (i in 0 until groups) drawRoundRect(c.accent.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(5 * px + i * gw, top), androidx.compose.ui.geometry.Size(gw - 2 * px, barH), androidx.compose.ui.geometry.CornerRadius(px))
        top += barH + 3 * px
        val bottom = h - (if (e.statusBar) 6 * px else 3 * px)
        if (e.statusBar) drawRect(c.muted.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(3 * px, h - 4 * px), androidx.compose.ui.geometry.Size(w - 6 * px, 1.5f * px))
        val l = e.layout
        var left = 3 * px
        var right = w - 3 * px
        val side = w * 0.2f
        if (l.showFiles) {
            val onRight = l.filesSide == "right"
            val x = if (onRight) right - side else left
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(side, bottom - top), r)
            for (i in 0 until 4) drawRect(c.muted.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(x + 3 * px, top + 4 * px + i * 5 * px), androidx.compose.ui.geometry.Size(side - 6 * px, 1.5f * px))
            if (onRight) right -= side + 2 * px else left += side + 2 * px
        }
        if (l.showInspector) {
            val onLeft = l.inspectorSide == "left"
            val x = if (onLeft) left else right - side
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Size(side, bottom - top), r)
            if (onLeft) left += side + 2 * px else right -= side + 2 * px
        }
        // lanes of the picture
        val lanes = buildList {
            if (l.showWaveform) add(c.wave)
            if (l.showSpectrogram) add(androidx.compose.ui.graphics.Color(0xFFE07A3F))
            if (l.showPitch && !l.pitchOverSpectrogram) add(androidx.compose.ui.graphics.Color(0xFF4FD1C5))
            if (l.showPower) add(c.muted)
        }
        val labelsH = 6 * px
        val areaB = bottom - labelsH - 2 * px
        if (l.overlay || lanes.isEmpty()) {
            drawRoundRect(androidx.compose.ui.graphics.Color(0xFFE07A3F).copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top), r)
            drawRect(c.wave.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(left, (top + bottom) / 2 - 2 * px), androidx.compose.ui.geometry.Size(right - left, 4 * px))
            drawRect(c.text.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(left, bottom - labelsH), androidx.compose.ui.geometry.Size(right - left, labelsH))
        } else {
            val each = (areaB - top) / lanes.size
            for ((i, col) in lanes.withIndex()) {
                drawRoundRect(col.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(left, top + i * each), androidx.compose.ui.geometry.Size(right - left, each - 1.5f * px), androidx.compose.ui.geometry.CornerRadius(px))
            }
            drawRect(c.text.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(left, bottom - labelsH), androidx.compose.ui.geometry.Size(right - left, labelsH))
        }
    }
}
