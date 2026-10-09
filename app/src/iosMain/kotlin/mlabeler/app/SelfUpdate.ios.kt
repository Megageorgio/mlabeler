package mlabeler.app

actual object SelfUpdate {
    actual val supported: Boolean = false
    actual fun canUse(download: String?): Boolean = false
    actual suspend fun prepare(download: String, tag: String, sha256: String?, progress: (Float) -> Unit) { error("not supported") }
    actual fun prepared(): String? = null
    actual fun discard() {}
    actual fun install(restart: Boolean): String? = "not supported"
    actual val installsOnClose: Boolean = false
}
