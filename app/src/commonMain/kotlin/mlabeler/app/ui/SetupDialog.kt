package mlabeler.app.ui

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
                    if (e.description.isNotEmpty()) Text(e.description, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for ((k, v) in EnvContents.of(e.env)) Row {
                            Text(k, color = c.muted, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                            Text(v, color = c.text, fontSize = 11.sp)
                        }
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
