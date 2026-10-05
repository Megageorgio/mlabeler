package mlabeler.app.toolkit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState

/**
 * Finds, installs and starts mVocalToolkit for this app. On a computer the toolkit is started as a child
 * process when something needs it (and stopped when the app closes); a toolkit that is already running on the
 * same address is simply used. Phones only connect to an address.
 */
class ToolkitManager(private val app: AppState, private val scope: CoroutineScope) {
    enum class Status { Unknown, Checking, Ready, Starting, Installing, Missing, Off, Failed }

    var status by mutableStateOf(Status.Unknown)
        private set
    var version by mutableStateOf("")
        private set
    /** Token printed by a toolkit started with network access (for phones). */
    var networkToken by mutableStateOf("")
        private set
    /** True when the running toolkit was started by this app. */
    var ownProcess by mutableStateOf(false)
        private set
    var lastError by mutableStateOf("")
        private set
    val log = mutableStateListOf<String>()
    private val lock = Mutex()
    private var installJob: Job? = null

    private val settings get() = app.settings.toolkit
    fun client() = ToolkitClient(settings.url, settings.token)

    val isLocalAddress: Boolean
        get() {
            val host = settings.url.substringAfter("://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
            return host in setOf("127.0.0.1", "localhost", "::1", "0.0.0.0")
        }
    val canRunHere: Boolean get() = LocalToolkit.supported && isLocalAddress
    val port: Int get() = settings.url.substringAfter("://").substringBefore('/').substringAfterLast(':', "").toIntOrNull() ?: 8765

    private fun addLog(line: String) {
        scope.launch {
            log.add(line)
            while (log.size > 300) log.removeAt(0)
            Regex("Token: (\\S+)").find(line)?.let { networkToken = it.groupValues[1] }
        }
    }

    /** Asks the toolkit whether it answers. */
    suspend fun check(): Boolean {
        if (status != Status.Starting && status != Status.Installing) status = Status.Checking
        return try {
            val h = client().health()
            version = (h.jsonObject["version"] as? JsonPrimitive)?.content ?: ""
            status = Status.Ready
            true
        } catch (e: Exception) {
            lastError = e.message ?: ""
            if (status == Status.Checking) status = when {
                !canRunHere -> Status.Off
                LocalToolkit.findMvt(settings.mvtPath) == null -> Status.Missing
                else -> Status.Off
            }
            false
        }
    }

    /** Makes sure the toolkit answers: starts it here when allowed. Returns false when it can't. */
    suspend fun ensure(): Boolean = lock.withLock {
        if (check()) return true
        if (!canRunHere || !settings.autoStart) return false
        if (LocalToolkit.findMvt(settings.mvtPath) == null) { status = Status.Missing; return false }
        startLocked()
    }

    suspend fun start(): Boolean = lock.withLock { startLocked() }

    private suspend fun startLocked(): Boolean {
        val mvt = LocalToolkit.findMvt(settings.mvtPath) ?: run { status = Status.Missing; return false }
        status = Status.Starting
        networkToken = ""
        val cmd = buildList {
            add(mvt); add("serve"); add("--port"); add(port.toString())
            if (settings.shareOnNetwork) { add("--host"); add("0.0.0.0") }
        }
        addLog("> " + cmd.joinToString(" "))
        if (!LocalToolkit.start(cmd, ::addLog)) { status = Status.Failed; lastError = log.lastOrNull() ?: ""; return false }
        ownProcess = true
        // the first start can take a while (Python imports); give it up to two minutes
        repeat(240) {
            delay(500)
            if (!LocalToolkit.running) {
                status = Status.Failed
                lastError = startFailed()
                ownProcess = false
                return false
            }
            if (runCatching { client().health() }.isSuccess) {
                check()
                return true
            }
        }
        status = Status.Failed
        lastError = startFailed()
        return false
    }

    fun stop() {
        LocalToolkit.stop()
        ownProcess = false
        networkToken = ""
        status = Status.Off
    }

    /** Restarts a toolkit started here (after changing network access). */
    fun restart() {
        scope.launch {
            if (ownProcess) stop()
            start()
        }
    }

    /** Installs uv when needed, then the toolkit with `uv tool install`. */
    fun install() {
        if (installJob?.isActive == true || !LocalToolkit.supported) return
        installJob = scope.launch {
            status = Status.Installing
            try {
                var uv = LocalToolkit.findUv()
                if (uv == null) {
                    addLog(gettingUv())
                    val cmd = LocalToolkit.uvInstallCommand()
                    addLog("> " + cmd.joinToString(" "))
                    LocalToolkit.run(cmd, ::addLog)
                    uv = LocalToolkit.findUv()
                }
                if (uv == null) {
                    status = Status.Failed
                    lastError = noUv()
                    return@launch
                }
                val src = settings.installSource.ifBlank { mlabeler.app.state.ToolkitSettings.DEFAULT_SOURCE }
                val cmd = listOf(uv, "tool", "install", "--force", "--python", "3.12", src)
                addLog("> " + cmd.joinToString(" "))
                val code = LocalToolkit.run(cmd, ::addLog)
                if (code != 0 || LocalToolkit.findMvt(settings.mvtPath) == null) {
                    status = Status.Failed
                    lastError = installFailed()
                    return@launch
                }
                addLog(installed())
                start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = Status.Failed
                lastError = e.message ?: e.toString()
            }
        }
    }

    val installing: Boolean get() = installJob?.isActive == true

    private fun startFailed(): String = log.takeLast(4).joinToString("\n").ifBlank { notStarted() }

    /** One line for the UI. */
    fun statusText(): String = when (status) {
        Status.Unknown, Status.Checking -> checking()
        Status.Ready -> ready.format(version.ifEmpty { "?" }) + if (ownProcess) " · " + startedHere() else ""
        Status.Starting -> starting()
        Status.Installing -> installingT()
        Status.Missing -> missing()
        Status.Off -> if (canRunHere) offHere() else offRemote()
        Status.Failed -> failed()
    }

    companion object {
        val checking = L("Checking the toolkit…", "Проверяю тулкит…")
        val ready = L("mVocalToolkit {0} is ready", "mVocalToolkit {0} готов")
        val startedHere = L("started by mLabeler", "запущен mLabeler")
        val starting = L("Starting the toolkit… (the first time takes up to a minute)", "Запускаю тулкит… (в первый раз — до минуты)")
        val installingT = L("Installing the toolkit… this downloads a few hundred MB", "Устанавливаю тулкит… скачается несколько сотен МБ")
        val missing = L("mVocalToolkit isn't installed on this computer", "mVocalToolkit не установлен на этом компьютере")
        val offHere = L("The toolkit isn't running; it starts by itself when needed", "Тулкит не запущен; он запустится сам, когда понадобится")
        val offRemote = L("No answer from the toolkit at this address", "Тулкит по этому адресу не отвечает")
        val failed = L("The toolkit couldn't start", "Тулкит не смог запуститься")
        val notStarted = L("The toolkit stopped right after start", "Тулкит остановился сразу после запуска")
        val gettingUv = L("Installing uv (Python package manager)…", "Устанавливаю uv (менеджер пакетов Python)…")
        val noUv = L("Couldn't install uv. Install it from astral.sh/uv and try again.", "Не получилось установить uv. Установите его с astral.sh/uv и попробуйте снова.")
        val installFailed = L("Installation failed; see the log below", "Установка не удалась, подробности в журнале ниже")
        val installed = L("Installed", "Установлено")
    }
}
