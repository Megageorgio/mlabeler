package mlabeler.core.io

/** Decodes [bytes] with a charset name such as "UTF-8", "Shift_JIS", "GBK", "windows-1252". */
expect fun decodeText(bytes: ByteArray, charset: String): String

expect fun encodeText(text: String, charset: String): ByteArray

/** Charsets offered in the UI. */
val KNOWN_CHARSETS = listOf("UTF-8", "Shift_JIS", "GBK", "Big5", "EUC-KR", "windows-1251", "windows-1252", "ISO-8859-1")

/** True when [bytes] are valid UTF-8. */
fun isValidUtf8(bytes: ByteArray): Boolean {
    var i = 0
    val n = bytes.size
    while (i < n) {
        val b = bytes[i].toInt() and 0xFF
        val len = when {
            b < 0x80 -> 1
            b in 0xC2..0xDF -> 2
            b in 0xE0..0xEF -> 3
            b in 0xF0..0xF4 -> 4
            else -> return false
        }
        if (i + len > n) return false
        for (k in 1 until len) if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
        i += len
    }
    return true
}

/** Decodes text, guessing between UTF-8 (with or without BOM) and [fallback]. Returns the text and the charset. */
fun decodeGuess(bytes: ByteArray, fallback: String = "Shift_JIS"): Pair<String, String> {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return decodeText(bytes.copyOfRange(3, bytes.size), "UTF-8") to "UTF-8"
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return decodeText(bytes, "UTF-16LE").removePrefix("\uFEFF") to "UTF-16LE"
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return decodeText(bytes, "UTF-16BE").removePrefix("\uFEFF") to "UTF-16BE"
    }
    if (bytes.all { it >= 0 } || isValidUtf8(bytes)) return decodeText(bytes, "UTF-8") to "UTF-8"
    return decodeText(bytes, fallback) to fallback
}

/** Minimal file system used by the app: paths are platform strings with '/' or '\' separators. */
interface FileSystem {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    /** Children of a directory (full paths), unsorted. */
    fun list(path: String): List<String>
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray)
    fun mkdirs(path: String)
    fun size(path: String): Long
    fun lastModified(path: String): Long
    fun copy(from: String, to: String) = write(to, read(from))
}

expect val PlatformFs: FileSystem

object Paths {
    fun name(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    fun parent(path: String): String {
        val p = path.trimEnd('/', '\\')
        val i = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
        return if (i <= 0) p.take(i + 1) else p.substring(0, i)
    }
    fun stem(path: String): String = name(path).substringBeforeLast('.', name(path))
    fun ext(path: String): String = name(path).substringAfterLast('.', "").lowercase()
    fun join(dir: String, name: String): String {
        if (dir.isEmpty()) return name
        val sep = if (dir.contains('\\') && !dir.contains('/')) "\\" else "/"
        return if (dir.endsWith('/') || dir.endsWith('\\')) dir + name else dir + sep + name
    }
    fun withExt(path: String, ext: String): String = join(parent(path), stem(path) + "." + ext)
}
