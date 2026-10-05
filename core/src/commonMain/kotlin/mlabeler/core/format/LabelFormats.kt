package mlabeler.core.format

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.Note
import mlabeler.core.model.NoteTier
import mlabeler.core.model.PointTier
import mlabeler.core.model.Point
import kotlin.math.roundToLong

class FormatException(message: String) : Exception(message)

enum class LabelFormat(val extension: String, val title: String) {
    Lab("lab", "HTK lab"),
    TextGrid("TextGrid", "Praat TextGrid"),
    Audacity("txt", "Audacity labels"),
    Ds("ds", "DiffSinger .ds"),
    /** A DiffSinger transcriptions.csv holding the labels of many recordings. */
    DsCsv("csv", "DiffSinger transcriptions.csv"),
    ;

    /** Formats one file can be saved in on its own. */
    val standalone: Boolean get() = this != DsCsv

    companion object {
        fun byExtension(ext: String) = entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
    }
}

fun formatNumberPublic(value: Double, decimals: Int): String = formatNumber(value, decimals)

internal fun formatNumber(value: Double, decimals: Int): String {
    val neg = value < 0
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.abs(value * factor).roundToLong()
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(decimals, '0').trimEnd('0')
    val body = if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    return if (neg && scaled != 0L) "-$body" else body
}

/** HTK / Sinsy / NNSVS lab: "start end label" per line, times in 100 ns units. */
object HtkLab {
    private const val UNIT = 1e-7

    fun isTimed(text: String): Boolean = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.split(Regex("\\s+"))?.let { it.size >= 3 && it[0].toLongOrNull() != null && it[1].toLongOrNull() != null } ?: false

    fun read(text: String, tierName: String = "phones", duration: Double? = null): LabelDoc {
        val items = mutableListOf<Triple<Double, Double, String>>()
        for ((n, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) throw FormatException("Line ${n + 1}: expected \"start end label\"")
            val s = parts[0].toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad start time")
            val e = parts[1].toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad end time")
            items += Triple(s * UNIT, e * UNIT, parts[2])
        }
        return LabelDoc(listOf(IntervalTier.fromIntervals(tierName, items, duration)))
    }

    fun write(doc: LabelDoc, tierIndex: Int = doc.phonemeTierIndex()): String {
        val tier = doc.tiers.getOrNull(tierIndex) as? IntervalTier ?: throw FormatException("No interval tier to write")
        return buildString {
            for (i in 0 until tier.size) {
                val s = (tier.startOf(i) / UNIT).roundToLong()
                val e = (tier.endOf(i) / UNIT).roundToLong()
                append(s).append(' ').append(e).append(' ').append(tier.texts[i].ifEmpty { "pau" }).append('\n')
            }
        }
    }
}

/** Audacity label track export: "start\tend\tlabel" in seconds; point labels have start == end. */
object AudacityLabels {
    fun looksLike(text: String): Boolean = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("\\") }
        .take(3).toList().let { lines ->
            lines.isNotEmpty() && lines.all { l ->
                val p = l.split('\t')
                p.size >= 2 && p[0].toDoubleOrNull() != null && p[1].toDoubleOrNull() != null
            }
        }

    fun read(text: String, tierName: String = "labels", duration: Double? = null): LabelDoc {
        val items = mutableListOf<Triple<Double, Double, String>>()
        for ((n, raw) in text.lineSequence().withIndex()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("\\")) continue // "\" lines carry spectral selection
            val p = line.split('\t')
            if (p.size < 2) throw FormatException("Line ${n + 1}: expected \"start<TAB>end<TAB>label\"")
            val s = p[0].trim().toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad start time")
            val e = p[1].trim().toDoubleOrNull() ?: throw FormatException("Line ${n + 1}: bad end time")
            items += Triple(s, e, p.getOrElse(2) { "" })
        }
        return LabelDoc(listOf(IntervalTier.fromIntervals(tierName, items, duration)))
    }

    fun write(doc: LabelDoc, tierIndex: Int = doc.phonemeTierIndex(), skipEmpty: Boolean = true): String {
        val tier = doc.tiers.getOrNull(tierIndex) as? IntervalTier ?: throw FormatException("No interval tier to write")
        return buildString {
            for (i in 0 until tier.size) {
                if (skipEmpty && tier.texts[i].isEmpty()) continue
                append(formatNumber(tier.startOf(i), 6)).append('\t')
                append(formatNumber(tier.endOf(i), 6)).append('\t')
                append(tier.texts[i]).append('\n')
            }
        }
    }
}

