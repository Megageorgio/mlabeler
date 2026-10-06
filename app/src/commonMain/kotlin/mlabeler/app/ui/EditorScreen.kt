package mlabeler.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.app.state.panelsOn
import mlabeler.app.state.sideVisible
import mlabeler.app.theme.T
import mlabeler.core.io.Paths

enum class WidthClass { Compact, Medium, Expanded }

fun widthClass(width: Dp) = when {
    width < 700.dp -> WidthClass.Compact
    width < 1100.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

@Composable
fun EditorScreen(app: AppState, ed: EditorState) {
    val c = T.c
    val focus = remember { FocusRequester() }
    val v = app.settings.view
    LaunchedEffect(v.windowMs, v.hopMs, v.bands, v.minDb, v.maxDb) {
        // wait until the slider is let go
        kotlinx.coroutines.delay(400)
        if (ed.specNeedsUpdate()) ed.recomputeSpectrogram()
    }
    // speed or loop changed while playing: apply at once
    LaunchedEffect(app.settings.edit.speed, app.settings.edit.loop) { ed.playbackSettingsChanged() }
    LaunchedEffect(ed) {
        ed.requestFocus = { runCatching { focus.requestFocus() } }
        focus.requestFocus()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg)
            .windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || ed.editingText != null) return@onKeyEvent false
                // typing in a field: only shortcuts with Ctrl/Cmd work
                if (TextFocus.active && !e.isCtrlPressed && !e.isMetaPressed) return@onKeyEvent false
                if (ed.toolkitBusy != null) {
                    // nothing is edited while autolabel works; Esc stops it
                    if (e.key == androidx.compose.ui.input.key.Key.Escape) ed.cancelToolkit()
                    return@onKeyEvent true
                }
                val cmd = Commands.find(e, ed.mode) ?: return@onKeyEvent false
                if (!cmd.enabled(ed)) return@onKeyEvent true
                cmd.run(ed, app)
                true
            }
    ) {
        val wc = widthClass(maxWidth)
        when (wc) {
            WidthClass.Compact -> CompactEditor(app, ed)
            else -> WideEditor(app, ed, wc)
        }
        AutolabelBusy(ed)
        MessageToast(app, if (wc == WidthClass.Compact) 84.dp else 40.dp)
    }
}

@Composable
private fun WideEditor(app: AppState, ed: EditorState, wc: WidthClass) {
    val c = T.c
    val s = app.settings
    val l = s.layout
    var overlayDetails by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (s.menuBar && !Platform.isMobile) {
            MenuBar(app, ed)
            Divider()
        }
        TopBar(app, ed, wc, overlayDetails) { overlayDetails = !overlayDetails }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // panels go to the side the user put them on; two on one side share it as tabs
            val left = mutableListOf<SidePanelId>()
            val right = mutableListOf<SidePanelId>()
            if (l.showFiles) (if (l.filesSide == "right") right else left) += SidePanelId.Files
            if (l.showInspector && wc == WidthClass.Expanded) (if (l.inspectorSide == "left") left else right) += SidePanelId.Details
            if (l.leftCollapsed) left.clear()
            if (l.rightCollapsed) right.clear()
            Row(Modifier.fillMaxSize()) {
                if (left.isNotEmpty()) {
                    PanelStack(app, ed, left, Modifier.width(l.filesWidth.coerceIn(180f, 520f).dp).fillMaxHeight())
                    VSplitter { d -> app.update { it.copy(layout = it.layout.copy(filesWidth = (it.layout.filesWidth + d).coerceIn(180f, 520f))) } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    EditorBody(app, ed)
                }
                if (right.isNotEmpty()) {
                    VSplitter { d -> app.update { it.copy(layout = it.layout.copy(inspectorWidth = (it.layout.inspectorWidth - d).coerceIn(180f, 520f))) } }
                    PanelStack(app, ed, right, Modifier.width(l.inspectorWidth.coerceIn(180f, 520f).dp).fillMaxHeight())
                }
            }
            // medium width: details slide over the timeline
            if (wc == WidthClass.Medium) {
                androidx.compose.animation.AnimatedVisibility(
                    overlayDetails, modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
                ) {
                    Row(Modifier.fillMaxHeight()) {
                        Divider(vertical = true)
                        Inspector(ed, Modifier.width(300.dp).fillMaxHeight())
                    }
                }
            }
        }
        if (s.statusBar) {
            Divider()
            StatusBar(app, ed)
        }
    }
}

