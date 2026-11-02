@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package mlabeler.core.io

/** Small synchronous view of the browser workspace; IndexedDB persistence lives in web-bridge.js. */
@JsName("MLabelerWeb")
external object WebBridge {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): String
    fun read(path: String): String
    fun write(path: String, binary: String)
    fun mkdirs(path: String)
    fun size(path: String): Double
    fun modified(path: String): Double
    fun delete(path: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun decode(binary: String, charset: String): String
    fun encode(text: String, charset: String): String
    fun normalize(text: String): String
}

fun ByteArray.toBinary(): String = CharArray(size) { (this[it].toInt() and 255).toChar() }.concatToString()
fun String.binaryBytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

actual fun decodeText(bytes: ByteArray, charset: String): String = WebBridge.decode(bytes.toBinary(), charset)
actual fun encodeText(text: String, charset: String): ByteArray = WebBridge.encode(text, charset).binaryBytes()
actual fun nfc(text: String): String = WebBridge.normalize(text)
actual val PlatformFs: FileSystem = object : FileSystem {
    override fun exists(path: String) = WebBridge.exists(path)
    override fun isDirectory(path: String) = WebBridge.isDirectory(path)
    override fun list(path: String): List<String> = kotlinx.serialization.json.Json.decodeFromString(WebBridge.list(path))
    override fun read(path: String): ByteArray = WebBridge.read(path).binaryBytes()
    override fun write(path: String, bytes: ByteArray) = WebBridge.write(path, bytes.toBinary())
    override fun mkdirs(path: String) = WebBridge.mkdirs(path)
    override fun size(path: String) = WebBridge.size(path).toLong()
    override fun lastModified(path: String) = WebBridge.modified(path).toLong()
    override fun delete(path: String) = WebBridge.delete(path)
    override fun rename(from: String, to: String) = WebBridge.rename(from, to)
}
