@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package mlabeler.app.toolkit

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpResponse =
    suspendCancellableCoroutine { cont ->
        val req = NSMutableURLRequest(uRL = NSURL(string = url))
        req.setHTTPMethod(method)
        req.setTimeoutInterval(timeoutMs / 1000.0)
        for ((k, v) in headers) req.setValue(v, forHTTPHeaderField = k)
        if (body != null) req.setHTTPBody(body.usePinned { NSData.dataWithBytes(it.addressOf(0), body.size.toULong()) })
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            if (error != null) {
                cont.resumeWithException(Exception(error.localizedDescription))
            } else {
                val n = data?.length?.toInt() ?: 0
                val bytes = ByteArray(n)
                if (n > 0) bytes.usePinned { memcpy(it.addressOf(0), data!!.bytes, data.length) }
                cont.resume(HttpResponse((response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0, bytes))
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