@Composable
private fun EditorBody(app: AppState, ed: EditorState) {
    val c = T.c
    Column(Modifier.fillMaxSize()) {
    if (ed.mode != Mode.Oto) ModelResultsBar(ed)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            ed.loadError != null -> EmptyNote(ed.loadError ?: "")
            ed.item == null -> EmptyNote(if (ed.items.isEmpty()) S.noFiles() else "")
            else -> Timeline(ed, app.settings.layout, app.settings.view, { nl -> app.update { it.copy(layout = nl) } }, Modifier.fillMaxSize())
        }
        if (ed.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
            }
        }
    }
    }
}

@Composable
private fun TopBar(app: AppState, ed: EditorState, wc: WidthClass, overlayDetails: Boolean, toggleOverlay: () -> Unit) {
    val c = T.c
    val s = app.settings
    val item = ed.item
    // left: files and the title; middle: tools, scrolling when they do not fit; right: always visible
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 44.dp else 46.dp).background(c.panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // folds the left side away; only there when some panel lives on the left
        if (s.layout.panelsOn("left").isNotEmpty()) IconBtn(Icons.panelLeft, Commands.leftSide.title(), active = s.layout.sideVisible("left")) { Commands.leftSide.run(ed, app) }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.widthIn(min = 60.dp, max = 260.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item?.let { Paths.name(it.audioPath) } ?: Paths.name(ed.workspace.root),
                    color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (ed.dirty) Text(" •", color = c.accent, fontSize = 14.sp)
            }
            // folder name and what is labelled there; a click opens the folder settings
            Text(
                Paths.name(ed.workspace.root) + "  ·  " + kindTitle(ed.mode),
                color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { app.showWorkspace = true },
            )
        }
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            ToolbarGroupsRow(app, ed)
        }
        Sep()
        if (wc == WidthClass.Expanded) {
            EnvironmentButton(app)
            Spacer(Modifier.width(4.dp))
        }
        IconBtn(Icons.settings, S.settings(), Commands.settings.keyLabel) { app.showSettings = true }
        if (!s.menuBar || Platform.isMobile) MenuButton(app, ed)
        if (wc == WidthClass.Medium) {
            IconBtn(Icons.panelRight, S.toggleInspector(), active = overlayDetails) { toggleOverlay() }
        } else {
            if (s.layout.panelsOn("right").isNotEmpty()) IconBtn(Icons.panelRight, Commands.rightSide.title(), active = s.layout.sideVisible("right")) { Commands.rightSide.run(ed, app) }
        }
    }
}

@Composable
private fun Sep() {
    val c = T.c
    Box(Modifier.padding(horizontal = 4.dp).width(c.borderWidth).height(20.dp).background(c.border))
}

@Composable
private fun TextIcon(text: String) {
    Text(text, color = T.c.muted, fontSize = 12.sp)
}

