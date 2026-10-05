package mlabeler.app.toolkit

/**
 * Runs mVocalToolkit on this computer as a child process (desktop only).
 * Phones and tablets don't run it: they connect to a computer where it runs.
 */
expect object LocalToolkit {
    /** True where the toolkit can be installed and started from the app. */
    val supported: Boolean
    val running: Boolean

    /** Path of the `mvt` command, looking at [custom] first, then PATH and the usual install folders. */
    fun findMvt(custom: String): String?

    /** Path of `uv`, or null. */
    fun findUv(): String?

    /** Runs a command to the end, passing its output lines; returns the exit code. */
    suspend fun run(command: List<String>, onLine: (String) -> Unit): Int

    /** Starts a long-running command (the server); output goes to [onLine]. */
    fun start(command: List<String>, onLine: (String) -> Unit): Boolean

    /** Stops the server started by [start], with its child processes. */
    fun stop()

    /** Addresses of this computer in the local network, for connecting from a phone. */
    fun lanAddresses(): List<String>

    /** The command that installs uv itself. */
    fun uvInstallCommand(): List<String>
}
