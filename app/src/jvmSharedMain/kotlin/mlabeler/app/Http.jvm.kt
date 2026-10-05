package mlabeler.app.toolkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpResponse =
    withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = timeoutMs
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        HttpResponse(code, bytes)
    }
