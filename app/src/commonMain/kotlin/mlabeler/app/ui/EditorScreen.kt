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
import androidx.compose.ui.input.key.type
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
        TopBar(app, ed, wc, overlayDetails) { overlayDetails = !overlayDetails }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                if (l.showFiles) {
                    SidePanel(ed, Modifier.width(l.filesWidth.coerceIn(180f, 480f).dp).fillMaxHeight())
                    VSplitter { d -> app.update { it.copy(layout = it.layout.copy(filesWidth = (it.layout.filesWidth + d).coerceIn(180f, 480f))) } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    EditorBody(app, ed)
                }
                if (l.showInspector && wc == WidthClass.Expanded) {
                    VSplitter { d -> app.update { it.copy(layout = it.layout.copy(inspectorWidth = (it.layout.inspectorWidth - d).coerceIn(220f, 520f))) } }
                    Inspector(ed, Modifier.width(l.inspectorWidth.coerceIn(220f, 520f).dp).fillMaxHeight())
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
        Divider()
        StatusBar(app, ed)
    }
}

@Composable
private fun EditorBody(app: AppState, ed: EditorState) {
    val c = T.c
    Box(Modifier.fillMaxSize()) {
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

@Composable
private fun TopBar(app: AppState, ed: EditorState, wc: WidthClass, overlayDetails: Boolean, toggleOverlay: () -> Unit) {
    val c = T.c
    val s = app.settings
    val item = ed.item
    // left: files and the title; middle: tools, scrolling when they do not fit; right: always visible
    Row(
        Modifier.fillMaxWidth().height(if (Platform.isMobile) 44.dp else 46.dp).background(c.panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Icons.panelLeft, S.toggleFiles(), Commands.files.keyLabel, active = s.layout.showFiles) { Commands.files.run(ed, app) }
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
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
        ) {
            IconBtn(Icons.prevFile, S.prevFile(), Commands.prevFile.keyLabel) { ed.openRelative(-1) }
            IconBtn(Icons.nextFile, S.nextFile(), Commands.nextFile.keyLabel) { ed.openRelative(1) }
            Sep()
            IconBtn(Icons.undo, S.undo(), Commands.undo.keyLabel, enabled = ed.canUndo) { ed.undo() }
            IconBtn(Icons.redo, S.redo(), Commands.redo.keyLabel, enabled = ed.canRedo) { ed.redo() }
            IconBtn(Icons.save, S.save(), Commands.save.keyLabel, enabled = ed.dirty || ed.item?.labelPath == null) { ed.save() }
            Sep()
            IconBtn(if (ed.playing) Icons.stop else Icons.play, if (ed.playing) S.stop() else S.play(), Commands.togglePlay.keyLabel) { ed.togglePlay() }
            IconBtn(Icons.loop, S.loop(), Commands.loop.keyLabel, active = s.edit.loop) { Commands.loop.run(ed, app) }
            SpeedButton(ed, s.edit.speed)
            Sep()
            if (ed.mode == Mode.Oto) {
                IconBtn(Icons.magic, Commands.autoOto.title(), Commands.autoOto.keyLabel) { app.showAutoOto = true }
                IconBtn(Icons.plus, Commands.otoAdd.title(), Commands.otoAdd.keyLabel) { ed.oto.add() }
                IconBtn(Icons.merge, Commands.otoDuplicate.title(), Commands.otoDuplicate.keyLabel, enabled = ed.oto.current() != null) { ed.oto.duplicate() }
                IconBtn(Icons.trash, Commands.otoDelete.title(), Commands.otoDelete.keyLabel, enabled = ed.oto.current() != null) { ed.oto.delete() }
                IconBtn(Icons.link, Commands.otoLock.title(), Commands.otoLock.keyLabel, active = s.edit.otoLockedDrag) { Commands.otoLock.run(ed, app) }
            } else {
                IconBtn(Icons.split, S.split(), Commands.split.keyLabel) { ed.splitAt() }
                IconBtn(Icons.merge, S.merge(), Commands.merge.keyLabel) { ed.mergeSelected() }
                IconBtn(Icons.ripple, S.ripple() + " — " + S.rippleHint(), Commands.ripple.keyLabel, active = s.edit.ripple) { Commands.ripple.run(ed, app) }
                IconBtn(Icons.link, S.linked() + " — " + S.linkedHint(), Commands.linked.keyLabel, active = s.edit.linked) { Commands.linked.run(ed, app) }
            }
            Sep()
            IconBtn(Icons.layers, S.overlayShort(), Commands.overlay.keyLabel, active = s.layout.overlay) { Commands.overlay.run(ed, app) }
            IconBtn(Icons.zoomOut, S.zoomOut(), Commands.zoomOut.keyLabel) { Commands.zoomOut.run(ed, app) }
            IconBtn(Icons.zoomIn, S.zoomIn(), Commands.zoomIn.keyLabel) { Commands.zoomIn.run(ed, app) }
            IconBtn(Icons.fit, S.zoomFit(), Commands.zoomFit.keyLabel) { ed.fitAll() }
            Sep()
            IconBtn(Icons.plugin, S.pluginsTitle(), Commands.plugins.keyLabel) { Commands.plugins.run(ed, app) }
            IconBtn(Icons.command, S.commands(), Commands.palette.keyLabel) { app.showCommands = true }
        }
        Sep()
        IconBtn(Icons.settings, S.settings(), Commands.settings.keyLabel) { app.showSettings = true }
        MainMenu(app, ed)
        if (wc == WidthClass.Medium) {
            IconBtn(Icons.panelRight, S.toggleInspector(), active = overlayDetails) { toggleOverlay() }
        } else {
            IconBtn(Icons.panelRight, S.toggleInspector(), Commands.inspector.keyLabel, active = s.layout.showInspector) { Commands.inspector.run(ed, app) }
        }
    }
}

@Composable
private fun Sep() {
    val c = T.c
    Box(Modifier.padding(horizontal = 4.dp).width(c.borderWidth).height(20.dp).background(c.border))
}

@Composable
private fun MainMenu(app: AppState, ed: EditorState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Icons.menu, S.more()) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(S.openFolder()) }, onClick = { open = false; app.closeFolder() })
            DropdownMenuItem({ Text(S.showInFolder()) }, onClick = { open = false; Platform.openInFileManager(ed.workspace.root) })
            DropdownMenuItem({ Text(S.waveform()) }, onClick = { open = false; Commands.wave.run(ed, app) }, trailingIcon = { if (app.settings.layout.showWaveform) TextIcon("✓") })
            DropdownMenuItem({ Text(S.spectrogram()) }, onClick = { open = false; Commands.spectrogram.run(ed, app) }, trailingIcon = { if (app.settings.layout.showSpectrogram) TextIcon("✓") })
            DropdownMenuItem({ Text(S.pitch()) }, onClick = { open = false; Commands.pitchLane.run(ed, app) }, trailingIcon = { if (app.settings.layout.showPitch) TextIcon("✓") })
            DropdownMenuItem({ Text(S.tiersOnTop()) }, onClick = { open = false; Commands.tiersOnTop.run(ed, app) }, trailingIcon = { if (app.settings.layout.tiersOnTop) TextIcon("✓") })
            DropdownMenuItem({ Text(S.power()) }, onClick = { open = false; Commands.powerLane.run(ed, app) }, trailingIcon = { if (app.settings.layout.showPower) TextIcon("✓") })
            DropdownMenuItem({ Text(Commands.workspace.title()) }, onClick = { open = false; app.showWorkspace = true })
            DropdownMenuItem({ Text(Commands.record.title()) }, onClick = { open = false; app.openRecorder(ed.workspace.root) })
            DropdownMenuItem({ Text(Commands.importLbp.title()) }, onClick = { open = false; app.showImport = true })
            DropdownMenuItem({ Text(S.settings()) }, onClick = { open = false; app.showSettings = true }, trailingIcon = { TextIcon(Commands.settings.keyLabel) })
            DropdownMenuItem({ Text(Commands.help.title()) }, onClick = { open = false; app.showHelp = true }, trailingIcon = { TextIcon("F1") })
        }
    }
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
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Icons.more, S.more()) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(S.inspector()) }, onClick = { open = false; onDetails() })
            DropdownMenuItem({ Text(S.ripple()) }, onClick = { open = false; Commands.ripple.run(ed, app) }, trailingIcon = { if (app.settings.edit.ripple) TextIcon("✓") })
            DropdownMenuItem({ Text(S.linked()) }, onClick = { open = false; Commands.linked.run(ed, app) }, trailingIcon = { if (app.settings.edit.linked) TextIcon("✓") })
            DropdownMenuItem({ Text(S.loop()) }, onClick = { open = false; Commands.loop.run(ed, app) }, trailingIcon = { if (app.settings.edit.loop) TextIcon("✓") })
            DropdownMenuItem({ Text(S.waveform()) }, onClick = { open = false; Commands.wave.run(ed, app) }, trailingIcon = { if (app.settings.layout.showWaveform) TextIcon("✓") })
            DropdownMenuItem({ Text(S.spectrogram()) }, onClick = { open = false; Commands.spectrogram.run(ed, app) }, trailingIcon = { if (app.settings.layout.showSpectrogram) TextIcon("✓") })
            DropdownMenuItem({ Text(S.zoomFit()) }, onClick = { open = false; ed.fitAll() })
            DropdownMenuItem({ Text(S.settings()) }, onClick = { open = false; app.showSettings = true })
            DropdownMenuItem({ Text(Commands.help.title()) }, onClick = { open = false; app.showHelp = true })
            DropdownMenuItem({ Text(Commands.record.title()) }, onClick = { open = false; app.openRecorder(ed.workspace.root) })
            DropdownMenuItem({ Text(S.pluginsTitle()) }, onClick = { open = false; Commands.plugins.run(ed, app) })
            DropdownMenuItem({ Text(S.closeFolder()) }, onClick = { open = false; app.closeFolder() })
        }
    }
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
                Text(
                    t, fontSize = 13.sp, color = if (sel) c.text else c.muted, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(c.radius)).background(if (sel) c.panelAlt else c.panel)
                        .clickable { tab = k }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (tab == 0) FilesPanel(ed, Modifier.weight(1f).fillMaxWidth(), onOpened) else EntriesPanel(ed, Modifier.weight(1f).fillMaxWidth(), onOpened)
    }
}

@Composable
private fun SpeedButton(ed: EditorState, speed: Float) {
    val c = T.c
    Tip(Commands.speed.title() + "  ·  " + Commands.speed.keyLabel) {
        Text(
            if (speed >= 1f) "1×" else "$speed×".removePrefix("0"),
            color = if (speed < 1f) c.accent else c.muted, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(c.radius)).clickable { ed.cycleSpeed() }.padding(horizontal = 6.dp, vertical = 6.dp),
        )
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
