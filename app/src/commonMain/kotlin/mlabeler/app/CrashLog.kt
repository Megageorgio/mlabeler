package mlabeler.app

import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs

/**
 * The report of the last crash: written by the platform's handler of uncaught errors, shown once on the next start
 * so that it can be copied and sent.
 */
object CrashLog {
    fun path(): String = Paths.join(Platform.dataDir(), "crash.txt")

    fun write(text: String) {
        runCatching { PlatformFs.write(path(), ("mLabeler ${AppInfo.VERSION} (${Platform.name})\n" + text).encodeToByteArray()) }
    }

    /** The report left by the previous run, if any; it is removed once read. */
    fun take(): String? = runCatching {
        val p = path()
        if (!PlatformFs.exists(p)) return null
        val text = PlatformFs.read(p).decodeToString()
        PlatformFs.delete(p)
        text.takeIf { it.isNotBlank() }
    }.getOrNull()
}
