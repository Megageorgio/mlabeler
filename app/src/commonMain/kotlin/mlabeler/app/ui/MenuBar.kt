package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.app.state.ToolbarGroups
import mlabeler.app.state.togglePanel

sealed interface MenuEntry
class MItem(
    val title: String,
    val keys: String = "",
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val action: () -> Unit,
) : MenuEntry
data object MSep : MenuEntry
class MSub(val title: String, val entries: List<MenuEntry>) : MenuEntry

private fun item(cmd: Command, ed: EditorState, app: AppState, checked: Boolean? = null, title: String = cmd.title()) =
    MItem(title, cmd.keyLabel, checked, cmd.enabled(ed)) { cmd.run(ed, app) }

object MenuTitles {
    val file = L("File", "Файл")
    val edit = L("Edit", "Правка")
    val view = L("View", "Вид")
    val go = L("Go", "Переход")
    val tools = L("Tools", "Инструменты")
    val help = L("Help", "Справка")
    val closeFolder = L("Close folder", "Закрыть папку")
    val openFolder = L("Open folder…", "Открыть папку…")
    val showInFolder = L("Show in file manager", "Показать в проводнике")
    val panels = L("Panels", "Панели")
    val filesPanel = L("Files and entries", "Файлы и записи")
    val detailsPanel = L("Details", "Подробности")
    val entriesPanel = L("Entries", "Записи")
    val recent = L("Open recent", "Открыть недавние")
    val scrollbar = L("Scroll bar under the picture", "Полоса прокрутки под картинкой")
    val otoHeader = L("Large alias over the picture", "Крупный псевдоним над картинкой")
    val followPlay = L("View during playback", "Вид при воспроизведении")
    val entriesSeparate = L("Entries as a panel of their own", "Записи отдельной панелью")
    val entriesRight = L("Entries panel on the right", "Панель записей справа")
    val menuBar = L("Menu bar", "Строка меню")
    val statusBar = L("Status bar", "Строка состояния")
    val toolbar = L("Toolbar", "Панель инструментов")
    val buttonLabels = L("Names under buttons", "Подписи под кнопками")
    val bigButtons = L("Big buttons", "Крупные кнопки")
    val audio = L("Audio", "Звук")
    val interfaceSet = L("Interface", "Интерфейс")
    val filesRight = L("Files panel on the right", "Панель файлов справа")
    val detailsLeft = L("Details panel on the left", "Подробности слева")
    val arrange = L("Arrange panels and lanes (move, hide, reorder)", "Расставить панели и полосы (перенос, скрытие, порядок)")
    val specFirst = L("Spectrogram above the waveform", "Спектрограмма над волной")
    val labelsOnTop = L("Labels above the audio", "Разметка над звуком")
    val overlay = L("Labels over the audio (one picture)", "Разметка поверх звука (одна картинка)")
    val keys = L("Keyboard shortcuts…", "Горячие клавиши…")
    val about = L("About", "О программе")
    val notes = L("Notes", "Ноты")
    val importExport = L("Import and export", "Импорт и экспорт")
    val recording = L("Recording", "Запись")
    val markersAtCursor = L("Marker at the cursor", "Маркер на курсор")
    val boundaries = L("Boundaries", "Границы")
    val lanes = L("Lanes", "Полосы")
    val labelsView = L("Labels on the picture", "Разметка на картинке")
    val withinFile = L("Within the file", "Внутри файла")
    val soundTools = L("Sound", "Звук")
    val datasetTools = L("Checking the dataset", "Проверка датасета")
    val pluginsMenu = L("Plugins", "Плагины")
}

