package mlabeler.core.io

import java.io.File
import java.nio.charset.Charset

actual fun decodeText(bytes: ByteArray, charset: String): String = String(bytes, Charset.forName(charset))

actual fun encodeText(text: String, charset: String): ByteArray = text.toByteArray(Charset.forName(charset))

actual val PlatformFs: FileSystem = object : FileSystem {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun list(path: String): List<String> = File(path).listFiles()?.map { it.path } ?: emptyList()
    override fun read(path: String) = File(path).readBytes()
    override fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }
    override fun delete(path: String) = File(path).delete()
    override fun size(path: String) = File(path).length()
    override fun lastModified(path: String) = File(path).lastModified()
}
