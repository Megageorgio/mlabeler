package mlabeler.app.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
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
    /** Extra data the script reads: "pitch" (the f0 of the recording). */
    val uses: List<String> = emptyList(),
)

class Plugin(val info: PluginInfo, val dir: String, val code: String, val builtIn: Boolean)

/**
 * What a plugin sees besides its data: the folder and its files, the marks of the open file, its pitch; and the
 * file system of the folder, which it may read and write (only inside the folder).
 */
class PluginContext(
    val folder: String,
    /** The recordings of the folder: name, labelled, done, star, tag. */
    val files: List<JsonObject> = emptyList(),
    /** Marks of the open file (labels plugins). */
    val marks: mlabeler.core.io.ItemMarks? = null,
    /** Marks of each oto entry, in the order of the entries (oto plugins). */
    val entryMarks: List<mlabeler.core.io.ItemMarks>? = null,
    val pitch: mlabeler.core.dsp.Curve? = null,
    val language: String = "en",
    val platform: String = "",
    /** Called before a file is overwritten by writeText (to keep a copy). */
    val backup: (String) -> Unit = {},
)

/**
 * Result of a run: changed data, a report to show, lines printed with log(), changed marks, a part to play
 * (start, end in seconds) and the files written.
 */
data class PluginResult(
    val doc: LabelDoc?, val entries: List<OtoEntry>?, val report: String?, val logs: List<String>,
    val marks: mlabeler.core.io.ItemMarks? = null, val entryMarks: List<mlabeler.core.io.ItemMarks>? = null,
    val play: Pair<Double, Double>? = null, val written: List<String> = emptyList(),
)

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

    fun notesToJson(t: mlabeler.core.model.NoteTier): JsonElement = buildJsonArray {
        for (n in t.notes) add(buildJsonObject {
            put("start", n.start); put("end", n.end); put("pitch", n.pitch?.let { JsonPrimitive(it) } ?: JsonNull)
            put("slur", n.slur); put("text", n.text)
        })
    }

    fun notesFromJson(e: JsonElement, name: String): mlabeler.core.model.NoteTier = mlabeler.core.model.NoteTier(name, e.jsonArray.map {
        val o = it.jsonObject
        mlabeler.core.model.Note(
            o["start"]!!.jsonPrimitive.content.toDouble(), o["end"]!!.jsonPrimitive.content.toDouble(),
            (o["pitch"] as? JsonPrimitive)?.doubleOrNull, (o["slur"] as? JsonPrimitive)?.content == "true", (o["text"] as? JsonPrimitive)?.content ?: "",
        )
    }.sortedBy { it.start })

    private fun marksToJson(m: mlabeler.core.io.ItemMarks) = buildJsonObject { put("done", m.done); put("star", m.star); put("tag", m.tag) }

    private fun marksFromJson(e: JsonElement?): mlabeler.core.io.ItemMarks? {
        val o = e as? JsonObject ?: return null
        return mlabeler.core.io.ItemMarks((o["done"] as? JsonPrimitive)?.content == "true", (o["star"] as? JsonPrimitive)?.content == "true",
            (o["tag"] as? JsonPrimitive)?.content ?: "")
    }

    /** [path] (relative to [folder], or absolute) when it is inside [folder], else null. */
    private fun inside(folder: String, path: String): String? {
        val p = path.replace('\\', '/')
        val abs = if (p.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(p)) p else Paths.join(folder, p).replace('\\', '/')
        val parts = mutableListOf<String>()
        for (s in abs.split('/')) when (s) { "", "." -> if (parts.isEmpty()) parts += s; ".." -> if (parts.size > 1) parts.removeAt(parts.lastIndex) else return null; else -> parts += s }
        val norm = parts.joinToString("/")
        val root = folder.replace('\\', '/').trimEnd('/')
        return norm.takeIf { it == root || it.startsWith("$root/") }
    }

    /**
     * Runs [plugin]. The script sees `params`, `labels` (tiers of the open file) or `entries` (oto, each with its
     * done, star and tag), `notes`, `marks` ({done, star, tag} of the file), `file` ({name, duration}), `folder`
     * ({path, files}), `env`, `pitch` ({hop, values} when the plugin uses it); it changes them in place, may set
     * `report` and `play` ([start, end]), call `log(...)`, and read and write text files of the folder with
     * readText, writeText, listFiles and exists.
     */
    suspend fun run(plugin: Plugin, params: Map<String, JsonElement>, doc: LabelDoc?, entries: List<OtoEntry>?, fileName: String, duration: Double,
                    ctx: PluginContext? = null): PluginResult {
        val js = QuickJs.create(Dispatchers.Default)
        val written = mutableListOf<String>()
        try {
            val folder = ctx?.folder
            if (folder != null) {
                js.function("readText") { args ->
                    val p = inside(folder, args.getOrNull(0)?.toString() ?: "") ?: return@function null
                    runCatching { mlabeler.core.io.decodeGuess(PlatformFs.read(p), args.getOrNull(1)?.toString() ?: "UTF-8").first }.getOrNull()
                }
                js.function("exists") { args -> inside(folder, args.getOrNull(0)?.toString() ?: "")?.let { PlatformFs.exists(it) } ?: false }
                js.function("listFiles") { args ->
                    val p = inside(folder, args.getOrNull(0)?.toString() ?: "") ?: return@function "[]"
                    val list = runCatching { PlatformFs.list(p) }.getOrDefault(emptyList()).map { Paths.name(it) }.filter { !it.startsWith(".") }.sorted()
                    JsonArray(list.map { JsonPrimitive(it) }).toString()
                }
                js.function("writeText") { args ->
                    val p = inside(folder, args.getOrNull(0)?.toString() ?: "") ?: throw IllegalArgumentException("writeText: only files inside the folder")
                    if (Paths.name(p).startsWith(".")) throw IllegalArgumentException("writeText: not a hidden file")
                    if (PlatformFs.exists(p)) ctx.backup(p)
                    PlatformFs.write(p, (args.getOrNull(1)?.toString() ?: "").encodeToByteArray())
                    written += p
                    true
                }
            }
            val code = buildString {
                append("var __logs = [];\n")
                append("function log() { __logs.push(Array.prototype.map.call(arguments, function (a) { return typeof a === 'string' ? a : JSON.stringify(a); }).join(' ')); }\n")
                append("var console = { log: log, warn: log, error: log };\n")
                if (folder != null) append("var __list = listFiles; listFiles = function (d) { return JSON.parse(__list(d || '')); };\n")
                append("var params = ").append(lit(JsonObject(params))).append(";\n")
                append("var labels = ").append(if (doc != null) lit(docToJson(doc)) else "null").append(";\n")
                val noteTier = doc?.tiers?.firstOrNull { it is mlabeler.core.model.NoteTier } as? mlabeler.core.model.NoteTier
                append("var notes = ").append(if (noteTier != null) lit(notesToJson(noteTier)) else "null").append(";\n")
                val withMarks = entries?.let { es ->
                    val m = ctx?.entryMarks
                    buildJsonArray {
                        for ((i, o) in otoToJson(es).jsonArray.withIndex()) add(JsonObject(o.jsonObject + (m?.getOrNull(i)?.let { marksToJson(it) } ?: marksToJson(mlabeler.core.io.ItemMarks()))))
                    }
                }
                append("var entries = ").append(if (withMarks != null) lit(withMarks) else "null").append(";\n")
                append("var marks = ").append(ctx?.marks?.let { lit(marksToJson(it)) } ?: "null").append(";\n")
                append("var file = ").append(lit(buildJsonObject { put("name", fileName); put("duration", duration) })).append(";\n")
                append("var folder = ").append(if (ctx != null) lit(buildJsonObject { put("path", ctx.folder); put("files", JsonArray(ctx.files)) }) else "null").append(";\n")
                append("var env = ").append(lit(buildJsonObject { put("platform", ctx?.platform ?: ""); put("language", ctx?.language ?: "en"); put("app", "mLabeler") })).append(";\n")
                val pitch = ctx?.pitch?.takeIf { "pitch" in plugin.info.uses }
                append("var pitch = ").append(if (pitch != null) lit(buildJsonObject {
                    put("hop", pitch.hop)
                    put("values", buildJsonArray { for (v in pitch.values) add(JsonPrimitive(if (v.isNaN() || v < 0f) 0f else v)) })
                }) else "null").append(";\n")
                append("var report = null;\nvar play = null;\n")
                append("(function () {\n").append(plugin.code).append("\n})();\n")
                append("JSON.stringify({ labels: labels, notes: notes, entries: entries, marks: marks, report: report, play: play, logs: __logs });\n")
            }
            val out: String = js.evaluate(code, plugin.info.name + ".js", false)
            val r = json.parseToJsonElement(out).jsonObject
            var newDoc = r["labels"]?.takeIf { it !is JsonNull && doc != null }?.let { docFromJson(it, doc!!, duration) }
            val noteIndex = doc?.tiers?.indexOfFirst { it is mlabeler.core.model.NoteTier } ?: -1
            val notesOut = r["notes"]?.takeIf { it !is JsonNull && doc != null }
            if (notesOut != null && doc != null) {
                val base = newDoc ?: doc
                val name = if (noteIndex >= 0) (doc.tiers[noteIndex] as mlabeler.core.model.NoteTier).name else "notes"
                val nt = notesFromJson(notesOut, name)
                val k = base.tiers.indexOfFirst { it is mlabeler.core.model.NoteTier }
                newDoc = if (k >= 0) base.replace(k, nt) else base.copy(tiers = base.tiers + nt)
            }
            val outEntries = r["entries"]?.takeIf { it !is JsonNull && entries != null }
            val play = (r["play"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
            return PluginResult(
                doc = newDoc,
                entries = outEntries?.let { otoFromJson(it) },
                report = (r["report"] as? JsonPrimitive)?.content?.takeIf { r["report"] !is JsonNull },
                logs = (r["logs"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                marks = marksFromJson(r["marks"]),
                entryMarks = (outEntries as? JsonArray)?.map { marksFromJson(it) ?: mlabeler.core.io.ItemMarks() },
                play = play,
                written = written,
            )
        } finally {
            js.close()
        }
    }
}
