package mlabeler.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads [url] into [to] (through a ".part" file, so a broken download never looks finished). */
internal suspend fun downloadFile(url: String, to: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    to.parentFile?.mkdirs()
    val part = File(to.path + ".part")
    val c = URL(url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = true
    c.connectTimeout = 15_000
    c.readTimeout = 60_000
    c.setRequestProperty("User-Agent", "mLabeler/${AppInfo.VERSION}")
    try {
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        if (total > 0 && done != total) throw IllegalStateException("incomplete download ($done of $total bytes)")
        to.delete()
        if (!part.renameTo(to)) { part.copyTo(to, overwrite = true); part.delete() }
    } finally {
        c.disconnect()
    }
}
