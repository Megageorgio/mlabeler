@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import mlabeler.core.audio.Audio
import mlabeler.core.io.binaryBytes
import mlabeler.core.mcfl.Mcfl

@JsName("MLabelerInference")
external object InferenceBridge {
    fun install(run: (String, Int, String, String, Boolean) -> Unit)
    fun config(): String
    fun weights(): String
    fun progress(value: Double)
    fun result(value: String)
    fun failure(message: String)
}

fun main() {
    val scope = MainScope()
    var model: Mcfl? = null
    InferenceBridge.install { binary, rate, language, knownJson, confidence ->
        scope.launch {
            try {
                val m = model ?: Mcfl(InferenceBridge.config(), InferenceBridge.weights().binaryBytes()).also { model = it }
                val bytes = binary.binaryBytes()
                val audio = Audio(rate, FloatArray(bytes.size / 4) { i ->
                    var bits = 0
                    for (k in 0..3) bits = bits or ((bytes[i * 4 + k].toInt() and 255) shl (8 * k))
                    Float.fromBits(bits)
                })
                val known = Json.decodeFromString<List<String>>(knownJson)
                val progress: (Double) -> Unit = { InferenceBridge.progress(it) }
                val result = if (known.isEmpty()) m.label(audio, language, threads = 1, confidence = confidence, progress = progress)
                    else m.align(audio, language, known, 1, confidence, progress)
                InferenceBridge.result(buildJsonArray {
                    for (s in result) add(buildJsonObject {
                        put("start", s.start); put("end", s.end); put("label", s.label)
                        s.confidence?.let { put("confidence", it) }
                    })
                }.toString())
            } catch (e: Throwable) { InferenceBridge.failure(e.message ?: "Inference failed") }
        }
    }
}