@Composable
private fun StatusBar(app: AppState, ed: EditorState) {
    val c = T.c
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(c.panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val cur = ed.playhead ?: ed.cursor
        StatusText(cur?.let { formatTime(it) } ?: "–", Modifier.width(80.dp))
        ed.range?.let { (a, b) -> StatusText("${formatTime(a)} – ${formatTime(b)}  (${formatMs(b - a)})") }
        Spacer(Modifier.weight(1f))
        Text(Commands.help.title() + " · F1", color = c.muted, fontSize = 12.sp, modifier = Modifier.clickable { app.showHelp = true })
        val spec = ed.spectrogram
        if (ed.audio != null && (spec == null || spec.ready < spec.frames)) StatusText(S.analysing())
        ed.toolkitBusy?.let { b ->
            StatusText(S.toolkit() + ": " + b, color = c.accent)
            Text("×", color = c.muted, fontSize = 14.sp, modifier = Modifier.clickable { ed.cancelToolkit() })
        }
        if (ed.problems.isNotEmpty()) StatusText("⚠ ${ed.problems.size}", color = c.warn)
        StatusText("${(ed.visibleDuration).let { if (it < 10) ((it * 100).toLong() / 100.0).toString() else it.toLong().toString() }} s")
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = T.c.muted) {
    Text(text, color = color, fontSize = 12.sp, maxLines = 1, modifier = modifier)
}

// ---------------- phones ----------------

@Composable
private fun CompactEditor(app: AppState, ed: EditorState) {
    val c = T.c
    var showFiles by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(c.panel).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.folder, S.files()) { showFiles = true }
                Column(Modifier.weight(1f).clickable { showFiles = true }.padding(horizontal = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ed.item?.let { Paths.name(it.audioPath) } ?: "", color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (ed.dirty) Text("  •", color = c.accent, fontSize = 15.sp)
                    }
                    val pos = ed.items.indexOfFirst { it.id == ed.item?.id }
                    Text("${pos + 1} / ${ed.items.size}", color = c.muted, fontSize = 11.sp)
                }
                IconBtn(Icons.undo, S.undo(), enabled = ed.canUndo) { ed.undo() }
                IconBtn(Icons.redo, S.redo(), enabled = ed.canRedo) { ed.redo() }
                IconBtn(Icons.save, S.save(), enabled = ed.dirty || ed.item?.labelPath == null) { ed.save() }
                if (app.settings.toolbar.scaleButton != false) ScaleTool(app, app.settings.scale, labels = false, big = false)
                CompactMenu(app, ed) { showDetails = true }
            }
            Divider()
            Box(Modifier.weight(1f).fillMaxWidth()) { EditorBody(app, ed) }
            Divider()
            CompactToolbar(app, ed) { showDetails = true }
        }
        AnimatedVisibility(showFiles, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            Column(Modifier.fillMaxSize().background(c.panel)) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.back, S.back()) { showFiles = false }
                    Text(Paths.name(ed.workspace.root), color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconBtn(Icons.home, S.closeFolder()) { app.closeFolder() }
                }
                Divider()
                SidePanel(ed, Modifier.weight(1f).fillMaxWidth()) { showFiles = false }
            }
        }
        if (showDetails) {
            Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null) { showDetails = false })
        }
        AnimatedVisibility(
            showDetails, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(topStart = c.radius * 2, topEnd = c.radius * 2))
                    .background(c.panel).windowInsetsPadding(mlabeler.app.ui.screenInsets()),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.border))
                }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(S.inspector(), color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconBtn(Icons.close, S.close()) { showDetails = false }
                }
                Inspector(ed, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CompactMenu(app: AppState, ed: EditorState, onDetails: () -> Unit) {
    MenuButton(app, ed, extra = listOf(
        MItem(S.inspector()) { onDetails() },
        MItem(S.closeFolder()) { app.closeFolder() },
    ))
}

/** Large targets for one-handed use. Boundary actions act on the selected boundary or the cursor. */
@Composable
private fun CompactToolbar(app: AppState, ed: EditorState, onDetails: () -> Unit) {
    val c = T.c
    Row(
        Modifier.fillMaxWidth().background(c.panel).windowInsetsPadding(mlabeler.app.ui.screenInsets())
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val big = 48.dp
        IconBtn(if (ed.playing) Icons.stop else Icons.play, if (ed.playing) S.stop() else S.play(), size = big) { ed.togglePlay() }
        IconBtn(Icons.left, S.prevInterval(), size = big) { ed.stepInterval(-1) }
        IconBtn(Icons.right, S.nextInterval(), size = big) { ed.stepInterval(1) }
        IconBtn(Icons.nudgeLeft, S.nudgeLeft(), size = big, enabled = ed.selection is mlabeler.app.state.Selection.Bound) { ed.nudge(-1) }
        IconBtn(Icons.nudgeRight, S.nudgeRight(), size = big, enabled = ed.selection is mlabeler.app.state.Selection.Bound) { ed.nudge(1) }
        if (ed.mode == Mode.Oto) {
            IconBtn(Icons.up, Commands.prevEntry.title(), size = big) { ed.oto.step(-1) }
            IconBtn(Icons.down, Commands.nextEntry.title(), size = big) { ed.oto.step(1) }
            IconBtn(Icons.plus, Commands.otoAdd.title(), size = big) { ed.oto.add() }
        } else {
            IconBtn(Icons.split, S.split(), size = big) { ed.splitAt() }
            IconBtn(Icons.merge, S.merge(), size = big) { ed.mergeSelected() }
        }
        IconBtn(Icons.edit, S.rename(), size = big, enabled = ed.selectedInterval() != null) { onDetails() }
        IconBtn(Icons.ripple, S.ripple(), size = big, active = app.settings.edit.ripple) { Commands.ripple.run(ed, app) }
        IconBtn(Icons.check, S.toggleDone(), size = big, active = ed.item?.let { ed.marks(it).done } == true) { Commands.done.run(ed, app) }
    }
}

/** Files, or oto entries in oto mode. */
@Composable
fun SidePanel(ed: EditorState, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    if (ed.mode == Mode.Oto) return OtoEntryList(ed, modifier, onOpened)
    var tab by remember { mutableStateOf(0) }
    val c = T.c
    Column(modifier.background(c.panel)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, t) in listOf(S.files(), mlabeler.app.ui.entriesTitle()).withIndex()) {
                val sel = k == tab
                // tabs share the width and shorten their names in a narrow panel, so none goes out of reach
                Text(
                    t, fontSize = 13.sp, color = if (sel) c.text else c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(c.radius)).background(if (sel) c.panelAlt else c.panel)
                        .clickable { tab = k }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (tab == 0) FilesPanel(ed, Modifier.weight(1f).fillMaxWidth(), onOpened) else EntriesPanel(ed, Modifier.weight(1f).fillMaxWidth(), onOpened)
    }
}

