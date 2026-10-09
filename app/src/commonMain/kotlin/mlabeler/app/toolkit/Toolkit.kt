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
import kotlinx.serialization.json.intOrNull
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

    /** The toolkit's settings (secrets hidden). */
    suspend fun settings(): JsonObject = call("GET", "/settings").jsonObject

    /** Changes settings of the toolkit, e.g. "device" ("auto", "cpu", "cuda"); returns them all. */
    suspend fun changeSettings(values: Map<String, String>): JsonObject =
        call("POST", "/settings", buildJsonObject { for ((k, v) in values) put(k, v) }).jsonObject

    /** Jobs that haven't finished yet (queued, running, waiting). */
    suspend fun activeJobs(): Int = call("GET", "/jobs?active=true").jsonArray.size

    /** Ids of the programs using the toolkit. */
    suspend fun clients(): List<String> = call("GET", "/clients").jsonArray.mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.content }

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

    /** Removes an engine's environment (it is installed again when a job needs it). */
    suspend fun removeEngine(name: String) { call("DELETE", "/engines/$name", timeoutMs = 300_000) }

    /** Disk space of the toolkit's folder, in bytes; null from toolkits that don't tell. */
    data class Storage(
        val home: String, val total: Long, val models: List<Pair<String, Long>>, val engines: List<Pair<String, Long>>,
        val leftovers: Long, val engineDownloads: Long, val keepDays: Int?,
    )

    suspend fun storage(): Storage? {
        val o = runCatching { call("GET", "/storage", timeoutMs = 120_000).jsonObject }.getOrElse { e ->
            // an older toolkit has no such call
            if (e is ToolkitException && (e.message == "Not Found" || e.message == "HTTP 404")) return null
            throw e
        }
        fun list(k: String) = (o[k] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            it["id"]!!.jsonPrimitive.content to (it["bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
        }.sortedByDescending { it.second }
        fun long(e: JsonElement?) = (e as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
        return Storage(
            (o["home"] as? JsonPrimitive)?.content ?: "", long(o["total"]), list("models"), list("engines"),
            (o["temporary"] as? JsonObject)?.values?.sumOf { long(it) } ?: 0L, long(o["engine_downloads"]),
            (o["keep_files_days"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /** Removes the leftovers of jobs (uploads, results, history, unfinished downloads); returns the bytes freed. */
    suspend fun cleanupStorage(): Long =
        call("POST", "/storage/cleanup", buildJsonObject { }, timeoutMs = 300_000).jsonObject["freed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

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
     * [extraLanguages]: other languages in the text (TIFA models).
     */
    suspend fun align(fileId: String, model: String, language: String?, text: String, phonemes: Boolean, whisper: Boolean = false,
                      refine: mlabeler.app.state.RefineSettings? = null, extraLanguages: List<String> = emptyList(),
                      whisperModel: String? = null, skipUnknown: Boolean = false): String {
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
            if (extraLanguages.isNotEmpty()) put("extra_languages", buildJsonArray { extraLanguages.forEach { add(JsonPrimitive(it)) } })
            if (!whisper) put("transcribe", kotlinx.serialization.json.JsonNull)
            else if (!whisperModel.isNullOrBlank()) putJsonObject("transcribe") { put("model", whisperModel) }
            if (skipUnknown) put("skip_unknown_words", true)
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

    /** What an aligner model makes of a text: its words, the phonemes of each (null = unknown), the words it lacks. */
    data class TextPhonemes(val words: List<String>, val phonemes: List<List<String>?>, val unknown: List<String>, val guessed: Map<String, List<String>>)

    private fun textRequest(texts: List<String>, model: String, language: String?) = buildJsonObject {
        put("texts", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
        put("model", model)
        if (language != null) put("language", language)
    }

    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private fun textPhonemes(o: JsonObject) = TextPhonemes(
        strings(o["tokens"]),
        (o["phonemes"] as? JsonArray)?.map { p -> (p as? JsonArray)?.let { strings(it) } }.orEmpty(),
        strings(o["unknown_words"]),
        (o["guessed"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to strings(it) } }?.toMap().orEmpty(),
    )

    /**
     * Words and phonemes of [texts] by the dictionary of [model], its own words and a G2P for the rest
     * ([check]: only the words it lacks, with G2P guesses). The first call may install the model and its engine.
     */
    suspend fun phonemize(texts: List<String>, model: String, language: String?, check: Boolean = false): List<TextPhonemes> =
        call("POST", if (check) "/text/validate" else "/text/g2p", textRequest(texts, model, language), timeoutMs = 600_000)
            .jsonObject["items"]!!.jsonArray.map { textPhonemes(it.jsonObject) }

    /** The own words of [model] ({} from toolkits that keep none). */
    suspend fun words(model: String): Map<String, List<String>> = runCatching {
        call("GET", "/models/${model.encodeUrl()}/words").jsonObject.mapValues { (_, v) -> strings(v) }
    }.getOrElse { e -> if (e is ToolkitException && (e.message == "Not Found" || e.message == "HTTP 404" || e.message == "HTTP 405")) emptyMap() else throw e }

    suspend fun setWords(model: String, words: Map<String, List<String>>): Map<String, List<String>> {
        val body = buildJsonObject { for ((w, ph) in words) put(w, buildJsonArray { ph.forEach { add(JsonPrimitive(it)) } }) }
        return call("PUT", "/models/${model.encodeUrl()}/words", body).jsonObject.mapValues { (_, v) -> strings(v) }
    }

    private fun String.encodeUrl() = buildString {
        for (b in this@encodeUrl.encodeToByteArray()) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.~") append(ch) else append('%' + (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
        }
    }

    /** Starts separating the voice from the music of one uploaded file; returns the job id. */
    suspend fun separate(fileId: String, model: String = "auto"): String {
        val req = buildJsonObject {
            putJsonObject("input") { put("items", buildJsonArray { add(buildJsonObject { put("file_id", fileId) }) }) }
            put("model", model)
        }
        return call("POST", "/separate", req).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Starts recognising the words of one uploaded file (phrases with times); returns the job id. */
    suspend fun transcribe(fileId: String, language: String?, prompt: String? = null, model: String? = null): String {
        val req = buildJsonObject {
            if (model != null) put("model", model)
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

    /**
     * Waits for a job; [onProgress] gets 0..1 and the step in words, [onJob] all there is to show of it (the
     * numbers of a download, files done). Returns the result object.
     */
    suspend fun await(jobId: String, onJob: ((JobProgress) -> Unit)? = null, onProgress: (Double, String) -> Unit): JsonObject {
        while (true) {
            // a short wait: the numbers of a download change every second
            val info = call("GET", "/jobs/$jobId?wait=1").jsonObject
            val status = info["status"]!!.jsonPrimitive.content
            val p = JobProgress(
                info["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                (info["stage"] as? JsonPrimitive)?.content ?: "",
                (info["message"] as? JsonPrimitive)?.content ?: "",
                info["detail"] as? JsonObject,
            )
            onJob?.invoke(p)
            onProgress(p.fraction, p.stageName())
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

    /** Self-check of one aligned file (TIFA): how well the text and the phoneme spans fit the sound. */
    data class Diagnosis(val agreement: Double?, val confidence: Double?, val skipped: Int)

    companion object {
        /** The aligner's self-check of an item, when it gives one. */
        fun diagnosisOf(result: JsonObject, index: Int = 0): Diagnosis? {
            val item = result["items"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            val d = item["data"]?.jsonObject?.get("diagnosis") as? JsonObject ?: return null
            return Diagnosis(
                (d["agreement"] as? JsonPrimitive)?.doubleOrNull,
                (d["confidence"] as? JsonPrimitive)?.doubleOrNull,
                (d["skipped_phonemes"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

        /** Tiers of the first item's label, times shifted by [offset] seconds. */
        fun labelOf(result: JsonObject, offset: Double, duration: Double, index: Int = 0): LabelDoc {
            val item = result["items"]!!.jsonArray.getOrNull(index)?.jsonObject ?: throw ToolkitException("no result")
            if ((item["ok"] as? JsonPrimitive)?.content == "false") throw ToolkitException((item["error"] as? JsonPrimitive)?.content ?: "failed")
            val tiers = item["label"]?.jsonObject?.get("tiers")?.jsonObject ?: throw ToolkitException("no labels in the result")
            val order = listOf("texts", "words", "phones")
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