/** The whole menu tree; the same on the menu bar, behind the ☰ button and on phones. */
@Composable
fun menus(app: AppState, ed: EditorState): List<Pair<String, List<MenuEntry>>> {
    val s = app.settings
    val l = s.layout
    val oto = ed.mode == Mode.Oto
    // long lists are grouped into submenus: the top level keeps what is used all the time
    val file = buildList {
        add(MItem(MenuTitles.openFolder(), Commands.openFolder.keyLabel) {
            app.pickFolder(S.openFolder()) { app.leaveFolderThen { app.openFolder(it) } }
        })
        val recent = s.recent.filter { it != ed.workspace.root }.take(12)
        if (recent.isNotEmpty()) add(MSub(MenuTitles.recent(), recent.map { p ->
            MItem(mlabeler.core.io.Paths.name(p) + "  —  " + p) { app.leaveFolderThen { app.openFolder(p) } }
        }))
        add(MItem(MenuTitles.closeFolder()) { app.leaveFolderThen { app.closeFolder() } })
        if (!Platform.isMobile) add(MItem(MenuTitles.showInFolder()) { Platform.openInFileManager(ed.workspace.root) })
        add(MSep)
        add(item(Commands.save, ed, app))
        add(item(Commands.reloadAudio, ed, app))
        add(item(Commands.workspace, ed, app))
        add(MSep)
        add(MSub(MenuTitles.importExport(), buildList {
            add(item(Commands.importLbp, ed, app))
            if (!oto) add(item(Commands.exportDs, ed, app))
        }))
        add(MSub(MenuTitles.recording(), listOf(item(Commands.record, ed, app), item(Commands.karaoke, ed, app))))
        add(MSep)
        add(item(Commands.settings, ed, app, title = S.settings()))
    }
    val edit = buildList {
        add(item(Commands.undo, ed, app))
        add(item(Commands.redo, ed, app))
        add(item(Commands.deselect, ed, app))
        add(MSep)
        if (oto) {
            add(item(Commands.otoAdd, ed, app))
            add(item(Commands.otoDuplicate, ed, app))
            add(item(Commands.otoDelete, ed, app))
            add(MSub(MenuTitles.markersAtCursor(), listOf(Commands.otoLeft, Commands.otoOverlap, Commands.otoPreu, Commands.otoCons, Commands.otoRight).map { item(it, ed, app) }))
            add(item(Commands.otoLock, ed, app, checked = s.edit.otoLockedDrag))
            add(MSep)
            add(item(Commands.batchRename, ed, app))
        } else {
            add(item(Commands.split, ed, app))
            add(item(Commands.merge, ed, app))
            add(item(Commands.delete, ed, app))
            add(item(Commands.rename, ed, app))
            add(MSub(MenuTitles.boundaries(), listOf(
                item(Commands.setLeft, ed, app), item(Commands.setRight, ed, app), MSep,
                item(Commands.nudgeLeft, ed, app), item(Commands.nudgeRight, ed, app), MSep,
                item(Commands.ripple, ed, app, checked = s.edit.ripple), item(Commands.linked, ed, app, checked = s.edit.linked),
                item(Commands.playOnDrag, ed, app, checked = s.edit.playOnDrag),
            )))
            add(MSep)
            add(MSub(MenuTitles.notes(), listOf(
                item(Commands.groupPhonemes, ed, app), item(Commands.notesFromGroups, ed, app), MSep,
                item(Commands.pitchUp, ed, app), item(Commands.pitchDown, ed, app), item(Commands.notesFromAudio, ed, app),
                MSep, item(Commands.midiIn, ed, app), item(Commands.midiOut, ed, app),
                MSep, item(Commands.resynthWorld, ed, app), item(Commands.resynthNsf, ed, app),
            )))
            add(item(Commands.fillQueue, ed, app))
            add(item(Commands.batchRename, ed, app))
        }
        add(MSep)
        add(item(Commands.done, ed, app))
        add(item(Commands.star, ed, app))
    }
    fun toggle(title: String, on: Boolean, keys: String = "", f: (mlabeler.app.state.AppSettings) -> mlabeler.app.state.AppSettings) =
        MItem(title, keys, on) { app.update(f) }
    val toolbar = buildList<MenuEntry> {
        for (g in s.toolbar.fullOrder()) {
            val on = g in s.toolbar.groups
            add(toggle(ToolLabels.group(g)(), on) { st -> st.copy(toolbar = st.toolbar.toggled(g, !on)) })
        }
        add(MSep)
        add(toggle(MenuTitles.buttonLabels(), s.toolbar.labels) { it.copy(toolbar = it.toolbar.copy(labels = !it.toolbar.labels)) })
        add(toggle(MenuTitles.bigButtons(), s.toolbar.big) { it.copy(toolbar = it.toolbar.copy(big = !it.toolbar.big)) })
    }
    val view = buildList {
        add(MSub(MenuTitles.panels(), buildList {
            add(item(Commands.files, ed, app, checked = l.showFiles, title = MenuTitles.filesPanel()))
            if (l.entriesSeparate) add(toggle(MenuTitles.entriesPanel(), l.showEntries) { it.copy(layout = it.layout.togglePanel("entries")) })
            add(item(Commands.inspector, ed, app, checked = l.showInspector, title = MenuTitles.detailsPanel()))
            add(toggle(notepadTitle(), s.layout.showNotepad) { it.copy(layout = it.layout.copy(showNotepad = !it.layout.showNotepad)) })
            // which side each panel is on, and entries as a panel of their own, are set on the panels themselves
            add(MItem(MenuTitles.arrange(), checked = app.arrangePanels) { app.arrangePanels = !app.arrangePanels })
            add(MSep)
            if (!Platform.isMobile) add(toggle(MenuTitles.menuBar(), s.menuBar) { it.copy(menuBar = !it.menuBar) })
            add(toggle(MenuTitles.statusBar(), s.statusBar) { it.copy(statusBar = !it.statusBar) })
            add(toggle(MenuTitles.scrollbar(), s.layout.scrollbar) { it.copy(layout = it.layout.copy(scrollbar = !it.layout.scrollbar)) })
            add(toggle(Commands.lockLayout.title(), s.layout.locked) { it.copy(layout = it.layout.copy(locked = !it.layout.locked)) })
        }))
        add(MSub(MenuTitles.toolbar(), toolbar))
        add(MSub(S.environment(), buildList {
            for (e in mlabeler.app.state.Environments.all()) add(MItem(e.title, checked = e.id == s.environment) { app.applyEnvironment(e.id) })
            add(MSep)
            add(MItem(S.environmentSaveAs()) { app.settingsPage = "interface"; app.showSettings = true })
        }))
        add(MSep)
        add(MSub(MenuTitles.lanes(), listOf(
            item(Commands.wave, ed, app, checked = l.showWaveform),
            item(Commands.spectrogram, ed, app, checked = l.showSpectrogram),
            item(Commands.pitchLane, ed, app, checked = l.showPitch),
            item(Commands.powerLane, ed, app, checked = l.showPower),
            item(Commands.formants, ed, app, checked = l.showFormants),
            MSep,
            toggle(MenuTitles.specFirst(), l.spectrogramFirst) { it.copy(layout = it.layout.copy(spectrogramFirst = !it.layout.spectrogramFirst, laneOrder = emptyList())) },
        )))
        add(MSub(MenuTitles.labelsView(), buildList {
            add(item(Commands.overlay, ed, app, checked = l.overlay, title = MenuTitles.overlay()))
            add(item(Commands.tiersOnTop, ed, app, checked = l.tiersOnTop, title = MenuTitles.labelsOnTop()))
            add(item(Commands.namesOnAudio, ed, app, checked = l.namesOnAudio))
            if (ed.mode == mlabeler.app.state.Mode.Oto) add(toggle(MenuTitles.otoHeader(), s.layout.otoHeader) { it.copy(layout = it.layout.copy(otoHeader = !it.layout.otoHeader)) })
            add(MSep)
            add(item(Commands.labelsBigger, ed, app))
            add(item(Commands.labelsSmaller, ed, app))
        }))
        add(MSub(MenuTitles.followPlay(), listOf(
            toggle(PlayTitles.followOff(), s.edit.follow == "off") { it.copy(edit = it.edit.copy(follow = "off")) },
            toggle(PlayTitles.followPage(), s.edit.follow == "page") { it.copy(edit = it.edit.copy(follow = "page")) },
            toggle(PlayTitles.followKeep(), s.edit.follow == "keep") { it.copy(edit = it.edit.copy(follow = "keep")) },
        )))
        add(MSep)
        add(item(Commands.zoomIn, ed, app))
        add(item(Commands.zoomOut, ed, app))
        add(item(Commands.zoomFit, ed, app))
        add(item(Commands.zoomSel, ed, app))
    }
    val go = buildList {
        add(item(Commands.togglePlay, ed, app))
        add(item(Commands.playFrom, ed, app))
        add(item(Commands.loop, ed, app, checked = s.edit.loop))
        add(item(Commands.speed, ed, app))
        add(MSep)
        add(item(Commands.prevFile, ed, app))
        add(item(Commands.nextFile, ed, app))
        add(MSep)
        if (oto) {
            add(item(Commands.prevEntry, ed, app))
            add(item(Commands.nextEntry, ed, app))
        } else {
            add(MSub(MenuTitles.withinFile(), listOf(
                item(Commands.prevBound, ed, app), item(Commands.nextBound, ed, app),
                item(Commands.prevInterval, ed, app), item(Commands.nextInterval, ed, app),
                item(Commands.tierUp, ed, app), item(Commands.tierDown, ed, app), MSep,
                item(Commands.home, ed, app), item(Commands.end, ed, app),
            )))
            add(MSep)
            add(item(Commands.reviewNext, ed, app))
            add(item(Commands.reviewPrev, ed, app))
        }
        if (oto) { add(MSep); add(item(Commands.home, ed, app)); add(item(Commands.end, ed, app)) }
    }
    val tools = buildList {
        if (oto) add(item(Commands.autoOto, ed, app)) else add(item(Commands.autolabel, ed, app))
        add(MSub(MenuTitles.soundTools(), buildList {
            if (!oto) add(item(Commands.soundMode, ed, app, checked = ed.soundMode))
            add(item(Commands.cleanup, ed, app))
            add(item(Commands.mute, ed, app))
            if (!oto) add(item(Commands.cutAudio, ed, app))
            add(item(Commands.normalize, ed, app))
            if (!oto) add(item(Commands.trimSilence, ed, app))
            if (!oto) add(item(Commands.segments, ed, app))
        }))
        add(MSub(MenuTitles.datasetTools(), buildList {
            add(item(Commands.soundCheck, ed, app))
            if (!oto) add(item(Commands.summary, ed, app))
            if (!oto) add(item(Commands.phonemeCuts, ed, app))
        }))
        val slots = listOf(Commands.slot1, Commands.slot2, Commands.slot3, Commands.slot4)
        val named = slots.withIndex().mapNotNull { (k, c) -> s.pluginSlots.getOrNull(k)?.takeIf { it.isNotEmpty() }?.let { item(c, ed, app, title = it) } }
        if (named.isEmpty()) add(item(Commands.plugins, ed, app))
        else add(MSub(MenuTitles.pluginsMenu(), listOf(item(Commands.plugins, ed, app), MSep) + named))
        add(MSep)
        add(item(Commands.palette, ed, app))
        add(MItem(MenuTitles.keys()) { app.settingsPage = "keys"; app.showSettings = true })
    }
    val help = listOf(
        item(Commands.help, ed, app),
        item(Commands.tips, ed, app),
        MItem(MenuTitles.about()) { app.settingsPage = "about"; app.showSettings = true },
    )
    return listOf(
        MenuTitles.file() to file,
        MenuTitles.edit() to edit,
        MenuTitles.view() to view,
        MenuTitles.go() to go,
        MenuTitles.tools() to tools,
        MenuTitles.help() to help,
    )
}

