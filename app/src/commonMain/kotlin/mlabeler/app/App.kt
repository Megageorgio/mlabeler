package mlabeler.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import mlabeler.app.state.AppState
import mlabeler.app.theme.AppTheme
import mlabeler.app.theme.Themes
import mlabeler.app.ui.CommandPalette
import mlabeler.app.ui.EditorScreen
import mlabeler.app.ui.SettingsDialog
import mlabeler.app.ui.StartScreen

@Composable
fun rememberAppState(): AppState {
    val scope = rememberCoroutineScope()
    return remember { AppState(scope) }
}

@Composable
fun App(app: AppState = rememberAppState()) {
    DisposableEffect(app) { onDispose { app.close() } }
    androidx.compose.runtime.LaunchedEffect(app) { app.showCrashReport() }
    val base = LocalDensity.current
    val scale = app.settings.scale.coerceIn(0.5f, 2f)
    mlabeler.app.ui.UiScale.current = scale
    val st = app.settings
    androidx.compose.runtime.LaunchedEffect(st.orientation, st.fullscreen) { Platform.applyScreen(st.orientation, st.fullscreen) }
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        mlabeler.app.ui.LocalKeepBarsFree provides (!Platform.isMobile || !st.fullscreen || st.avoidCutout),
    ) {
        val font = androidx.compose.runtime.remember(st.font) { systemFontFamily(st.font) }
        AppTheme(Themes.byId(app.settings.theme), font, crisp = app.settings.crisp) {
            StorageAccess {
                Box(Modifier.fillMaxSize()) {
                    val ed = app.editor
                    val rec = app.recorder
                    val kar = app.karaoke
                    if (kar != null) mlabeler.app.recorder.KaraokeScreen(app, kar)
                    else if (rec != null) mlabeler.app.recorder.RecorderScreen(app, rec)
                    else if (ed == null) StartScreen(app) else EditorScreen(app, ed)
                    // the start screen shows messages too (an error opening a folder, the report of a crash)
                    if (ed == null && rec == null && kar == null) mlabeler.app.ui.MessageToast(app, 24.dp)
                    if (app.showCommands && ed != null) CommandPalette(app)
                    if (app.showSettings) SettingsDialog(app)
                    if (!app.settings.setupDone && rec == null && kar == null) mlabeler.app.ui.SetupDialog(app)
                    if (app.showBatchRename && ed != null) mlabeler.app.ui.BatchRenameDialog(app)
                    if (ed != null) app.renamingFile?.let { i -> mlabeler.app.ui.RenameFileDialog(ed, i) { app.renamingFile = null } }
                    if (ed != null) app.trashingFile?.let { i -> mlabeler.app.ui.TrashFileDialog(ed, i) { app.trashingFile = null } }
                    if (ed != null && app.mergingFiles) mlabeler.app.ui.MergeFilesDialog(ed) { app.mergingFiles = false }
                    if (ed != null && app.trashingPicked) mlabeler.app.ui.TrashFilesDialog(ed) { app.trashingPicked = false }
                    if (app.showSegments && ed != null) mlabeler.app.ui.SegmentDialog(app)
                    if (app.updater.offer != null) mlabeler.app.ui.UpdateOfferDialog(app)
                    if (app.showWorkspace && ed != null) mlabeler.app.ui.WorkspaceDialog(app)
                    if (app.showAutolabel && ed != null) mlabeler.app.ui.AutolabelDialog(app)
                    if (app.showCleanup && ed != null) mlabeler.app.ui.CleanupDialog(app)
                    if (app.showHelp && app.settings.setupDone) mlabeler.app.ui.HelpDialog(app)
                    // (an update offer or a crash report comes first; the tip waits for the folder dialog to close)
                    if (app.showTips && app.settings.setupDone && app.updater.offer == null && app.folderPick == null && !app.showHelp) mlabeler.app.ui.TipsDialog(app)
                    if (app.showPlugins && ed != null) mlabeler.app.ui.PluginsDialog(app)
                    if (app.showImport && ed != null) mlabeler.app.ui.ImportDialog(app)
                    if (app.showDsExport && ed != null) mlabeler.app.ui.DsExportDialog(app, ed)
                    if (app.showSummary && ed != null) mlabeler.app.ui.DatasetSummaryDialog(app, ed)
                    if (app.showPhonemeCuts && ed != null) mlabeler.app.ui.PhonemeCutsDialog(app, ed)
                    if (app.showRefine && ed != null) mlabeler.app.ui.RefineDialog(app, ed)
                    if (app.showSoundCheck && ed != null) mlabeler.app.ui.SoundCheckDialog(app, ed)
                    if (app.pendingLeave != null) mlabeler.app.ui.LeaveDialog(app)
                    if (app.showAutoOto && ed != null) mlabeler.app.ui.AutoOtoDialog(app)
                    if (app.errorDetails != null) mlabeler.app.ui.ErrorDetailsDialog(app)
                    app.folderPick?.let { pick ->
                        mlabeler.app.ui.FolderBrowser(
                            start = ed?.workspace?.root ?: Platform.homeDir(),
                            onPick = { app.folderPick = null; pick(it) },
                            onCancel = { app.folderPick = null },
                        )
                    }
                }
            }
        }
    }
}
