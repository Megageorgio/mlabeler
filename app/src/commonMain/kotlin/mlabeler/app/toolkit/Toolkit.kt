package mlabeler.app.toolkit

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

class ToolkitException(message: String) : Exception(message)

data class ToolkitModel(val id: String, val name: String, val engine: String, val installed: Boolean)
data class ToolkitLanguage(val code: String, val name: String, val models: List<ToolkitModel>)

/** The few calls of mVocalToolkit's HTTP API that the editor needs. */
class ToolkitClient(baseUrl: String, private val token: String = "") {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private fun headers(extra: Map<String, String> = emptyMap()) =
        (if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()) + extra

    private suspend fun call(method: String, path: String, body: JsonElement? = null, timeoutMs: Int = 70_000): JsonElement {
        val r = try {
            httpRequest(method, base + path, headers(if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()),
                body?.toString()?.encodeToByteArray(), timeoutMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ToolkitException("Toolkit is not reachable at $base (${e.message})")
        }
        if (r.status !in 200..299) throw ToolkitException(errorText(r))
        return json.parseToJsonElement(r.text)
    }

    private fun errorText(r: HttpResponse): String = runCatching {
        json.parseToJsonElement(r.text).jsonObject["detail"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
    }.getOrNull() ?: "HTTP ${r.status}"

    /** Asks the toolkit to stop (one this program started). */
    suspend fun shutdown() {
        runCatching { call("POST", "/shutdown", timeoutMs = 2_000) }
    }

    suspend fun health(): JsonElement = call("GET", "/health", timeoutMs = 8_000)

    /** Asks the toolkit to update itself now: {"updating": true, "log": …} when it does (it then restarts). */
    suspend fun update(): JsonElement = call("POST", "/update", timeoutMs = 30_000)

    suspend fun languages(task: String = "align"): List<ToolkitLanguage> =
        call("GET", "/languages?task=$task").jsonObject["languages"]!!.jsonArray.map { g ->
            val o = g.jsonObject
            ToolkitLanguage(
                o["code"]!!.jsonPrimitive.content,
                (o["name"] ?: o["native_name"] ?: o["code"])!!.jsonPrimitive.content,
                o["models"]!!.jsonArray.map { m ->
                    val mo = m.jsonObject
                    ToolkitModel(
                        mo["id"]!!.jsonPrimitive.content,
                        (mo["name"] as? JsonPrimitive)?.content ?: mo["id"]!!.jsonPrimitive.content,
                        (mo["engine"] as? JsonPrimitive)?.content ?: "",
                        (mo["installed"] as? JsonPrimitive)?.content == "true",
                    )
                },
            )
        }

    /** A model on the toolkit's computer. */
    data class InstalledModel(val id: String, val name: String, val engine: String, val languages: List<String>, val source: String)

    suspend fun installedModels(): List<InstalledModel> = call("GET", "/models/installed").jsonArray.map { m ->
        val o = m.jsonObject
        InstalledModel(
            o["id"]!!.jsonPrimitive.content,
            (o["name"] as? JsonPrimitive)?.content ?: "",
            (o["engine"] as? JsonPrimitive)?.content ?: "",
            (o["languages"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            (o["source"] as? JsonPrimitive)?.content ?: "",
        )
    }

    /** Registers a model file, folder or archive at [path] on the toolkit's computer; returns the new model ids. */
    suspend fun importModel(engine: String, path: String, id: String?, name: String?, languages: List<String>): List<String> {
        val req = buildJsonObject {
            put("engine", engine); put("path", path)
            if (!id.isNullOrBlank()) put("id", id)
            if (!name.isNullOrBlank()) put("name", name)
            if (languages.isNotEmpty()) put("languages", buildJsonArray { languages.forEach { add(JsonPrimitive(it)) } })
        }
        return call("POST", "/models/import", req, timeoutMs = 600_000).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    suspend fun removeModel(id: String) { call("DELETE", "/models/$id") }

    /** Uploads a file, returns its id for input items. */
    suspend fun upload(name: String, bytes: ByteArray): String {
        val boundary = "----mlabeler" + bytes.size + name.hashCode()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = head.encodeToByteArray() + bytes + tail.encodeToByteArray()
        val r = httpRequest("POST", "$base/files", headers(mapOf("Content-Type" to "multipart/form-data; boundary=$boundary")), body)
        if (r.status !in 200..299) throw ToolkitException(errorText(r))
        return json.parseToJsonElement(r.text).jsonObject["file_id"]!!.jsonPrimitive.content
    }

    /** Starts forced alignment of one uploaded file; returns the job id. */
    /**
     * Starts alignment of [text] (words, or phonemes when [phonemes]); returns the job id. Empty text: the words are
     * first recognised with Whisper when [whisper], otherwise the toolkit reports that the text is missing.
     */
    suspend fun align(fileId: String, model: String, language: String?, text: String, phonemes: Boolean, whisper: Boolean = false): String {
        val req = buildJsonObject {
            putJsonObject("input") {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("file_id", fileId)
                        if (phonemes) put("phonemes", buildJsonArray { text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } })
                        else if (text.isNotBlank()) put("text", text)
                    })
                })
            }
            put("model", model)
            if (language != null) put("language", language)
            if (!whisper) put("transcribe", kotlinx.serialization.json.JsonNull)
            putJsonObject("output") {
                put("formats", JsonArray(emptyList()))
                put("return_labels", true)
            }
        }
        return call("POST", "/align", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /**
     * Starts phoneme recognition without lyrics (WFL-ASR models); returns the job id. With [phonemes] the model
     * places exactly these phonemes (forced alignment).
     */
    suspend fun segment(fileId: String, model: String, language: String? = null, phonemes: List<String> = emptyList(),
                        options: mlabeler.app.state.WflSettings = mlabeler.app.state.WflSettings()): String {
        val req = buildJsonObject {
            putJsonObject("input") {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("file_id", fileId)
                        if (phonemes.isNotEmpty()) put("phonemes", buildJsonArray { phonemes.forEach { add(JsonPrimitive(it)) } })
                    })
                })
            }
            put("model", model)
            if (language != null) put("language", language)
            if (options.confidence >= 0f) put("confidence_threshold", options.confidence.toDouble())
            put("decoder", options.decoder)
            put("viterbi_bias", options.viterbiBias.toDouble())
            put("silence_threshold", options.silenceThreshold.toDouble())
            put("min_silence_duration", options.minSilence.toDouble())
            putJsonObject("output") {
                put("formats", JsonArray(emptyList()))
                put("return_labels", true)
            }
        }
        return call("POST", "/segment", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Waits for a job; [onProgress] gets 0..1 and the stage. Returns the result object. */
    suspend fun await(jobId: String, onProgress: (Double, String) -> Unit): JsonObject {
        while (true) {
            val info = call("GET", "/jobs/$jobId?wait=5").jsonObject
            val status = info["status"]!!.jsonPrimitive.content
            onProgress(info["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0, (info["stage"] as? JsonPrimitive)?.content ?: "")
            when (status) {
                "done" -> return info["result"]!!.jsonObject
                "failed" -> throw ToolkitException((info["error"] as? JsonPrimitive)?.content ?: "failed")
                "cancelled" -> throw ToolkitException("cancelled")
                "paused" -> throw ToolkitException("the job waits for review, which this editor does not use")
            }
            delay(200)
        }
    }

    suspend fun cancel(jobId: String) {
        runCatching { call("POST", "/jobs/$jobId/cancel") }
    }

    companion object {
        /** Tiers of the first item's label, times shifted by [offset] seconds. */
        fun labelOf(result: JsonObject, offset: Double, duration: Double): LabelDoc {
            val item = result["items"]!!.jsonArray.firstOrNull()?.jsonObject ?: throw ToolkitException("no result")
            if ((item["ok"] as? JsonPrimitive)?.content == "false") throw ToolkitException((item["error"] as? JsonPrimitive)?.content ?: "failed")
            val tiers = item["label"]?.jsonObject?.get("tiers")?.jsonObject ?: throw ToolkitException("no labels in the result")
            val order = listOf("words", "phones")
            val names = tiers.keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
            return LabelDoc(names.map { name ->
                val ivs = tiers[name]!!.jsonArray.map { iv ->
                    val o = iv.jsonObject
                    Triple(o["start"]!!.jsonPrimitive.content.toDouble() + offset, o["end"]!!.jsonPrimitive.content.toDouble() + offset, o["text"]!!.jsonPrimitive.content)
                }
                val conf = tiers[name]!!.jsonArray.map { (it.jsonObject["confidence"] as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                IntervalTier.fromIntervals(name, ivs, offset + duration, if (conf.any { it != null }) conf else null)
            })
        }
    }
}
