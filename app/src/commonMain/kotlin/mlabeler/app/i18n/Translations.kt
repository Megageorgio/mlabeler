package mlabeler.app.i18n

import mlabeler.app.i18n.tr.*

/** Interface text in languages other than English and Russian, keyed by the English text. */
object Translations {
    private val tables = HashMap<String, Map<String, String>>()
    private val number = Regex("\\d+")

    private fun chunks(code: String): Array<String>? = when (code) {
        "ja" -> TrJa.chunks
        "zh" -> TrZh.chunks
        "ko" -> TrKo.chunks
        "fr" -> TrFr.chunks
        "de" -> TrDe.chunks
        "es" -> TrEs.chunks
        "pt" -> TrPt.chunks
        else -> null
    }

    private fun table(code: String): Map<String, String>? = tables[code] ?: chunks(code)?.let { parts ->
        val map = HashMap<String, String>(2048)
        for (part in parts) for (rec in part.split('␞')) {
            val i = rec.indexOf('␟')
            if (i > 0) map[rec.substring(0, i).replace('␤', '\n')] = rec.substring(i + 1).replace('␤', '\n')
        }
        tables[code] = map
        map
    }

    fun get(code: String, en: String): String? {
        val t = table(code) ?: return null
        t[en]?.let { return it }
        val m = number.find(en) ?: return null
        return t[en.replaceRange(m.range, "{n}")]?.replace("{n}", m.value)
    }
}
