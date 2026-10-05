package mlabeler.app.toolkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

actual object LocalToolkit {
    private val windows = System.getProperty("os.name").lowercase().contains("win")
    private var process: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    actual val supported: Boolean = true
    actual val running: Boolean get() = process?.isAlive == true

    private fun exe(name: String) = if (windows) "$name.exe" else name

    private fun candidates(name: String): List<File> {
        val home = System.getProperty("user.home")
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, exe(name)) }
        val extra = buildList {
            add(File(home, ".local/bin/" + exe(name)))
            add(File(home, ".cargo/bin/" + exe(name)))
            System.getenv("XDG_BIN_HOME")?.let { add(File(it, exe(name))) }
            System.getenv("UV_TOOL_BIN_DIR")?.let { add(File(it, exe(name))) }
            if (windows) System.getenv("LOCALAPPDATA")?.let { add(File(it, "Programs/uv/" + exe(name))) }
            if (!windows) { add(File("/opt/homebrew/bin/$name")); add(File("/usr/local/bin/$name")) }
        }
        return path + extra
    }

    private fun firstExisting(files: List<File>) = files.firstOrNull { it.isFile && (windows || it.canExecute()) }?.absolutePath

    actual fun findMvt(custom: String): String? {
        if (custom.isNotBlank()) {
            val f = File(custom.trim())
            if (f.isFile) return f.absolutePath
            if (f.isDirectory) firstExisting(listOf(File(f, exe("mvt")), File(f, "bin/" + exe("mvt")), File(f, "Scripts/" + exe("mvt"))))?.let { return it }
        }
        return firstExisting(candidates("mvt"))
    }

    actual fun findUv(): String? = firstExisting(candidates("uv"))

    private fun builder(command: List<String>) = ProcessBuilder(command).redirectErrorStream(true).apply {
        environment()["PYTHONUNBUFFERED"] = "1"
        environment()["PYTHONIOENCODING"] = "utf-8"
    }

    actual suspend fun run(command: List<String>, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val p = builder(command).start()
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            p.waitFor()
        } catch (e: InterruptedException) {
            p.descendants().forEach { it.destroy() }
            p.destroy()
            -1
        }
    }

    actual fun start(command: List<String>, onLine: (String) -> Unit): Boolean {
        stop()
        val p = runCatching { builder(command).start() }.getOrElse { onLine(it.message ?: it.toString()); return false }
        process = p
        thread(isDaemon = true, name = "toolkit-output") {
            runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine) }
        }
        return true
    }

    actual fun stop() {
        val p = process ?: return
        process = null
        runCatching {
            p.descendants().forEach { it.destroy() }
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
            }
        }
    }

    actual fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
    }.getOrDefault(emptyList())

    actual fun uvInstallCommand(): List<String> =
        if (windows) listOf("powershell", "-NoProfile", "-ExecutionPolicy", "ByPass", "-Command", "irm https://astral.sh/uv/install.ps1 | iex")
        else listOf("sh", "-c", "curl -LsSf https://astral.sh/uv/install.sh | sh")
}
