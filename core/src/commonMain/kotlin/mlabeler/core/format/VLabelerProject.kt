package mlabeler.core.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.core.io.ItemMarks
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/** What a vLabeler project (.lbp) holds, converted to this app's data. */
class VLabelerImport(
    val root: String,
    val labeler: String,
    /** Per module folder (absolute): oto entries, for oto labelers. */
    val oto: Map<String, List<OtoEntry>>,
    /** Per module folder and sample file name: label tiers, for lab-like labelers. */
    val labels: Map<String, Map<String, LabelDoc>>,
    /** Notes: key = "folder|sample|entry name". */
    val marks: Map<String, ItemMarks>,
)

object VLabelerProject {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun join(a: String, b: String): String {
        if (b.isEmpty()) return a
        if (b.startsWith("/") || (b.length > 1 && b[1] == ':')) return b
        return a.trimEnd('/', '\\') + "/" + b
    }

    fun read(text: String): VLabelerImport {
        val o = json.parseToJsonElement(text).jsonObject
        val root = o["rootSampleDirectory"]?.jsonPrimitive?.content ?: ""
        val conf = o["labelerConf"]?.jsonObject
        val labeler = conf?.get("name")?.jsonPrimitive?.content ?: ""
        val fields = conf?.get("fields")?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content } ?: emptyList()
        val isOto = "left" in fields && "preu" in fields
        val oto = mutableMapOf<String, List<OtoEntry>>()
        val labels = mutableMapOf<String, MutableMap<String, LabelDoc>>()
        val marks = mutableMapOf<String, ItemMarks>()
        for (m in o["modules"]?.jsonArray ?: emptyList()) {
            val mo = m.jsonObject
            val dir = join(root, mo["sampleDirectory"]?.jsonPrimitive?.content ?: "")
            val entries = mo["entries"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            for (e in entries) {
                val notes = e["notes"] as? JsonObject ?: continue
                val mk = ItemMarks(
                    done = notes["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                    star = notes["star"]?.jsonPrimitive?.booleanOrNull ?: false,
                    tag = notes["tag"]?.jsonPrimitive?.content ?: "",
                )
                if (mk != ItemMarks()) marks["$dir|${e["sample"]!!.jsonPrimitive.content}|${e["name"]!!.jsonPrimitive.content}"] = mk
            }
            if (isOto) {
                oto[dir] = entries.map { e ->
                    fun num(k: String) = (e[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    val pts = e["points"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyList()
                    fun pt(name: String) = pts.getOrNull(fields.indexOf(name)) ?: 0.0
                    val left = pt("left")
                    val end = num("end")
                    OtoEntry(
                        sample = e["sample"]!!.jsonPrimitive.content,
                        alias = e["name"]!!.jsonPrimitive.content,
                        offset = left,
                        consonant = pt("fixed") - left,
                        // end ≤ 0 is measured from the end of the file, which oto writes as a positive cutoff
                        cutoff = if (end > 0) -(end - left) else -end,
                        preutterance = pt("preu") - left,
                        overlap = pt("ovl") - left,
                    )
                }
            } else {
                val bySample = labels.getOrPut(dir) { mutableMapOf() }
                for ((sample, group) in entries.groupBy { it["sample"]!!.jsonPrimitive.content }) {
                    val items = group.map { e ->
                        Triple(e["start"]!!.jsonPrimitive.content.toDouble() / 1000, e["end"]!!.jsonPrimitive.content.toDouble() / 1000, e["name"]!!.jsonPrimitive.content)
                    }.filter { it.second > it.first }
                    bySample[sample] = LabelDoc(listOf(IntervalTier.fromIntervals("phones", items)))
                }
            }
        }
        return VLabelerImport(root, labeler, oto, labels, marks)
    }
}
