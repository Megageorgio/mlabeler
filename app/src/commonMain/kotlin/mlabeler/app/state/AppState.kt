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
    var showCommands by mutableStateOf(false)
    var showBatchRename by mutableStateOf(false)
    private var messageJob: Job? = null
    private var counter = 0L

    init {
        Lang.current = settings.language.ifEmpty { Platform.systemLanguage }.let { l -> if (Lang.available.any { it.first == l }) l else "en" }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(settings)
        if (s == settings) return
        settings = s
        if (s.language.isNotEmpty()) Lang.current = s.language
        AppSettings.save(s)
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
        update { it.copy(recent = (listOf(path) + it.recent.filter { r -> r != path }).take(12)) }
    }

    fun closeFolder() {
        editor?.saveAllOnClose()
        editor = null
    }

    fun forgetRecent(path: String) = update { it.copy(recent = it.recent - path) }

    fun close() {
        editor?.saveAllOnClose()
    }
}