/** Praat TextGrid, long ("ooTextFile") and short formats. Interval, point (TextTier) tiers. */
object TextGridFormat {
    private sealed interface Tok
    private data class Str(val v: String) : Tok
    private data class Num(val v: Double) : Tok
    private data object Flag : Tok // <exists>, <absent>

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n) {
                        if (text[i] == '"') {
                            if (i + 1 < n && text[i + 1] == '"') {
                                sb.append('"')
                                i += 2
                                continue
                            }
                            break
                        }
                        sb.append(text[i])
                        i++
                    }
                    i++
                    out += Str(sb.toString())
                }
                c == '!' -> while (i < n && text[i] != '\n') i++ // comment
                c == '<' -> {
                    while (i < n && text[i] != '>') i++
                    i++
                    out += Flag
                }
                c == '-' || c == '.' || c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isDigit() || text[i] in ".-+eE")) i++
                    val num = text.substring(start, i).toDoubleOrNull()
                    // numbers glued to letters belong to keys such as "item [1]:" – they still parse as numbers,
                    // keys are skipped by the reader below.
                    if (num != null) out += Num(num)
                }
                c.isLetter() -> {
                    // a key such as "xmin =" or "intervals [3]:"; "item [1]" numbers are keys too
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val word = text.substring(start, i)
                    // skip "[n]" after a key
                    var j = i
                    while (j < n && text[j] == ' ') j++
                    if (j < n && text[j] == '[') {
                        while (j < n && text[j] != ']') j++
                        i = j + 1
                    }
                    if (word == "exists" || word == "absent") out += Flag
                }
                else -> i++
            }
        }
        return out
    }

    fun read(text: String): LabelDoc {
        val t = tokenize(text)
        var p = 0
        fun str(): String = (t.getOrNull(p++) as? Str)?.v ?: throw FormatException("TextGrid: expected text at token $p")
        fun num(): Double = (t.getOrNull(p++) as? Num)?.v ?: throw FormatException("TextGrid: expected number at token $p")
        val fileType = str()
        if (!fileType.startsWith("ooTextFile")) throw FormatException("Not a TextGrid file")
        val objClass = str()
        if (objClass != "TextGrid") throw FormatException("Not a TextGrid file")
        num()
        val xmax = num()
        if (t.getOrNull(p) == Flag) p++ else throw FormatException("TextGrid has no tiers")
        val count = num().toInt()
        val tiers = mutableListOf<mlabeler.core.model.Tier>()
        repeat(count) {
            val cls = str()
            val name = str()
            val tmin = num()
            val tmax = num()
            val size = num().toInt()
            when (cls) {
                "IntervalTier" -> {
                    val items = ArrayList<Triple<Double, Double, String>>(size)
                    repeat(size) { items += Triple(num(), num(), str()) }
                    val tier = IntervalTier.fromIntervals(name, items, maxOf(tmax, xmax))
                    tiers += if (tier.start > tmin + 1e-9) {
                        IntervalTier(name, listOf(tmin) + tier.bounds, listOf("") + tier.texts)
                    } else {
                        tier
                    }
                }
                "TextTier" -> {
                    val points = ArrayList<Point>(size)
                    repeat(size) { points += Point(num(), str()) }
                    tiers += PointTier(name, points)
                }
                else -> throw FormatException("Unknown tier class \"$cls\"")
            }
        }
        return LabelDoc(tiers)
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun write(doc: LabelDoc, duration: Double? = null): String {
        val xmax = maxOf(duration ?: 0.0, doc.end)
        val tiers = doc.tiers.filter { it !is NoteTier } + doc.tiers.filterIsInstance<NoteTier>().map { noteTierAsPoints(it) }
        fun f(v: Double) = formatNumber(v, 7)
        return buildString {
            append("File type = \"ooTextFile\"\nObject class = \"TextGrid\"\n\n")
            append("xmin = 0 \nxmax = ${f(xmax)} \ntiers? <exists> \nsize = ${tiers.size} \nitem []: \n")
            for ((k, tier) in tiers.withIndex()) {
                append("    item [${k + 1}]:\n")
                when (tier) {
                    is IntervalTier -> {
                        val last = maxOf(tier.end, xmax)
                        append("        class = \"IntervalTier\" \n        name = ${q(tier.name)} \n")
                        append("        xmin = ${f(tier.start)} \n        xmax = ${f(last)} \n")
                        val extra = last > tier.end + 1e-9
                        append("        intervals: size = ${tier.size + if (extra) 1 else 0} \n")
                        for (i in 0 until tier.size) {
                            append("        intervals [${i + 1}]:\n")
                            append("            xmin = ${f(tier.startOf(i))} \n            xmax = ${f(tier.endOf(i))} \n")
                            append("            text = ${q(tier.texts[i])} \n")
                        }
                        if (extra) {
                            append("        intervals [${tier.size + 1}]:\n")
                            append("            xmin = ${f(tier.end)} \n            xmax = ${f(last)} \n            text = \"\" \n")
                        }
                    }
                    is PointTier -> {
                        append("        class = \"TextTier\" \n        name = ${q(tier.name)} \n")
                        append("        xmin = 0 \n        xmax = ${f(xmax)} \n        points: size = ${tier.points.size} \n")
                        for ((i, pt) in tier.points.withIndex()) {
                            append("        points [${i + 1}]:\n            number = ${f(pt.time)} \n            mark = ${q(pt.text)} \n")
                        }
                    }
                    is NoteTier -> Unit
                }
            }
        }
    }

    private fun noteTierAsPoints(t: NoteTier) = PointTier(t.name, t.notes.map { Point(it.start, it.pitch?.toString() ?: "rest") })
}

