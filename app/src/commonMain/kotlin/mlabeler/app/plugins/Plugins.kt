package mlabeler.app.plugins

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mlabeler.core.format.OtoEntry
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.Tier

/** A plugin parameter shown in the run dialog. */
@Serializable
data class PluginParam(
    val name: String,
    /** integer, float, boolean, string, text (multi-line), enum */
    val type: String = "string",
    val label: String = "",
    val labelRu: String = "",
    val default: JsonElement = JsonNull,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
)

/** plugin.json */
@Serializable
data class PluginInfo(
    val name: String,
    val title: String = "",
    val titleRu: String = "",
    val description: String = "",
    val descriptionRu: String = "",
    val version: String = "1",
    val author: String = "",
    /** "labels": works on the open file's tiers; "oto": on the entries of the open folder's oto.ini. */
    val target: String = "labels",
    val parameters: List<PluginParam> = emptyList(),
    val script: String = "main.js",
)

class Plugin(val info: PluginInfo, val dir: String, val code: String, val builtIn: Boolean)

/** Result of a run: changed data, a report to show, lines printed with log(). */
data class PluginResult(val doc: LabelDoc?, val entries: List<OtoEntry>?, val report: String?, val logs: List<String>)

object Plugins {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Plugins from the app's folder and the open workspace (".mlabeler/plugins"), plus the bundled ones. */
    fun load(dirs: List<String>): List<Plugin> {
        val out = BuiltInPlugins.all.map { (info, code) -> Plugin(info, "", code, true) }.toMutableList()
        for (d in dirs) {
            if (!PlatformFs.isDirectory(d)) continue
            for (p in PlatformFs.list(d).sorted()) {
                val manifest = Paths.join(p, "plugin.json")
                if (!PlatformFs.exists(manifest)) continue
                runCatching {
                    val info = json.decodeFromString(PluginInfo.serializer(), PlatformFs.read(manifest).decodeToString())
                    val code = PlatformFs.read(Paths.join(p, info.script)).decodeToString()
                    out.removeAll { it.info.name == info.name }
                    out += Plugin(info, p, code, false)
                }
            }
        }
        return out
    }

    // ---------- data to and from JSON ----------

    fun docToJson(doc: LabelDoc): JsonElement = buildJsonArray {
        for (t in doc.tiers) if (t is IntervalTier) add(buildJsonObject {
            put("name", t.name)
            put("intervals", buildJsonArray {
                for (i in 0 until t.size) add(buildJsonObject {
                    put("start", t.startOf(i)); put("end", t.endOf(i)); put("text", t.texts[i])
                })
            })
        })
    }

    fun docFromJson(e: JsonElement, original: LabelDoc, duration: Double): LabelDoc {
        val others = original.tiers.filter { it !is IntervalTier }
        val tiers: List<Tier> = e.jsonArray.map { t ->
            val o = t.jsonObject
            val items = o["intervals"]!!.jsonArray.map { iv ->
                val io = iv.jsonObject
                Triple(io["start"]!!.jsonPrimitive.content.toDouble(), io["end"]!!.jsonPrimitive.content.toDouble(), io["text"]?.jsonPrimitive?.content ?: "")
            }
            IntervalTier.fromIntervals(o["name"]?.jsonPrimitive?.content ?: "tier", items, duration)
        }
        return LabelDoc(tiers + others)
    }

    fun otoToJson(entries: List<OtoEntry>): JsonElement = buildJsonArray {
        for (e in entries) add(buildJsonObject {
            put("sample", e.sample); put("alias", e.alias); put("offset", e.offset); put("consonant", e.consonant)
            put("cutoff", e.cutoff); put("preutterance", e.preutterance); put("overlap", e.overlap)
        })
    }

    fun otoFromJson(e: JsonElement): List<OtoEntry> = e.jsonArray.map {
        val o = it.jsonObject
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        OtoEntry(o["sample"]!!.jsonPrimitive.content, o["alias"]?.jsonPrimitive?.content ?: "", d("offset"), d("consonant"), d("cutoff"), d("preutterance"), d("overlap"))
    }

    private fun lit(e: JsonElement) = e.toString()

    /**
     * Runs [plugin]. The script sees `params`, `labels` (tiers of the open file) or `entries` (oto),
     * `file` ({name, duration}); it changes them in place, may set `report`, and may call `log(...)`.
     */
    suspend fun run(plugin: Plugin, params: Map<String, JsonElement>, doc: LabelDoc?, entries: List<OtoEntry>?, fileName: String, duration: Double): PluginResult {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            val code = buildString {
                append("var __logs = [];\n")
                append("function log() { __logs.push(Array.prototype.map.call(arguments, function (a) { return typeof a === 'string' ? a : JSON.stringify(a); }).join(' ')); }\n")
                append("var console = { log: log, warn: log, error: log };\n")
                append("var params = ").append(lit(JsonObject(params))).append(";\n")
                append("var labels = ").append(if (doc != null) lit(docToJson(doc)) else "null").append(";\n")
                append("var entries = ").append(if (entries != null) lit(otoToJson(entries)) else "null").append(";\n")
                append("var file = ").append(lit(buildJsonObject { put("name", fileName); put("duration", duration) })).append(";\n")
                append("var report = null;\n")
                append("(function () {\n").append(plugin.code).append("\n})();\n")
                append("JSON.stringify({ labels: labels, entries: entries, report: report, logs: __logs });\n")
            }
            val out: String = js.evaluate(code, plugin.info.name + ".js", false)
            val r = json.parseToJsonElement(out).jsonObject
            return PluginResult(
                doc = r["labels"]?.takeIf { it !is JsonNull && doc != null }?.let { docFromJson(it, doc!!, duration) },
                entries = r["entries"]?.takeIf { it !is JsonNull && entries != null }?.let { otoFromJson(it) },
                report = (r["report"] as? JsonPrimitive)?.content?.takeIf { r["report"] !is JsonNull },
                logs = (r["logs"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        } finally {
            js.close()
        }
    }
}
