package mlabeler.app.state


import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mlabeler.app.Platform
import mlabeler.app.i18n.Lang
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace

data class Message(val text: String, val error: Boolean, val id: Long)

private val dropOnlyAudio = mlabeler.app.i18n.L("Drop a recording or a folder", "Перетащите запись или папку")
private val dropOtherAudio = mlabeler.app.i18n.L("Turn on other audio formats in Settings → General to open these", "Чтобы открывать такие файлы, включите другие форматы в Настройках → Общие")

class AppState(private val scope: CoroutineScope) {
    var settings by mutableStateOf(AppSettings.load())
        private set
    var editor by mutableStateOf<EditorState?>(null)
        private set
    var message by mutableStateOf<Message?>(null)
        private set
    var showSettings by mutableStateOf(false)
    /** Settings page to open with ("keys", "about", …); empty = the first. */
    var settingsPage by mutableStateOf("")
    var showCommands by mutableStateOf(false)
    var showBatchRename by mutableStateOf(false)
    var showWorkspace by mutableStateOf(false)
    var showAutolabel by mutableStateOf(false)
    var showCleanup by mutableStateOf(false)
    /** Panels show move/hide buttons (View → Arrange panels). */
    var arrangePanels by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showAutoOto by mutableStateOf(false)
    var showPlugins by mutableStateOf(false)
    var showImport by mutableStateOf(false)
    var showDsExport by mutableStateOf(false)
    val toolkit = mlabeler.app.toolkit.ToolkitManager(this, scope)
    var plugins by mutableStateOf<List<mlabeler.app.plugins.Plugin>>(emptyList())
        private set

    fun pluginDirs(): List<String> = listOfNotNull(
        mlabeler.core.io.Paths.join(Platform.dataDir(), "plugins"),
        editor?.workspace?.metaDir?.let { mlabeler.core.io.Paths.join(it, "plugins") },
    )

    fun reloadPlugins() {
        plugins = mlabeler.app.plugins.Plugins.load(pluginDirs())
    }

