package mlabeler.core.format

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.Note
import mlabeler.core.model.NoteTier

/** An UTAU sequence (.ust): notes in ticks (480 per quarter note) with lyric, key and tempo changes. */
object Ust {
    data class UstNote(val ticks: Int, val lyric: String, val key: Int?, val tempo: Double?)

    /** Lyrics that are rests rather than sung notes. */
    fun isRest(lyric: String) = lyric.isBlank() || lyric.trim().lowercase() in setOf("r", "rest", "pau", "sil", "sp", "-")

    /** The notes of [text] in order, and the tempo of the song (from [#SETTING], or 120). */
    fun read(text: String): Pair<Double, List<UstNote>> {
        var tempo = 120.0
        val notes = mutableListOf<UstNote>()
        var section = ""
        var fields = mutableMapOf<String, String>()
        fun flush() {
            if (Regex("#\\d+").matches(section)) {
                val len = fields["Length"]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                notes += UstNote(len, fields["Lyric"]?.trim() ?: "", fields["NoteNum"]?.trim()?.toIntOrNull(),
                    fields["Tempo"]?.trim()?.replace(',', '.')?.toDoubleOrNull())
            } else if (section == "#SETTING") {
                fields["Tempo"]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { tempo = it }
            }
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                section = line.substring(1, line.length - 1)
                fields = mutableMapOf()
            } else {
                val i = line.indexOf('=')
                if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        flush()
        return tempo to notes
    }

    /** Start and end of each note in seconds, following the tempo changes; [offset] is added. */
    fun times(tempo: Double, notes: List<UstNote>, offset: Double = 0.0): List<Pair<Double, Double>> {
        var t = offset
        var bpm = tempo
        return notes.map { n ->
            n.tempo?.let { if (it > 0) bpm = it }
            val start = t
            t += n.ticks / 480.0 * 60.0 / bpm
            start to t
        }
    }

    /** The sung part of a lyric: "a か" (VCV) and "- か" give か; prefixes of a voicebank map stay. */
    fun syllable(lyric: String): String = lyric.trim().substringAfterLast(' ')

    /**
     * Notes (rests have no pitch) and words (the lyrics, rests as [rest]) of a .ust, starting at [offset] s and cut
     * at [duration] when it is given.
     */
    fun tiers(text: String, offset: Double = 0.0, duration: Double? = null, rest: String = "SP"): Pair<NoteTier, IntervalTier> {
        val (tempo, notes) = read(text)
        val times = times(tempo, notes, offset)
        val out = mutableListOf<Note>()
        val words = mutableListOf<Triple<Double, Double, String>>()
        for ((n, span) in notes.zip(times)) {
            var (s, e) = span
            if (duration != null) { if (s >= duration) break; e = minOf(e, duration) }
            if (e <= s) continue
            val sung = !isRest(n.lyric)
            out += Note(s, e, if (sung) n.key?.toDouble() else null, text = if (sung) syllable(n.lyric) else "")
            words += Triple(s, e, if (sung) syllable(n.lyric) else rest)
        }
        return NoteTier("notes", out) to IntervalTier.fromIntervals("words", words, duration)
    }

    /** The lyrics of a .ust as they would be looked up in oto.ini (the aliases), rests left out. */
    fun aliases(text: String): Set<String> = read(text).second.map { it.lyric.trim() }.filter { !isRest(it) }.toSet()
}
