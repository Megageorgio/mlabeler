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
    /** When the current installing or starting began (ms), for the time shown. */
    var busySince = 0L
        private set
    private fun busy(s: Status) {
        if (status != s) busySince = kotlin.time.Clock.System.now().toEpochMilliseconds()
        status = s
    }
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
    /** The `mvt` launcher is there but what it starts was removed (its folders were deleted): installing again fixes it. */
    var damaged by mutableStateOf(false)
        private set
    /** Changes when models are added or removed, so the lists of models are read again. */
    var modelsVersion by androidx.compose.runtime.mutableIntStateOf(0)
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

    private var checkSeq = 0

    /** Asks the toolkit whether it answers. A slow answer from an older check never overrides a newer one. */
    suspend fun check(): Boolean {
        val seq = ++checkSeq
        if (status != Status.Starting && status != Status.Installing && status != Status.Ready) status = Status.Checking
        return try {
            val h = client().health()
            if (seq == checkSeq || status != Status.Ready) {
                version = (h.jsonObject["version"] as? JsonPrimitive)?.content ?: ""
                status = Status.Ready
            }
            keepAttached()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seq != checkSeq) return false
            lastError = e.message ?: ""
            if (status == Status.Checking || status == Status.Ready) status = when {
                !canRunHere -> Status.Off
                damaged || LocalToolkit.findMvt(settings.mvtPath) == null -> Status.Missing
                else -> Status.Off
            }
            false
        }
    }

    /** Makes sure the toolkit answers: starts it here when allowed. Returns false when it can't. */
    suspend fun ensure(): Boolean = lock.withLock {
        if (check()) return true
        if (!canRunHere || !settings.autoStart) return false
        if (damaged || LocalToolkit.findMvt(settings.mvtPath) == null) { status = Status.Missing; return false }
        startLocked()
    }

    /** True while an automatic update runs. */
    var updatingNow by mutableStateOf(false)
        private set

    suspend fun start(): Boolean = lock.withLock { startLocked() }

    /**
     * Asks the toolkit to update itself now (it otherwise looks for a newer version when it starts). The toolkit
     * does the update; this only waits for it to come back, as after an update found at start.
     */
    fun updateNow() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            if (!ensure()) return@launch
            val answer = runCatching { client().update().jsonObject }
            val r = answer.getOrNull()
            if (r == null) {
                // toolkits from before this request: they update when they start
                app.message(updateUnsupported())
                return@launch
            }
            if ((r["updating"] as? JsonPrimitive)?.content != "true") {
                val err = (r["error"] as? JsonPrimitive)?.content
                app.message(err ?: upToDate.format(version.ifEmpty { "?" }), error = err != null)
                return@launch
            }
            (r["log"] as? JsonPrimitive)?.content?.let { addLog("Update log: $it") }
            lock.withLock {
                // a toolkit started here exits now; one started elsewhere is followed by its log and address
                repeat(40) { if (!(ownProcess && LocalToolkit.running)) return@repeat; delay(250) }
                waitForUpdate(startCommand())
            }
        }
    }

    private fun startCommand(mvt: String = LocalToolkit.findMvt(settings.mvtPath) ?: "mvt"): List<String> = buildList {
        add(mvt); add("serve"); add("--port"); add(port.toString())
        // shared with other programs: it stops by itself when none of them uses it any more
        add("--exit-when-unused"); add("30")
        if (settings.shareOnNetwork) { add("--host"); add("0.0.0.0") }
    }

    /** An older toolkit rejected --exit-when-unused (argparse exits with 2): it is started without it. */
    private var oldToolkit = false

    private suspend fun startLocked(): Boolean {
        val mvt = LocalToolkit.findMvt(settings.mvtPath)?.takeIf { !damaged } ?: run { status = Status.Missing; return false }
        busy(Status.Starting)
        networkToken = ""
        val full = startCommand(mvt)
        val cmd = if (oldToolkit) full.filterIndexed { i, x -> x != "--exit-when-unused" && full.getOrNull(i - 1) != "--exit-when-unused" } else full
        addLog("> " + cmd.joinToString(" "))
        if (!LocalToolkit.start(cmd, ::addLog)) { status = Status.Failed; lastError = log.lastOrNull() ?: ""; return false }
        ownProcess = true
        // the first start can take a while (Python imports); give it up to two minutes
        repeat(240) {
            delay(500)
            if (!LocalToolkit.running) {
                // the toolkit found a newer version of itself: it updates and starts again on its own
                if (LocalToolkit.lastExitCode() == EXIT_UPDATING) return waitForUpdate(cmd)
                if (LocalToolkit.lastExitCode() == 2 && !oldToolkit) { oldToolkit = true; return startLocked() }
                // (the process's last lines reach the log a moment after it exits)
                delay(300)
                val said = log.drop(log.indexOfLast { it.startsWith("> ") } + 1)
                if (said.any { line -> DAMAGED.any { it in line } }) {
                    damaged = true
                    status = Status.Missing
                    lastError = ""
                    ownProcess = false
                    return false
                }
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

    /** The toolkit updates itself (it exited with [EXIT_UPDATING]); wait until it answers again, up to 15 minutes. */
    private suspend fun waitForUpdate(cmd: List<String>): Boolean {
        updatingNow = true
        busy(Status.Installing)
        addLog(updating())
        // the updater writes uv's output to a file (its path is in the toolkit's last words): show it as it grows
        var shown = 0
        var finishedAt = 0
        var startedAgain = false
        val since = kotlin.time.Clock.System.now().toEpochMilliseconds() - 5_000
        try {
            repeat(15 * 60) { second ->
                delay(1000)
                // (looked up each time: the toolkit's last lines reach the log a moment after it exits);
                // older toolkits don't name it: their default place, if it changed just now
                val updateLog = log.lastOrNull { it.startsWith("Update log: ") }?.removePrefix("Update log: ")?.trim()
                    ?: mlabeler.core.io.Paths.join(mlabeler.app.Platform.homeDir(), "mVocalToolkit/logs/update.log").takeIf { p ->
                        runCatching { mlabeler.core.io.PlatformFs.lastModified(p) >= since }.getOrDefault(false)
                    }
                var lines: List<String> = emptyList()
                if (updateLog != null) runCatching {
                    val bytes = mlabeler.core.io.PlatformFs.read(updateLog)
                    val text = bytes.decodeLog()
                    lines = text.lines().map { it.trimEnd('\r', '\uFEFF') }.filter { it.isNotBlank() }
                    if (lines.size < shown) shown = 0
                    for (l in lines.drop(shown)) addLog(l)
                    shown = lines.size
                }
                if (runCatching { client().health() }.isSuccess) {
                    // started by the updater: stopped through /shutdown later; started by us: a child as usual
                    restartedItself = !startedAgain
                    check()
                    return true
                }
                // uv is done ("Installed … executable" for older toolkits): if the toolkit doesn't come back by itself
                // soon (it couldn't find itself to start again), start it here
                val installed = lines.any { it.startsWith("Installed ") && "executable" in it }
                val finished = lines.any { it.startsWith("mVocalToolkit update finished") }
                // uv failed: say so instead of waiting
                val err = lines.lastOrNull { it.trimStart().startsWith("error:") }
                if (finished && !installed && err != null) {
                    status = Status.Failed
                    lastError = err.trim()
                    ownProcess = false
                    return false
                }
                val done = installed || finished
                if (done && finishedAt == 0) finishedAt = second
                if (done && !startedAgain && second - finishedAt >= 12) {
                    startedAgain = true
                    addLog(startingAgain())
                    addLog("> " + cmd.joinToString(" "))
                    if (LocalToolkit.start(cmd, ::addLog)) ownProcess = true
                }
            }
            status = Status.Failed
            lastError = updateFailed()
            ownProcess = false
            return false
        } finally {
            updatingNow = false
        }
    }

    // ---------- sharing the toolkit with other programs ----------

    /** This program's id at the toolkit (it counts the programs using it and stops when none is left). */
    private var clientId: String? = null
    private var pingJob: Job? = null

    private fun keepAttached() {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while (true) {
                val c = client()
                val id = clientId
                if (id == null || !c.ping(id)) clientId = c.attach("mLabeler")
                delay(30_000)
            }
        }
    }

    /**
     * The app closes: it stops using the toolkit. When no other program uses it and it was started for programs
     * (by this app or another), it is stopped now, so nothing stays running and no folder stays busy.
     */
    fun close() {
        pingJob?.cancel()
        val c = client()
        val id = clientId
        clientId = null
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    val left = id?.let { c.detach(it) }
                    val h = runCatching { c.health().jsonObject }.getOrNull()
                    val shared = (h?.get("exit_when_unused") as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { it > 0 } == true
                    val others = left ?: (h?.get("clients") as? JsonPrimitive)?.content?.toIntOrNull()
                    when {
                        // older toolkits don't count programs: one started here goes with the app, as before
                        others == null -> if (ownProcess) { c.shutdown(); LocalToolkit.stop() }
                        others == 0 && (shared || ownProcess) -> c.shutdown()
                        // others still use it: it stays and stops by itself after the last one
                    }
                }
            }
        }
        status = Status.Off
    }

    /** Started here, but running as its own process after updating itself: it is stopped through /shutdown. */
    private var restartedItself = false

    fun stop() {
        if (restartedItself && ownProcess) {
            restartedItself = false
            val c = client()
            // the app may be closing: ask briefly
            runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1500) { c.shutdown() } } }
        }
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

    /** Installs uv when needed, then the toolkit with `uv tool install`, then starts it. */
    fun install() {
        if (installJob?.isActive == true || !LocalToolkit.supported) return
        installJob = scope.launch {
            if (installNow()) start()
        }
    }

    /** The installation itself; true when `mvt` is there afterwards. */
    private suspend fun installNow(): Boolean {
            busy(Status.Installing)
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
                    return false
                }
                val src = settings.installSource.ifBlank { mlabeler.app.state.ToolkitSettings.DEFAULT_SOURCE }
                // --reinstall: rebuild even when the version number is the same (a local folder changes without one)
                val cmd = listOf(uv, "tool", "install", "--force", "--reinstall", "--python", "3.12", src)
                addLog("> " + cmd.joinToString(" "))
                val code = LocalToolkit.run(cmd, ::addLog)
                if (code != 0 || LocalToolkit.findMvt(settings.mvtPath) == null) {
                    status = Status.Failed
                    lastError = installFailed()
                    return false
                }
                addLog(installed())
                damaged = false
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = Status.Failed
                lastError = e.message ?: e.toString()
                return false
            }
    }

    val installing: Boolean get() = installJob?.isActive == true

    private fun startFailed(): String = log.takeLast(4).joinToString("\n").ifBlank { notStarted() }

    /** One line for the UI. */
    fun statusText(): String = when (status) {
        Status.Unknown, Status.Checking -> checking()
        Status.Ready -> ready.format(version.ifEmpty { "?" }) + if (ownProcess) " · " + startedHere() else ""
        Status.Starting -> starting()
        Status.Installing -> if (updatingNow) updating() else installingT()
        Status.Missing -> if (damaged) damagedT() else missing()
        Status.Off -> if (canRunHere) offHere() else offRemote()
        Status.Failed -> failed()
    }

    companion object {
        val checking = L("Checking the toolkit…", "Проверка тулкита…")
        val ready = L("mVocalToolkit {0} is ready", "mVocalToolkit {0} готов")
        val startedHere = L("started by mLabeler", "запущен mLabeler")
        val starting = L("The toolkit is starting…", "Тулкит запускается…")
        val installingT = L("Installing the toolkit… this downloads a few hundred MB", "Тулкит устанавливается… будет скачано несколько сотен МБ")
        val missing = L("mVocalToolkit isn't installed on this computer", "mVocalToolkit не установлен на этом компьютере")
        val offHere = L("The toolkit isn't running; it starts automatically when needed", "Тулкит не запущен; он запустится автоматически при необходимости")
        val offRemote = L("No answer from the toolkit at this address", "Тулкит по этому адресу не отвечает")
        val failed = L("The toolkit couldn't start", "Тулкит не смог запуститься")
        val notStarted = L("The toolkit stopped right after start", "Тулкит остановился сразу после запуска")
        val gettingUv = L("Installing uv (Python package manager)…", "Устанавливается uv (менеджер пакетов Python)…")
        val noUv = L("Couldn't install uv. Install it from astral.sh/uv and try again.", "Не удалось установить uv. Установите его с astral.sh/uv и повторите попытку.")
        val installFailed = L("Installation failed; see the log below", "Установка не удалась, подробности в журнале ниже")
        val installed = L("Installed", "Установлено")
        val updating = L("The toolkit is updating itself to a newer version and will start again…", "Тулкит обновляется до новой версии и запустится снова…")
        val startingAgain = L("The update is installed; starting the toolkit…", "Обновление установлено, тулкит запускается…")
        val updateFailed = L("The toolkit didn't come back after updating; see the log", "Тулкит не запустился после обновления, подробности в журнале")
        val damagedT = L(
            "mVocalToolkit's files were removed, only its launcher is left; install it again",
            "Файлы mVocalToolkit удалены, остался только его ярлык запуска; установите его заново",
        )
        /** What a launcher whose program is gone says: uv's trampoline, the Windows py launcher, Python itself. */
        private val DAMAGED = listOf(
            "trampoline", "canonicalize", "No Python at", "Unable to create process",
            "No module named 'mvocaltoolkit'", "No module named mvocaltoolkit",
        )
        const val EXIT_UPDATING = 75
    }
}

/**
 * A log written by PowerShell is UTF-16 (with a byte order mark, or plain), uv's own output is UTF-8.
 * Older toolkits updated through PowerShell, so both kinds turn up.
 */
internal fun ByteArray.decodeLog(): String {
    val bom = size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()
    // plain UTF-16 of mostly Latin text: every second byte is zero
    val zeros = (1 until minOf(size, 200) step 2).count { this[it] == 0.toByte() }
    val utf16 = bom || (size >= 4 && zeros * 2 >= minOf(size, 200) / 2)
    return if (utf16) decodeUtf16le() else decodeToString().removePrefix("\uFEFF")
}

private fun ByteArray.decodeUtf16le(): String {
    val start = if (size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) 2 else 0
    val chars = CharArray((size - start) / 2) { i -> ((this[start + 2 * i].toInt() and 0xFF) or ((this[start + 2 * i + 1].toInt() and 0xFF) shl 8)).toChar() }
    return chars.concatToString()
}

private val upToDate = L("The toolkit is up to date ({0})", "Тулкит последней версии ({0})")
private val updateUnsupported = L("This toolkit version can't update on request; it looks for a newer version each time it starts", "Эта версия тулкита не поддерживает обновление по запросу; она проверяет наличие новой версии при каждом запуске")
