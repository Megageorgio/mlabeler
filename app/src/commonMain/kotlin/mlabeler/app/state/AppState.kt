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
    var showHelp by mutableStateOf(false)
    var showAutoOto by mutableStateOf(false)
    var showPlugins by mutableStateOf(false)
    var showImport by mutableStateOf(false)
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