/** Items of a dropdown, with check marks, key hints and submenus (opened by hover or click). */
@Composable
fun MenuItems(entries: List<MenuEntry>, close: () -> Unit) {
    val c = mlabeler.app.theme.T.c
    val h = if (Platform.isMobile) 44.dp else 28.dp
    var openSub by remember { mutableStateOf(-1) }
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 220.dp)) {
        for ((k, e) in entries.withIndex()) when (e) {
            is MSep -> Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().height(c.borderWidth).background(c.border))
            is MItem -> MenuRow(h, e.enabled, onHover = { openSub = -1 }, onClick = { close(); e.action() }) {
                Text(if (e.checked == true) "✓" else "", color = c.accent, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                Text(e.title, fontSize = 13.sp, color = if (e.enabled) c.text else c.muted.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                Spacer(Modifier.widthIn(min = 32.dp).weight(1f))
                Text(e.keys, fontSize = 12.sp, color = c.muted, maxLines = 1, softWrap = false)
            }
            is MSub -> {
                var width by remember { mutableStateOf(0) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(Modifier.onSizeChanged { width = it.width }) {
                    MenuRow(h, true, active = openSub == k, onHover = { openSub = k }, onClick = { openSub = if (openSub == k) -1 else k }) {
                        Spacer(Modifier.width(20.dp))
                        Text(e.title, fontSize = 13.sp, color = c.text, maxLines = 1, softWrap = false)
                        Spacer(Modifier.widthIn(min = 32.dp).weight(1f))
                        Text("›", fontSize = 14.sp, color = c.muted)
                    }
                    MenuPopup(openSub == k, side = true) {
                        MenuItems(e.entries) { openSub = -1; close() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    h: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    active: Boolean = false,
    onHover: () -> Unit,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = mlabeler.app.theme.T.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier.fillMaxWidth().height(h).padding(horizontal = 4.dp).clip(RoundedCornerShape(c.radius))
            .background(if ((hovered && enabled) || active) c.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Transparent layer under an open menu: a press anywhere else closes the menu and goes no further,
 * Esc closes it. [onMove]/[onPress] get window coordinates; returning true from [onPress] keeps the menu open.
 */
@Composable
fun MenuCatcher(onDismiss: () -> Unit, onMove: (androidx.compose.ui.geometry.Offset) -> Unit = {}, onPress: (androidx.compose.ui.geometry.Offset) -> Boolean = { false }) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset.Zero
        },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = false),
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            Modifier.size(with(density) { win.width.toDp() }, with(density) { win.height.toDp() }).focusRequester(focus).focusable()
                .onKeyEvent { e -> if (e.key == androidx.compose.ui.input.key.Key.Escape) { onDismiss(); true } else false }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull()?.position ?: continue
                            when (ev.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Move -> onMove(p)
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> if (!onPress(p)) onDismiss()
                                else -> Unit
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}

/** File / Edit / View … across the top, for computers. Moving the mouse along it switches open menus. */
@Composable
fun MenuBar(app: AppState, ed: EditorState) {
    val c = mlabeler.app.theme.T.c
    var open by remember { mutableStateOf<Int?>(null) }
    val tree = menus(app, ed)
    val bounds = remember { mutableMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    fun titleAt(p: androidx.compose.ui.geometry.Offset) = bounds.entries.firstOrNull { it.value.contains(p) }?.key
    if (open != null) {
        MenuCatcher(
            onDismiss = { open = null },
            onMove = { p -> titleAt(p)?.let { open = it } },
            onPress = { p -> val t = titleAt(p); if (t != null && t != open) { open = t; true } else false },
        )
    }
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(c.panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((k, pair) in tree.withIndex()) {
            val (title, entries) = pair
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Box(Modifier.onGloballyPositioned { bounds[k] = it.boundsInWindow() }) {
                Text(
                    title, fontSize = 13.sp, color = c.text,
                    modifier = Modifier.clip(RoundedCornerShape(c.radius))
                        .background(if (open == k || hovered) c.text.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                        .hoverable(source)
                        .clickable(interactionSource = source, indication = null) { open = if (open == k) null else k }
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
                MenuPopup(open == k) {
                    MenuItems(entries) { open = null }
                }
            }
        }
    }
}

/** ☰ button with the same menus as submenus (tablets, or when the menu bar is hidden). */
@Composable
fun MenuButton(app: AppState, ed: EditorState, extra: List<MenuEntry> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    val tree = menus(app, ed)
    if (open) MenuCatcher({ open = false })
    Box {
        IconBtn(Icons.menu, S.more()) { open = true }
        MenuPopup(open) {
            MenuItems(extra + (if (extra.isEmpty()) emptyList() else listOf(MSep)) + tree.map { (t, e) -> MSub(t, e) }) { open = false }
        }
    }
}

/** Name of the current work environment at the top right; opens the list of environments. */
@Composable
fun EnvironmentButton(app: AppState) {
    val c = mlabeler.app.theme.T.c
    var open by remember { mutableStateOf(false) }
    val s = app.settings
    val version = app.environmentsVersion
    val all = remember(version) { mlabeler.app.state.Environments.all() }
    val cur = all.firstOrNull { it.id == s.environment }
    val changed = cur != null && !mlabeler.app.state.Environments.matches(s, cur)
    if (open) MenuCatcher({ open = false })
    Box {
        Tip(S.environment()) {
            Text(
                (cur?.title ?: S.environment()) + if (changed) " •" else "",
                fontSize = 12.sp, color = c.muted, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(c.radius)).border(c.borderWidth, c.border, RoundedCornerShape(c.radius))
                    .clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        MenuPopup(open) {
            MenuItems(buildList {
                for (e in all) add(MItem(e.title, checked = e.id == s.environment) { app.applyEnvironment(e.id) })
                add(MSep)
                if (changed && cur != null) add(MItem(S.reset() + " (" + cur.title + ")") { app.applyEnvironment(cur.id) })
                add(MItem(S.environmentSaveAs()) { app.settingsPage = "interface"; app.showSettings = true })
            }) { open = false }
        }
    }
}
