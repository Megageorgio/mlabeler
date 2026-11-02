package mlabeler.core.io

import java.io.File
import java.nio.charset.Charset

actual fun decodeText(bytes: ByteArray, charset: String): String = String(bytes, Charset.forName(charset))

actual fun encodeText(text: String, charset: String): ByteArray = text.toByteArray(Charset.forName(charset))

actual fun nfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

/** Android may mount user-selected SAF trees; ordinary filesystem paths always keep using java.io. */
object JvmFileSystems {
    @Volatile var documents: FileSystem? = null
    fun isDocumentPath(path: String) = path.startsWith("saf:/")
    private fun forPath(path: String): FileSystem = if (isDocumentPath(path))
        checkNotNull(documents) { "Document access is unavailable; select the project folder again" } else LocalFileSystem
    val routed: FileSystem = object : FileSystem {
        override fun exists(path: String) = forPath(path).exists(path)
        override fun isDirectory(path: String) = forPath(path).isDirectory(path)
        override fun list(path: String) = forPath(path).list(path)
        override fun read(path: String) = forPath(path).read(path)
        override fun write(path: String, bytes: ByteArray) = forPath(path).write(path, bytes)
        override fun mkdirs(path: String) = forPath(path).mkdirs(path)
        override fun size(path: String) = forPath(path).size(path)
        override fun lastModified(path: String) = forPath(path).lastModified(path)
        override fun delete(path: String) = forPath(path).delete(path)
        override fun rename(from: String, to: String): Boolean =
            if (forPath(from) !== forPath(to)) false else forPath(from).rename(from, to)
    }
}

actual val PlatformFs: FileSystem = JvmFileSystems.routed

private object LocalFileSystem : FileSystem {
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
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun size(path: String) = File(path).length()
    override fun lastModified(path: String) = File(path).lastModified()
}
