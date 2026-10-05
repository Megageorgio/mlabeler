package mlabeler.app.toolkit

class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/** Plain HTTP request; runs off the main thread. */
expect suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int = 70_000): HttpResponse