/** Note names used by DiffSinger: "C4", "D#3", "Bb2", "rest", optionally with cents "C4+12". */
object NoteNames {
    private val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val flats = mapOf("Db" to 1, "Eb" to 3, "Gb" to 6, "Ab" to 8, "Bb" to 10, "Cb" to -1, "Fb" to 4)

    fun parse(s: String): Double? {
        if (s.equals("rest", true) || s.isEmpty()) return null
        val m = Regex("^([A-Ga-g])([#b]?)(-?\\d+)([+-]\\d+)?$").matchEntire(s.trim()) ?: return null
        val letter = m.groupValues[1].uppercase()
        val acc = m.groupValues[2]
        val octave = m.groupValues[3].toInt()
        val base = when (acc) {
            "#" -> names.indexOf("$letter#").takeIf { it >= 0 } ?: (names.indexOf(letter) + 1)
            "b" -> flats["${letter}b"] ?: (names.indexOf(letter) - 1)
            else -> names.indexOf(letter)
        }
        val cents = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return (octave + 1) * 12 + base + cents / 100.0
    }

    fun format(pitch: Double?): String {
        if (pitch == null) return "rest"
        val r = kotlin.math.round(pitch).toInt()
        val cents = kotlin.math.round((pitch - r) * 100).toInt()
        val name = names[((r % 12) + 12) % 12] + (r / 12 - 1)
        return when {
            cents > 0 -> "$name+$cents"
            cents < 0 -> "$name$cents"
            else -> name
        }
    }
}

/** DiffSinger transcriptions.csv. One row per item: name,ph_seq,ph_dur[,ph_num][,note_seq,note_dur,note_slur]. */
object DsCsv {
    data class Row(val name: String, val doc: LabelDoc)

