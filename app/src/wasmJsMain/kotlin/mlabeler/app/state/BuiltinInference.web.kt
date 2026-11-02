@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package mlabeler.app.state

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import mlabeler.core.audio.Audio
import mlabeler.core.io.toBinary
import mlabeler.core.mcfl.Mcfl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@JsName("MLabelerWeb")
external object InferenceClient {
    fun infer(binary: String, rate: Int, language: String, known: String, confidence: Boolean, progress: (Double) -> Unit, done: (String) -> Unit, failed: (String) -> Unit)
    fun cancelInference()
}

actual suspend fun inferBuiltin(audio: Audio, language: String, known: List<String>, threads: Int, confidence: Boolean, progress: (Double) -> Unit): List<Mcfl.Segment> = suspendCancellableCoroutine { cont ->
    val bytes = ByteArray(audio.samples.size * 4)
    for (i in audio.samples.indices) {
        val bits = audio.samples[i].toBits()
        for (k in 0..3) bytes[4*i+k] = (bits ushr (8*k)).toByte()
    }
    InferenceClient.infer(bytes.toBinary(), audio.sampleRate, language, Json.encodeToString(known), confidence,
        { if (cont.isActive) progress(it) },
        { out ->
            if (cont.isActive) try {
                cont.resume(Json.parseToJsonElement(out).jsonArray.map { row ->
                    val o = row.jsonObject
                    Mcfl.Segment(o["start"]!!.jsonPrimitive.double, o["end"]!!.jsonPrimitive.double, o["label"]!!.jsonPrimitive.content, o["confidence"]?.jsonPrimitive?.floatOrNull)
                })
            } catch (e: Exception) { cont.resumeWithException(e) }
        }, { if (cont.isActive) cont.resumeWithException(IllegalStateException(it)) })
    cont.invokeOnCancellation { InferenceClient.cancelInference() }
}
