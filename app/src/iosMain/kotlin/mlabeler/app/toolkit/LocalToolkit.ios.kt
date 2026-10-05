package mlabeler.app.toolkit

actual object LocalToolkit {
    actual val supported: Boolean = false
    actual val running: Boolean = false
    actual fun findMvt(custom: String): String? = null
    actual fun findUv(): String? = null
    actual suspend fun run(command: List<String>, onLine: (String) -> Unit): Int = -1
    actual fun start(command: List<String>, onLine: (String) -> Unit): Boolean = false
    actual fun stop() {}
    actual fun lanAddresses(): List<String> = emptyList()
    actual fun uvInstallCommand(): List<String> = emptyList()
}