    fun read(text: String): List<Row> {
        val lines = Csv.parse(text)
        if (lines.isEmpty()) return emptyList()
        val header = lines[0].map { it.trim() }
        fun col(name: String) = header.indexOf(name)
        val cName = col("name")
        val cSeq = col("ph_seq")
        val cDur = col("ph_dur")
        if (cName < 0 || cSeq < 0 || cDur < 0) throw FormatException("transcriptions.csv needs name, ph_seq and ph_dur")
        val cNum = col("ph_num")
        val cNote = col("note_seq")
        val cNoteDur = col("note_dur")
        val cSlur = col("note_slur")
        return lines.drop(1).filter { it.size > cDur }.map { row ->
            val phs = row[cSeq].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val durs = row[cDur].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDouble() }
            if (phs.size != durs.size) throw FormatException("${row[cName]}: ph_seq and ph_dur differ in length")
            val bounds = ArrayList<Double>(phs.size + 1)
            bounds += 0.0
            for (d in durs) bounds += bounds.last() + d
            val tiers = mutableListOf<mlabeler.core.model.Tier>()
            val nums = if (cNum >= 0) row.getOrNull(cNum)?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } else null
            if (nums != null && nums.isNotEmpty() && nums.sum() == phs.size) {
                val wb = ArrayList<Double>(nums.size + 1)
                val wt = ArrayList<String>(nums.size)
                var k = 0
                wb += 0.0
                for (c in nums) {
                    val group = phs.subList(k, k + c)
                    wt += if (group.size == 1 && group[0] in setOf("SP", "AP")) group[0] else ""
                    k += c
                    wb += bounds[k]
                }
                tiers += IntervalTier("words", wb, wt)
            }
            tiers += IntervalTier("phones", bounds, phs)
            if (cNote >= 0 && cNoteDur >= 0 && row.size > cNoteDur) {
                val ns = row[cNote].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val nd = row[cNoteDur].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDouble() }
                val sl = if (cSlur >= 0) row.getOrNull(cSlur)?.trim()?.split(Regex("\\s+"))?.map { it == "1" } else null
                var t = 0.0
                val notes = ns.zip(nd).mapIndexed { i, (n, d) ->
                    Note(t, t + d, NoteNames.parse(n), sl?.getOrNull(i) ?: false).also { t += d }
                }
                tiers += NoteTier("notes", notes)
            }
            Row(row[cName].trim(), LabelDoc(tiers))
        }
    }

    fun write(rows: List<Row>): String {
        val withNotes = rows.any { r -> r.doc.tiers.any { it is NoteTier } }
        val withNum = rows.any { r -> r.doc.wordTierIndex() >= 0 }
        val header = buildList {
            add("name"); add("ph_seq"); add("ph_dur")
            if (withNum) add("ph_num")
            if (withNotes) { add("note_seq"); add("note_dur"); add("note_slur") }
        }
        val out = mutableListOf(header)
        for (r in rows) {
            val ph = r.doc.tiers[r.doc.phonemeTierIndex()] as IntervalTier
            val line = mutableListOf(r.name, ph.texts.joinToString(" "), (0 until ph.size).joinToString(" ") { formatNumber(ph.durationOf(it), 6) })
            if (withNum) line += phNum(r.doc).joinToString(" ")
            if (withNotes) {
                val notes = r.doc.tiers.filterIsInstance<NoteTier>().firstOrNull()?.notes.orEmpty()
                line += notes.joinToString(" ") { NoteNames.format(it.pitch) }
                line += notes.joinToString(" ") { formatNumber(it.end - it.start, 6) }
                line += notes.joinToString(" ") { if (it.slur) "1" else "0" }
            }
            out += line
        }
        return Csv.write(out)
    }

    /** Phoneme counts per word interval; phonemes are assigned to the word containing their middle. */
    fun phNum(doc: LabelDoc): List<Int> {
        val ph = doc.tiers[doc.phonemeTierIndex()] as IntervalTier
        val wi = doc.wordTierIndex()
        if (wi < 0) return List(ph.size) { 1 }
        val w = doc.tiers[wi] as IntervalTier
        val counts = IntArray(w.size)
        for (i in 0 until ph.size) {
            val mid = (ph.startOf(i) + ph.endOf(i)) / 2
            val k = w.indexAt(mid).coerceIn(0, w.size - 1)
            counts[k]++
        }
        return counts.filter { it > 0 }
    }
}

object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += cell.toString(); cell.clear() }
                '\r' -> Unit
                '\n' -> { row += cell.toString(); cell.clear(); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); if (row.any { it.isNotEmpty() }) rows += row }
        return rows
    }

    fun write(rows: List<List<String>>): String = buildString {
        for (r in rows) {
            append(r.joinToString(",") { v -> if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v })
            append('\n')
        }
    }
}
