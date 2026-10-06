package mlabeler.app.plugins

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mlabeler.app.Platform
import mlabeler.core.check.Problem
import mlabeler.core.check.Severity
import mlabeler.core.edit.IntervalRef
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/**
 * Own checks: small JavaScript files in <data>/checks and <folder>/.mlabeler/checks, run after every change.
 * A script reads `labels` (tiers with intervals {start, end, text}) and `file` ({name, duration}) and calls
 * `error(tier, index, message)` or `warn(tier, index, message)`; tier is its name or number.
 */
object CheckScripts {
    class Script(val name: String, val path: String, val code: String)

    fun appDir(): String = Paths.join(Platform.dataDir(), "checks")
    fun folderDir(root: String): String = Paths.join(Paths.join(root, ".mlabeler"), "checks")

    fun load(root: String?): List<Script> = listOfNotNull(appDir(), root?.let { folderDir(it) }).flatMap { d ->
        runCatching { PlatformFs.list(d) }.getOrDefault(emptyList()).filter { Paths.ext(it).equals("js", true) }.sorted().mapNotNull { p ->
            runCatching { Script(Paths.stem(p), p, PlatformFs.read(p).decodeToString()) }.getOrNull()
        }
    }

    const val EXAMPLE = """// Example check. Every file with labels is checked after each change.
// labels: [{ name, intervals: [{ start, end, text }] }], file: { name, duration }
// error(tier, index, "message") or warn(...) marks an interval; tier is a name ("phones") or a number.

var MAX_PAUSE = 3      // seconds
var MAX_LENGTH = 15    // seconds of a whole recording

labels.forEach(function (tier) {
  tier.intervals.forEach(function (iv, i) {
    var pause = iv.text === "" || iv.text === "SP" || iv.text === "AP"
    if (pause && iv.end - iv.start > MAX_PAUSE) error(tier.name, i, "pause longer than " + MAX_PAUSE + " s")
  })
})
if (file.duration > MAX_LENGTH && labels.length > 0) warn(0, 0, "the recording is longer than " + MAX_LENGTH + " s")
"""

    fun writeExample(): String {
        val p = Paths.join(appDir(), "example.js")
        PlatformFs.mkdirs(appDir())
        if (!PlatformFs.exists(p)) PlatformFs.write(p, EXAMPLE.encodeToByteArray())
        return p
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Problems found by [scripts]; a script that fails reports its error on the first interval. */
    suspend fun run(scripts: List<Script>, doc: LabelDoc, fileName: String, duration: Double): List<Problem> {
        if (scripts.isEmpty()) return emptyList()
        val tiers = doc.tiers.withIndex().filter { it.value is IntervalTier }
        fun tierIndex(v: String): Int? = v.toIntOrNull()?.let { n -> tiers.getOrNull(n)?.index }
            ?: tiers.firstOrNull { it.value.let { t -> (t as IntervalTier).name == v } }?.index
        val out = ArrayList<Problem>()
        val js = QuickJs.create(Dispatchers.Default)
        try {
            val labels = Plugins.docToJson(doc).toString()
            val file = buildJsonObject { put("name", fileName); put("duration", duration) }.toString()
            for (s in scripts) {
                val code = buildString {
                    append("var __p = [];\n")
                    append("function __add(sev, t, i, m) { __p.push({ s: sev, t: String(t), i: i | 0, m: String(m) }); }\n")
                    append("function error(t, i, m) { __add('e', t, i, m); }\nfunction warn(t, i, m) { __add('w', t, i, m); }\n")
                    append("var labels = ").append(labels).append(";\nvar file = ").append(file).append(";\n")
                    append("(function () {\n").append(s.code).append("\n})();\nJSON.stringify(__p);\n")
                }
                val res = runCatching { js.evaluate<String>(code, s.name + ".js", false) }
                res.onFailure { e ->
                    val first = tiers.firstOrNull()?.index ?: return@onFailure
                    out += Problem(Problem.Kind.Script, IntervalRef(first, 0), Severity.Error, "${s.name}: ${e.message ?: e}")
                }
                res.onSuccess { text ->
                    for (p in json.parseToJsonElement(text).jsonArray) {
                        val o = p.jsonObject
                        val k = tierIndex(o["t"]!!.jsonPrimitive.content) ?: continue
                        val t = doc.tiers[k] as IntervalTier
                        val i = o["i"]!!.jsonPrimitive.int.coerceIn(0, (t.size - 1).coerceAtLeast(0))
                        out += Problem(Problem.Kind.Script, IntervalRef(k, i), if (o["s"]!!.jsonPrimitive.content == "e") Severity.Error else Severity.Warning, o["m"]!!.jsonPrimitive.content)
                    }
                }
            }
        } finally {
            js.close()
        }
        return out
    }
}
