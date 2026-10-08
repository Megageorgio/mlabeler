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
import mlabeler.app.theme.RoundedCornerShape
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

private object SetupTitles {
    val welcome = L("Welcome", "Добро пожаловать")
    val intro = L("A few basic choices first. Everything here can be changed later in Settings.",
        "Сначала несколько основных настроек. Всё это можно поменять потом в Настройках.")
    val language = L("Language of the program", "Язык программы")
    val theme = L("Look", "Внешний вид")
    val themeHint = L("Colours of the whole program. Dark is easier on the eyes in the evening.", "Цвета всей программы. Тёмная тема спокойнее для глаз вечером.")
    val size = L("Size of text and buttons", "Размер текста и кнопок")
    val sizeHint = L("Pick what is comfortable to read. On a small screen take a smaller size.", "Выберите, что удобно читать. На маленьком экране — поменьше.")
    val updates = L("New versions", "Новые версии")
    val updatesHint = L("At start the program can look on the internet for a newer version and ask whether to download it. Nothing is installed without asking.",
        "При запуске программа может проверить в интернете, есть ли новая версия, и спросить, скачать ли её. Без вопроса ничего не устанавливается.")
    val toolkit = L("Automatic labelling (toolkit)", "Автоматическая разметка (тулкит)")
    val toolkitHint = L("A separate helper program does the automatic labelling. When it is installed, it can start by itself whenever it is needed and stop once no program uses it.",
        "Автоматическую разметку делает отдельная программа-помощник. Если она установлена, она может запускаться сама, когда нужна, и закрываться, когда больше не используется.")
    val next = L("Next", "Дальше")
    val back = L("Back", "Назад")
}

/** Shown once, on the first start: the main settings (each explained), then the work environment. */
@Composable
fun SetupDialog(app: AppState) {
    val c = T.c
    var step by remember { mutableStateOf(0) }
    val s = app.settings
    Overlay({ app.applyEnvironment("basic") }, 760) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("mLabeler", color = c.text, fontSize = 20.sp)
            if (step == 0) {
                Text(SetupTitles.welcome(), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                Text(SetupTitles.intro(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))

                SectionTitle(SetupTitles.language())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                }

                SectionTitle(SetupTitles.theme())
                Text(SetupTitles.themeHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                val names = mapOf("modern-dark" to S.themeModernDark(), "modern-light" to S.themeModernLight(), "retro" to S.themeRetro(),
                    "retro-fairy" to S.themeFairy(), "contrast" to S.themeContrast())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in mlabeler.app.theme.Themes.builtIn) ThemeSwatch(t, names[t.id] ?: t.id, s.theme == t.id) { app.update { it.copy(theme = t.id) } }
                }

                SectionTitle(SetupTitles.size())
                Text(SetupTitles.sizeHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in listOf(0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f)) Chip("${(v * 100).toInt()}%", kotlin.math.abs(s.scale - v) < 0.01f) { app.update { it.copy(scale = v) } }
                }

                SectionTitle(SetupTitles.updates())
                Text(SetupTitles.updatesHint(), color = c.muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Toggle(s.updates.checkOnStart, { v -> app.update { it.copy(updates = it.updates.copy(checkOnStart = v)) } })
                    Text(UpdateTitles.checkOnStart(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
                if (s.updates.checkOnStart) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (ch in listOf("stable", "beta", "alpha")) Chip(UpdateTitles.channelName(ch), s.updates.channel == ch) {
                            app.update { it.copy(updates = it.updates.copy(channel = ch)) }
                        }
                    }
                    Text(UpdateTitles.channelHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }

                if (!Platform.isMobile) {
                    SectionTitle(SetupTitles.toolkit())
                    Text(SetupTitles.toolkitHint(), color = c.muted, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Toggle(s.toolkit.autoStart, { v -> app.update { it.copy(toolkit = it.toolkit.copy(autoStart = v)) } })
                        Text(S.toolkitAutoStart(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                    Btn(SetupTitles.next(), primary = true) { step = 1 }
                }
            } else {
                Text(S.chooseEnvironment(), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                Text(S.chooseEnvironmentHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                EnvironmentCards(app, Environments.builtIns(), current = null) { app.applyEnvironment(it.id); app.updater.checkAtStart() }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) { Btn(SetupTitles.back()) { step = 0 } }
            }
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
