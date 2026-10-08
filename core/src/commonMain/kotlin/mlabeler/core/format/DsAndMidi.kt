package mlabeler.core.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.Note
import mlabeler.core.model.NoteTier
import mlabeler.core.model.Tier

/** DiffSinger .ds: a JSON list of sentences with offsets; read as one timeline, written back into the same sentences. */
object DsFile {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; prettyPrint = true }

    private fun words(s: JsonElement?) = (s as? JsonPrimitive)?.content?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    fun read(text: String, duration: Double? = null): LabelDoc {
        val root = json.parseToJsonElement(text)
        val sentences = if (root is JsonArray) root.map { it.jsonObject } else listOf(root.jsonObject)
        val ph = mutableListOf<Triple<Double, Double, String>>()
        val wd = mutableListOf<Triple<Double, Double, String>>()
        val notes = mutableListOf<Note>()
        var hasNum = false
        var hasNotes = false
        for (s in sentences) {
            val off = (s["offset"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val seq = words(s["ph_seq"])
            val dur = words(s["ph_dur"]).map { it.toDouble() }
            var t = off
            val starts = ArrayList<Double>()
            for ((i, p) in seq.withIndex()) {
                val d = dur.getOrElse(i) { 0.0 }
                starts += t
                ph += Triple(t, t + d, p)
                t += d
            }
            starts += t
            val num = words(s["ph_num"]).mapNotNull { it.toIntOrNull() }
            if (num.isNotEmpty() && num.sum() == seq.size) {
                hasNum = true
                var k = 0
                for (c in num) {
                    val g = seq.subList(k, k + c)
                    wd += Triple(starts[k], starts[k + c], if (g.size == 1 && g[0] in setOf("SP", "AP")) g[0] else "")
                    k += c
                }
            }
            val ns = words(s["note_seq"])
            val nd = words(s["note_dur"]).map { it.toDouble() }
            val sl = words(s["note_slur"])
            if (ns.isNotEmpty()) {
                hasNotes = true
                var nt = off
                for ((i, n) in ns.withIndex()) {
                    val d = nd.getOrElse(i) { 0.0 }
                    notes += Note(nt, nt + d, NoteNames.parse(n), sl.getOrNull(i) == "1")
                    nt += d
                }
            }
        }
        val tiers = mutableListOf<Tier>()
        if (hasNum) tiers += IntervalTier.fromIntervals("words", wd, duration)
        tiers += IntervalTier.fromIntervals("phones", ph, duration)
        if (hasNotes) tiers += NoteTier("notes", notes)
        return LabelDoc(tiers)
    }

    private fun fmt(v: Double) = formatNumber(v, 6)

    private val managed = setOf("offset", "ph_seq", "ph_dur", "ph_num", "note_seq", "note_dur", "note_slur")

    /**
     * Writes [doc] back into the sentences of [previous] (when given): each sentence keeps its offset and every
     * field mlabeler doesn't edit (f0, text, gender…), and gets the phonemes, groups and notes that lie in its time
     * span. Without [previous], one sentence that starts at the first phoneme.
     */
    fun write(doc: LabelDoc, previous: String? = null): String {
        val ph = doc.tiers[doc.phonemeTierIndex()] as IntervalTier
        val words = doc.wordTierIndex().takeIf { it >= 0 }?.let { doc.tiers[it] as IntervalTier }
        val notes = doc.tiers.filterIsInstance<NoteTier>().firstOrNull()
        val old: List<JsonObject> = previous?.let { p ->
            runCatching {
                val r = json.parseToJsonElement(p)
                if (r is JsonArray) r.map { it.jsonObject } else listOf(r.jsonObject)
            }.getOrNull()
        }.orEmpty().sortedBy { (it["offset"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        val firstNamed = (0 until ph.size).firstOrNull { ph.texts[it].isNotBlank() }?.let { ph.startOf(it) } ?: 0.0
        val sentences = old.ifEmpty { listOf(JsonObject(mapOf("offset" to JsonPrimitive(firstNamed)))) }
        val offsets = sentences.map { (it["offset"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        val out = sentences.mapIndexed { si, sentence ->
            val from = if (si == 0) Double.NEGATIVE_INFINITY else offsets[si]
            val to = offsets.getOrNull(si + 1) ?: Double.POSITIVE_INFINITY
            val off = offsets[si]
            // phonemes whose middle is in this sentence; gaps at its ends are left out, gaps inside become SP
            val idx = (0 until ph.size).filter { val m = (ph.startOf(it) + ph.endOf(it)) / 2; m >= from && m < to }
            val first = idx.indexOfFirst { ph.texts[it].isNotBlank() }
            val last = idx.indexOfLast { ph.texts[it].isNotBlank() }
            val take = if (first < 0) emptyList() else idx.subList(first, last + 1)
            val seq = take.map { ph.texts[it].ifBlank { "SP" } }
            val dur = take.mapIndexed { k, i -> ph.endOf(i) - (if (k == 0) off else ph.startOf(i)) }.map { it.coerceAtLeast(0.0) }
            val fields = LinkedHashMap<String, JsonElement>()
            fields["offset"] = JsonPrimitive(off)
            sentence["text"]?.let { fields["text"] = it }
            fields["ph_seq"] = JsonPrimitive(seq.joinToString(" "))
            fields["ph_dur"] = JsonPrimitive(dur.joinToString(" ") { fmt(it) })
            if (words != null && (sentence.containsKey("ph_num") || old.isEmpty())) {
                val nums = ArrayList<Int>()
                var lastWord = -2
                for (i in take) {
                    val w = words.indexAt((ph.startOf(i) + ph.endOf(i)) / 2)
                    if (w != lastWord || nums.isEmpty()) { nums += 1; lastWord = w } else nums[nums.size - 1]++
                }
                fields["ph_num"] = JsonPrimitive(nums.joinToString(" "))
            } else sentence["ph_num"]?.let { fields["ph_num"] = it }
            if (notes != null && (sentence.containsKey("note_seq") || old.isEmpty())) {
                val ns = notes.notes.filter { val m = (it.start + it.end) / 2; m >= from && m < to }
                fields["note_seq"] = JsonPrimitive(ns.joinToString(" ") { NoteNames.format(it.pitch) })
                fields["note_dur"] = JsonPrimitive(ns.mapIndexed { k, n -> n.end - (if (k == 0) off else n.start) }.joinToString(" ") { fmt(it.coerceAtLeast(0.0)) })
                fields["note_slur"] = JsonPrimitive(ns.joinToString(" ") { if (it.slur) "1" else "0" })
            } else for (k in listOf("note_seq", "note_dur", "note_slur")) sentence[k]?.let { fields[k] = it }
            for ((k, v) in sentence) if (k !in managed && k != "text") fields[k] = v
            JsonObject(fields)
        }
        return json.encodeToString(JsonElement.serializer(), JsonArray(out))
    }
}

/** Standard MIDI files: notes in, notes out. */
object Midi {
    private const val PPQ = 480

    private fun vlq(v: Int): ByteArray {
        var x = v
        val bytes = ArrayList<Byte>()
        bytes += (x and 0x7F).toByte()
        x = x shr 7
        while (x > 0) { bytes.add(0, ((x and 0x7F) or 0x80).toByte()); x = x shr 7 }
        return bytes.toByteArray()
    }

    /** One track, tempo [bpm]; lyrics from [lyrics] (by note index) when given. */
    fun write(notes: NoteTier, bpm: Double = 120.0, lyrics: List<String>? = null): ByteArray {
        val secPerTick = 60.0 / bpm / PPQ
        val events = ArrayList<Pair<Int, ByteArray>>()
        val usPerQuarter = (60_000_000 / bpm).toInt()
        events += 0 to byteArrayOf(0xFF.toByte(), 0x51, 3, (usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte())
        for ((i, n) in notes.notes.withIndex()) {
            val p = n.pitch ?: continue
            val key = kotlin.math.round(p).toInt().coerceIn(0, 127)
            val on = (n.start / secPerTick).toInt()
            val off = (n.end / secPerTick).toInt().coerceAtLeast(on + 1)
            lyrics?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { l ->
                val b = l.encodeToByteArray()
                events += on to (byteArrayOf(0xFF.toByte(), 0x05) + vlq(b.size) + b)
            }
            events += on to byteArrayOf(0x90.toByte(), key.toByte(), 100)
            events += off to byteArrayOf(0x80.toByte(), key.toByte(), 0)
        }
        // note-offs before note-ons at the same tick
        events.sortWith(compareBy<Pair<Int, ByteArray>>({ it.first }, { if ((it.second[0].toInt() and 0xF0) == 0x80) 0 else 1 }))
        val track = ArrayList<Byte>()
        var last = 0
        for ((t, e) in events) { track.addAll(vlq(t - last).toList()); track.addAll(e.toList()); last = t }
        track.addAll(listOf(0, 0xFF.toByte(), 0x2F, 0))
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        return "MThd".encodeToByteArray() + be32(6) + be16(0) + be16(1) + be16(PPQ) +
            "MTrk".encodeToByteArray() + be32(track.size) + track.toByteArray()
    }

    /** Notes of all tracks (tempo changes followed), gaps filled with rests up to [duration]. */
    fun read(bytes: ByteArray, duration: Double? = null): NoteTier {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        if (bytes.size < 14 || bytes.decodeToString(0, 4) != "MThd") throw FormatException("Not a MIDI file")
        val division = (u8(12) shl 8) or u8(13)
        var pos = 8 + be32(4)
        val tempos = mutableListOf(0 to 500_000) // tick to microseconds per quarter
        val raw = mutableListOf<Triple<Int, Int, Int>>() // start tick, end tick, key
        while (pos + 8 <= bytes.size) {
            val len = be32(pos + 4)
            if (bytes.decodeToString(pos, pos + 4) != "MTrk") { pos += 8 + len; continue }
            var p = pos + 8
            val end = minOf(bytes.size, p + len)
            var tick = 0
            var status = 0
            val open = mutableMapOf<Int, Int>()
            while (p < end) {
                var delta = 0
                while (true) { val b = u8(p++); delta = (delta shl 7) or (b and 0x7F); if (b and 0x80 == 0) break }
                tick += delta
                var b = u8(p)
                if (b and 0x80 != 0) { status = b; p++ } else b = status
                when {
                    status == 0xFF -> {
                        val type = u8(p++)
                        var l = 0
                        while (true) { val x = u8(p++); l = (l shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }
                        if (type == 0x51 && l == 3) tempos += tick to ((u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2))
                        p += l
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        var l = 0
                        while (true) { val x = u8(p++); l = (l shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }
                        p += l
                    }
                    status and 0xF0 == 0x90 || status and 0xF0 == 0x80 -> {
                        val key = u8(p); val vel = u8(p + 1); p += 2
                        if (status and 0xF0 == 0x90 && vel > 0) open[key] = tick
                        else open.remove(key)?.let { s -> raw += Triple(s, tick, key) }
                    }
                    status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> p += 1
                    else -> p += 2
                }
            }
            pos = end
        }
        tempos.sortBy { it.first }
        fun seconds(tick: Int): Double {
            var t = 0.0
            var lastTick = 0
            var us = 500_000
            for ((tt, u) in tempos) {
                if (tt >= tick) break
                t += (tt - lastTick) * us / 1e6 / division
                lastTick = tt; us = u
            }
            return t + (tick - lastTick) * us / 1e6 / division
        }
        val notes = mutableListOf<Note>()
        var cursor = 0.0
        for ((s, e, k) in raw.sortedBy { it.first }) {
            val a = seconds(s)
            val b = seconds(e)
            if (a < cursor - 1e-6) continue // overlapping notes: keep the first
            if (a > cursor + 1e-6) notes += Note(cursor, a, null)
            notes += Note(a, b, k.toDouble())
            cursor = b
        }
        if (duration != null && duration > cursor + 1e-6) notes += Note(cursor, duration, null)
        return NoteTier("notes", notes)
    }
}