    fun pluginParams(p: mlabeler.app.plugins.Plugin): Map<String, kotlinx.serialization.json.JsonElement> {
        val saved = settings.pluginParams[p.info.name]?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }.getOrNull() }
        return p.info.parameters.associate { it.name to (saved?.get(it.name) ?: it.default) }
    }

    /** Runs a plugin on the open file or oto.ini and applies the result as one undo step. */
    fun runPlugin(p: mlabeler.app.plugins.Plugin, params: Map<String, kotlinx.serialization.json.JsonElement>) {
        val ed = editor ?: return
        update { it.copy(pluginParams = it.pluginParams + (p.info.name to kotlinx.serialization.json.JsonObject(params).toString())) }
        scope.launch {
            try {
                val oto = p.info.target == "oto"
                val r = mlabeler.app.plugins.Plugins.run(p, params, if (oto) null else ed.doc, if (oto) ed.oto.entries else null, ed.item?.name ?: "", ed.duration)
                r.doc?.let { d -> ed.updateDoc { mlabeler.core.edit.Edits.fitToDuration(d, ed.duration) } }
                r.entries?.let { ed.oto.replaceAll(it) }
                val text = listOfNotNull(r.report, r.logs.takeIf { it.isNotEmpty() }?.joinToString("\n")).joinToString("\n")
                message(text.ifEmpty { mlabeler.app.i18n.S.pluginDone() })
            } catch (e: Exception) {
                message(e.message ?: e.toString(), error = true)
            }
        }
    }
    var recorder by mutableStateOf<mlabeler.app.recorder.RecorderState?>(null)
        private set

    /** Opens the recorder for [folder] (saving the editor's changes first). */
    fun openRecorder(folder: String) {
        if (!PlatformFs.isDirectory(folder)) return
        editor?.let { if (it.dirty) it.save(quiet = true) }
        recorder = mlabeler.app.recorder.RecorderState(folder, this, scope)
    }

    fun closeRecorder() {
        recorder?.close()
        recorder = null
        editor?.rescan()
    }
    /** Set while the in-app folder browser is open for a pick. */
    var folderPick by mutableStateOf<((String) -> Unit)?>(null)

    /** Asks for a folder: the system dialog where there is one, else the built-in browser. */
    fun pickFolder(title: String, onPick: (String) -> Unit) {
        if (mlabeler.app.Platform.hasNativeFolderPicker) {
            val start = editor?.workspace?.root?.let { mlabeler.core.io.Paths.parent(it) }
                ?: settings.recent.firstOrNull()?.let { mlabeler.core.io.Paths.parent(it) }
            scope.launch {
                val picked = kotlinx.coroutines.withContext(Dispatchers.Default) { mlabeler.app.Platform.pickFolderNative(title, start) }
                picked?.let(onPick)
            }
        } else {
            folderPick = onPick
        }
    }
    private var messageJob: Job? = null
    private var counter = 0L

    init {
        // phones start smaller: the same sizes as on a desktop look oversized there
        if (Platform.isMobile && !settings.scaleChosen) settings = settings.copy(scale = 0.8f)
        runCatching { mlabeler.app.theme.ThemeFiles.load(Platform.dataDir()) }
        mlabeler.app.ui.Keymap.load(settings.keymap)
        applyAudioFormats(settings)
        Lang.current = settings.language.ifEmpty { Platform.systemLanguage }.let { l -> if (Lang.available.any { it.first == l }) l else "en" }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        var s = transform(settings)
        if (s.scale != settings.scale) s = s.copy(scaleChosen = true)
        if (s == settings) return
        val formatsChanged = s.otherAudio != settings.otherAudio
        settings = s
        if (s.language.isNotEmpty()) Lang.current = s.language
        if (formatsChanged) { applyAudioFormats(s); editor?.rescan() }
        AppSettings.save(s)
    }

    private fun applyAudioFormats(s: AppSettings) {
        mlabeler.core.io.AudioFormats.accepted = if (s.otherAudio) mlabeler.core.io.ALL_AUDIO_EXTENSIONS else setOf("wav")
    }

    /** Bumped when the user's environment files change, so lists reload. */
    var environmentsVersion by mutableStateOf(0)
        private set

    fun applyEnvironment(id: String) {
        val e = Environments.byId(id) ?: return
        update { Environments.apply(it, e) }
    }

    fun saveEnvironment(name: String) {
        val n = name.trim().ifEmpty { return }
        runCatching { Environments.saveCurrent(settings, n) }
            .onSuccess { id -> update { it.copy(environment = id) }; environmentsVersion++; message(mlabeler.app.i18n.S.environmentSaved.format(n)) }
            .onFailure { message(it.message ?: it.toString(), error = true) }
    }

    fun deleteEnvironment(id: String) {
        Environments.delete(id)
        if (settings.environment == id) update { it.copy(environment = "basic") }
        environmentsVersion++
    }

    fun message(text: String, error: Boolean = false) {
        val m = Message(text, error, ++counter)
        message = m
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(if (error) 8000 else 2500)
            if (message?.id == m.id) message = null
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun openFolder(path: String) {
        if (!PlatformFs.isDirectory(path)) return
        closeFolder()
        val ws = Workspace(path)
        val ed = EditorState(ws, this, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        editor = ed
        ed.scan()
        reloadPlugins()
        // the first folder ever opened: show how things work
        if (!settings.seenHelp) {
            showHelp = true
            update { it.copy(seenHelp = true) }
        }
        update { it.copy(recent = (listOf(path) + it.recent.filter { r -> r != path }).take(12)) }
    }

    /** Something to do after leaving the open folder, waiting for an answer about unsaved changes. */
    var pendingLeave by mutableStateOf<(() -> Unit)?>(null)

    /** Runs [action] (which leaves the folder); with unsaved changes and "save when switching" off, asks first. */
    fun leaveFolderThen(action: () -> Unit) {
        val ed = editor
        if (ed != null && ed.dirty && !settings.edit.saveOnSwitch) pendingLeave = action else action()
    }

    /**
     * Files dropped on the picture or the start screen: the recording (or folder) opens. A recording of another
     * folder opens that folder with it; one in a "wav"/"wavs" folder opens the folder above (labels may sit beside).
     */
    fun openDropped(paths: List<String>): Boolean {
        val first = paths.firstOrNull() ?: return false
        val ed = editor
        if (PlatformFs.isDirectory(first)) {
            leaveFolderThen { openFolder(first) }
            return true
        }
        val ext = mlabeler.core.io.Paths.ext(first).lowercase()
        if (ext !in mlabeler.core.io.AUDIO_EXTENSIONS) {
            message(if (ext in mlabeler.core.io.ALL_AUDIO_EXTENSIONS) dropOtherAudio() else dropOnlyAudio(), error = true)
            return false
        }
        if (ed != null && ed.contains(first)) return ed.openPath(first)
        var folder = mlabeler.core.io.Paths.parent(first)
        if (mlabeler.core.io.Paths.name(folder).lowercase() in setOf("wav", "wavs", "audio", "raw")) folder = mlabeler.core.io.Paths.parent(folder)
        leaveFolderThen {
            openFolder(folder)
            editor?.openPath(first)
        }
        return true
    }

    fun closeFolder() {
        editor?.saveAllOnClose()
        editor = null
    }

    fun forgetRecent(path: String) = update { it.copy(recent = it.recent - path) }

    fun close() {
        editor?.saveAllOnClose()
        toolkit.stop()
    }
}
