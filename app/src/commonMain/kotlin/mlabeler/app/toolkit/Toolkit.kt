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
    /** Tells the toolkit this program uses it; returns the id for [ping] and [detach], null for older toolkits. */
    suspend fun attach(name: String): String? = runCatching {
        call("POST", "/clients", buildJsonObject { put("name", name) }, timeoutMs = 5_000).jsonObject["id"]?.jsonPrimitive?.content
    }.getOrNull()

    /** False when the toolkit doesn't know [id] (it was restarted): attach again. */
    suspend fun ping(id: String): Boolean = runCatching { call("POST", "/clients/$id/ping", timeoutMs = 5_000); true }.getOrDefault(false)

    /** Detaches; returns how many programs still use the toolkit, null when unknown. */
    suspend fun detach(id: String): Int? = runCatching {
        call("DELETE", "/clients/$id", timeoutMs = 2_000).jsonObject["clients"]?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull()

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

    /** The "refine" option of a request: the boundaries are refined after labelling ([r] null = not). */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putRefine(r: mlabeler.app.state.RefineSettings?) {
        if (r == null || r.model.isBlank()) return
        putJsonObject("refine") { put("model", r.model); put("mode", r.mode) }
    }

    /**
     * Starts refining the boundaries of [segments] (start, end, phoneme in seconds) of one uploaded file; returns
     * the job id. The result has the same phonemes with the boundaries moved.
     */
    suspend fun refine(fileId: String, segments: List<Triple<Double, Double, String>>, model: String, mode: String): String {
        val req = buildJsonObject {
            putJsonObject("input") {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("file_id", fileId)
                        put("segments", buildJsonArray {
                            for ((a, b, t) in segments) add(buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)); add(JsonPrimitive(t)) })
                        })
                    })
                })
            }
            put("model", model)
            put("mode", mode)
            putJsonObject("output") {
                put("formats", JsonArray(emptyList()))
                put("return_labels", true)
            }
        }
        return call("POST", "/refine", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** One job aligning many uploaded files, each with its phonemes (in the same order in the result). */
    suspend fun alignPhonemes(files: List<Pair<String, List<String>>>, model: String, language: String?): String {
        val req = buildJsonObject {
            putJsonObject("input") {
                put("items", buildJsonArray {
                    for ((fileId, phonemes) in files) add(buildJsonObject {
                        put("file_id", fileId)
                        put("phonemes", buildJsonArray { phonemes.forEach { add(JsonPrimitive(it)) } })
                    })
                })
            }
            put("model", model)
            if (language != null) put("language", language)
            put("transcribe", kotlinx.serialization.json.JsonNull)
            putJsonObject("output") {
                put("formats", JsonArray(emptyList()))
                put("return_labels", true)
            }
        }
        return call("POST", "/align", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /**
     * Starts alignment of [text] (words, or phonemes when [phonemes]); returns the job id. Empty text: the words are
     * first recognised with Whisper when [whisper], otherwise the toolkit reports that the text is missing.
     */
    suspend fun align(fileId: String, model: String, language: String?, text: String, phonemes: Boolean, whisper: Boolean = false,
                      refine: mlabeler.app.state.RefineSettings? = null): String {
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
            putRefine(refine)
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
                        options: mlabeler.app.state.WflSettings = mlabeler.app.state.WflSettings(),
                        refine: mlabeler.app.state.RefineSettings? = null): String {
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
            putRefine(refine)
            putJsonObject("output") {
                put("formats", JsonArray(emptyList()))
                put("return_labels", true)
            }
        }
        return call("POST", "/segment", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Starts separating the voice from the music of one uploaded file; returns the job id. */
    suspend fun separate(fileId: String): String {
        val req = buildJsonObject {
            putJsonObject("input") { put("items", buildJsonArray { add(buildJsonObject { put("file_id", fileId) }) }) }
        }
        return call("POST", "/separate", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Starts recognising the words of one uploaded file (phrases with times); returns the job id. */
    suspend fun transcribe(fileId: String, language: String?, prompt: String? = null): String {
        val req = buildJsonObject {
            putJsonObject("input") { put("items", buildJsonArray { add(buildJsonObject { put("file_id", fileId) }) }) }
            if (language != null) put("language", language)
            if (prompt != null) put("initial_prompt", prompt)
            put("frontend", false)
        }
        return call("POST", "/transcribe", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Starts singing an uploaded recording again with [f0] (Hz every [hop] s, 0 = none): "world" or "nsf". */
    suspend fun resynth(fileId: String, f0: FloatArray, hop: Double, method: String): String {
        val req = buildJsonObject {
            putJsonObject("input") { put("items", buildJsonArray { add(buildJsonObject { put("file_id", fileId) }) }) }
            put("f0", buildJsonArray { for (v in f0) add(JsonPrimitive(if (v.isNaN() || v < 0f) 0f else v)) })
            put("hop", hop)
            put("method", method)
        }
        return call("POST", "/resynth", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Downloads a result file of a job. */
    suspend fun download(path: String): ByteArray {
        val enc = buildString {
            for (b in path.encodeToByteArray()) {
                val ch = (b.toInt() and 0xFF).toChar()
                if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.~/") append(ch) else append('%' + (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
            }
        }
        val r = httpRequest("GET", "$base/files/download?path=$enc", headers(), null, 600_000)
        if (r.status !in 200..299) throw ToolkitException(errorText(r))
        return r.body
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
        fun labelOf(result: JsonObject, offset: Double, duration: Double, index: Int = 0): LabelDoc {
            val item = result["items"]!!.jsonArray.getOrNull(index)?.jsonObject ?: throw ToolkitException("no result")
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