@Composable
fun MessageToast(app: AppState, bottom: Dp) {
    val c = T.c
    val m = app.message ?: return
    Box(Modifier.fillMaxSize().padding(bottom = bottom, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.clip(RoundedCornerShape(c.radius)).background(if (m.error) c.danger else c.text)
                .clickable { app.dismissMessage() }.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(m.text, color = c.bg, fontSize = 13.sp)
        }
    }
}

enum class SidePanelId { Files, Details }

private val panelNames = mapOf(
    SidePanelId.Files to mlabeler.app.i18n.L("Files and entries", "Файлы и записи"),
    SidePanelId.Details to mlabeler.app.i18n.L("Details", "Подробности"),
)
private val toOtherSide = mlabeler.app.i18n.L("Move to the other side", "Перенести на другую сторону")
private val hidePanel = mlabeler.app.i18n.L("Hide", "Скрыть")

/** Side panels of one side: a header with tabs when there are two, move/hide buttons while arranging. */
@Composable
private fun PanelStack(app: AppState, ed: EditorState, panels: List<SidePanelId>, modifier: Modifier) {
    val c = T.c
    var tab by remember { mutableStateOf(0) }
    val current = panels[tab.coerceIn(0, panels.size - 1)]
    val arranging = app.arrangePanels
    Column(modifier.background(c.panel)) {
        if (panels.size > 1 || arranging) {
            Row(Modifier.fillMaxWidth().background(if (arranging) c.accent.copy(alpha = 0.12f) else c.panel).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                for ((k, p) in panels.withIndex()) {
                    val sel = p == current
                    Text(
                        panelNames.getValue(p)(), fontSize = 13.sp, color = if (sel) c.text else c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(c.radius)).background(if (sel && panels.size > 1) c.panelAlt else c.panel.copy(alpha = 0f))
                            .clickable { tab = k }.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                }
                if (arranging) {
                    IconBtn(Icons.layers, toOtherSide(), size = 28.dp) {
                        app.update { st ->
                            st.copy(layout = when (current) {
                                SidePanelId.Files -> st.layout.copy(filesSide = if (st.layout.filesSide == "right") "left" else "right")
                                SidePanelId.Details -> st.layout.copy(inspectorSide = if (st.layout.inspectorSide == "left") "right" else "left")
                            })
                        }
                    }
                    IconBtn(Icons.close, hidePanel(), size = 28.dp) {
                        app.update { st ->
                            st.copy(layout = when (current) {
                                SidePanelId.Files -> st.layout.copy(showFiles = false)
                                SidePanelId.Details -> st.layout.copy(showInspector = false)
                            })
                        }
                    }
                }
            }
            Divider()
        }
        when (current) {
            SidePanelId.Files -> SidePanel(ed, Modifier.weight(1f).fillMaxWidth())
            SidePanelId.Details -> Inspector(ed, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
