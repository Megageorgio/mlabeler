package mlabeler.app.toolkit

actual fun finishToolkit(block: suspend () -> Unit) = Unit
actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpResponse = error("Toolkit is unavailable in the web edition")
actual object LocalToolkit {
    actual val supported = false
    actual val running = false
    actual fun findMvt(custom: String): String? = null
    actual fun findUv(): String? = null
    actual suspend fun run(command: List<String>, onLine: (String) -> Unit): Int = -1
    actual fun start(command: List<String>, onLine: (String) -> Unit) = false
    actual fun lastExitCode(): Int? = null
    actual fun stop() = Unit
    actual fun lanAddresses(): List<String> = emptyList()
    actual suspend fun installUv(onLine: (String) -> Unit) = false
}
