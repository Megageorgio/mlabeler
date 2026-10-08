package mlabeler.core.ds

import mlabeler.core.audio.Audio
import mlabeler.core.dsp.Curve
import mlabeler.core.dsp.Pitch
import mlabeler.core.format.DsCsv
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.Note
import mlabeler.core.model.NoteTier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How recordings are cut into pieces for a DiffSinger dataset. */
data class SegmentOptions(
    /** Longest piece, seconds. Pieces are cut inside pauses; a piece longer than this stays whole if there's no pause. */
    val maxSeconds: Double = 15.0,
    /** Shortest piece worth keeping, seconds. */
    val minSeconds: Double = 2.0,
    /** Silence kept at each end of a piece, seconds. */
    val padSeconds: Double = 0.3,
    /** A pause at least this long inside a piece is a place to cut. */
    val minPause: Double = 0.2,
)

enum class NoteSource { None, Labels, Pitch }

data class DatasetOptions(
    val segments: SegmentOptions = SegmentOptions(),
    val dict: PhonemeDict = PhonemeDict.auto,
    /** Keep the word/group tier of the labels when there is one; otherwise group with [dict]. */
    val keepGroups: Boolean = true,
    val notes: NoteSource = NoteSource.Pitch,
    /** 0 = keep the sample rate of the recording. */
    val sampleRate: Int = 44100,
    /** Text for unnamed parts and for added silence. */
    val restName: String = "SP",
)

/** A piece of a recording: [from]..[to] seconds of the source, with its labels shifted to start at zero. */
data class Segment(val name: String, val from: Double, val to: Double, val doc: LabelDoc)

/**
 * A recording cut into pieces with their labels: the rows of transcriptions.csv and the matching WAV files.
 */
object Dataset {
    /** Tier that marks pieces by hand: its named intervals become the pieces. */
    val SEGMENT_TIER_NAMES = setOf("segments", "segment", "pieces")

    fun isRest(text: String, dict: PhonemeDict) = text.isBlank() || dict.kindOf(text) == PhonemeKind.Rest

    /** Where to cut: (from, to) pairs in seconds. */
    fun cuts(doc: LabelDoc, duration: Double, o: SegmentOptions, dict: PhonemeDict): List<Pair<Double, Double>> {
        // pieces marked by hand
        val manual = doc.tiers.firstOrNull { it is IntervalTier && it.name.lowercase() in SEGMENT_TIER_NAMES } as? IntervalTier
        if (manual != null) {
            return (0 until manual.size).filter { manual.texts[it].isNotBlank() }.map { manual.startOf(it) to manual.endOf(it) }
        }
        val ph = doc.tiers.getOrNull(doc.phonemeTierIndex()) as? IntervalTier ?: return emptyList()
        val end = min(duration, ph.end)
        // sung stretches between pauses
        data class Run(val from: Double, val to: Double)
        val runs = ArrayList<Run>()
        var i = 0
        while (i < ph.size) {
            if (isRest(ph.texts[i], dict) && (ph.durationOf(i) >= o.minPause || i == 0 || i == ph.size - 1)) { i++; continue }
            val s = ph.startOf(i)
            var j = i
            while (j + 1 < ph.size && !(isRest(ph.texts[j + 1], dict) && (ph.durationOf(j + 1) >= o.minPause || j + 1 == ph.size - 1))) j++
            runs += Run(s, ph.endOf(j))
            i = j + 1
        }
        if (runs.isEmpty()) return emptyList()
        // join runs while the piece stays short enough
        val pieces = ArrayList<Pair<Double, Double>>()
        var a = runs[0].from
        var b = runs[0].to
        for (r in runs.drop(1)) {
            if (r.to - a <= o.maxSeconds) b = r.to else { pieces += a to b; a = r.from; b = r.to }
        }
        pieces += a to b
        // silence around each piece, never into the neighbour
        return pieces.mapIndexed { k, (s, e) ->
            val prevEnd = if (k > 0) pieces[k - 1].second else 0.0
            val nextStart = if (k < pieces.size - 1) pieces[k + 1].first else end
            val from = max(max(0.0, s - o.padSeconds), (prevEnd + s) / 2)
            val to = min(min(end, e + o.padSeconds), (e + nextStart) / 2)
            from to to
        }.filter { (s, e) -> e - s >= o.minSeconds }
    }

    /** Labels of [from]..[to], starting at zero. Unnamed parts become rests; the edges are rests too. */
    fun slice(doc: LabelDoc, from: Double, to: Double, rest: String): LabelDoc {
        val tiers = doc.tiers.mapNotNull { t ->
            when (t) {
                is IntervalTier -> {
                    if (t.name.lowercase() in SEGMENT_TIER_NAMES) return@mapNotNull null
                    val items = (0 until t.size).mapNotNull { i ->
                        val s = max(t.startOf(i), from)
                        val e = min(t.endOf(i), to)
                        if (e - s <= 1e-6) null else Triple(s - from, e - from, t.texts[i].ifBlank { rest })
                    }
                    IntervalTier.fromIntervals(t.name, items, to - from).let { r -> r.copy(texts = r.texts.map { it.ifBlank { rest } }) }
                }
                is NoteTier -> {
                    val inside = t.notes.mapNotNull { n ->
                        val s = max(n.start, from)
                        val e = min(n.end, to)
                        if (e - s <= 1e-6) null else n.copy(start = s - from, end = e - from)
                    }
                    // gaps become rests so the notes cover the whole piece
                    val filled = ArrayList<Note>()
                    var t0 = 0.0
                    for (n in inside) {
                        if (n.start > t0 + 1e-6) filled += Note(t0, n.start, null)
                        filled += n
                        t0 = n.end
                    }
                    if (to - from > t0 + 1e-6) filled += Note(t0, to - from, null)
                    NoteTier(t.name, filled)
                }
                else -> null
            }
        }
        return LabelDoc(tiers).let { mergeRests(it, rest) }
    }

    /** Neighbouring rests in the phoneme tier become one. */
    private fun mergeRests(doc: LabelDoc, rest: String): LabelDoc {
        val pi = doc.phonemeTierIndex()
        val ph = doc.tiers.getOrNull(pi) as? IntervalTier ?: return doc
        val b = ArrayList<Double>()
        val t = ArrayList<String>()
        b += ph.bounds[0]
        for (i in 0 until ph.size) {
            if (t.isNotEmpty() && t.last() == rest && ph.texts[i] == rest) { b[b.size - 1] = ph.endOf(i); continue }
            t += ph.texts[i]
            b += ph.endOf(i)
        }
        return doc.replace(pi, IntervalTier(ph.name, b, t))
    }

    /**
     * Pieces of one recording ready for transcriptions.csv: groups from the labels or the dictionary, notes from
     * the labels or from the pitch ([f0] of the whole recording).
     */
    fun segments(name: String, doc: LabelDoc, duration: Double, f0: Curve?, o: DatasetOptions): List<Segment> {
        val cuts = cuts(doc, duration, o.segments, o.dict)
        return cuts.mapIndexed { k, (from, to) ->
            var d = slice(doc, from, to, o.restName)
            if (!o.keepGroups || d.wordTierIndex() < 0 || !groupsMatch(d)) d = Grouping.withGroups(d, o.dict)
            d = when (o.notes) {
                NoteSource.None -> d.copy(tiers = d.tiers.filter { it !is NoteTier })
                NoteSource.Labels -> if (d.tiers.any { it is NoteTier }) d else notesFor(d, f0, from, o)
                NoteSource.Pitch -> notesFor(d.copy(tiers = d.tiers.filter { it !is NoteTier }), f0, from, o)
            }
            Segment(if (cuts.size == 1) name else "${name}_${(k + 1).toString().padStart(3, '0')}", from, to, d)
        }
    }

    /** Every group boundary is also a phoneme boundary (otherwise ph_num can't be computed). */
    private fun groupsMatch(d: LabelDoc): Boolean {
        val ph = d.tiers[d.phonemeTierIndex()] as IntervalTier
        val w = d.tiers[d.wordTierIndex()] as IntervalTier
        return w.bounds.all { wb -> ph.bounds.any { abs(it - wb) < 1e-4 } } && DsCsv.phNum(d).sum() == ph.size
    }

    /** One note per group; pitch is the median f0 of the group's middle, rests for rest groups. */
    fun notesFor(d: LabelDoc, f0: Curve?, offset: Double, o: DatasetOptions): LabelDoc {
        val w = d.tiers.getOrNull(d.wordTierIndex()) as? IntervalTier ?: return d
        val notes = (0 until w.size).map { i ->
            val rest = isRest(w.texts[i], o.dict)
            Note(w.startOf(i), w.endOf(i), if (rest) null else 60.0)
        }
        val shift = offset
        var last = 60.0
        val pitched = notes.map { n ->
            if (n.pitch == null) return@map n
            val p = f0?.let { medianMidi(it, n.start + shift, n.end + shift, 0.2) ?: medianMidi(it, n.start + shift, n.end + shift, 0.0) }
            if (p != null) last = p
            n.copy(pitch = p ?: last)
        }
        val tier = NoteTier("notes", pitched)
        return d.copy(tiers = d.tiers + tier)
    }

    /** Median f0 (MIDI, rounded) of [from]..[to] without [trim] of each end; null when too little is voiced. */
    private fun medianMidi(f0: Curve, from: Double, to: Double, trim: Double): Double? {
        val m = (to - from) * trim
        val a = ((from + m) / f0.hop).toInt().coerceAtLeast(0)
        val b = ((to - m) / f0.hop).toInt().coerceAtMost(f0.values.size)
        val v = (a until b).map { f0.values[it] }.filter { it > 0f }.sorted()
        if (v.size < 3) return null
        return kotlin.math.round(Pitch.hzToMidi(v[v.size / 2].toDouble()))
    }

    /** The samples of [from]..[to] seconds, resampled to [rate] (0 = as is). */
    fun cutAudio(a: Audio, from: Double, to: Double, rate: Int): Audio {
        val s = (from * a.sampleRate).toInt().coerceIn(0, a.samples.size)
        val e = (to * a.sampleRate).toInt().coerceIn(s, a.samples.size)
        val part = Audio(a.sampleRate, a.samples.copyOfRange(s, e))
        return if (rate <= 0 || rate == a.sampleRate) part else resample(part, rate)
    }

    /** Windowed-sinc resampling (good enough for training data: flat to ~0.9 of the lower Nyquist). */
    fun resample(a: Audio, rate: Int): Audio {
        val ratio = rate.toDouble() / a.sampleRate
        val n = (a.samples.size * ratio).toInt()
        val out = FloatArray(n)
        val cutoff = min(1.0, ratio) * 0.95
        val half = 24
        val width = half / cutoff
        val x = a.samples
        for (k in 0 until n) {
            val t = k / ratio
            val c = floor(t).toInt()
            val lo = max(0, (t - width).toInt())
            val hi = min(x.size - 1, (t + width).toInt() + 1)
            var acc = 0.0
            for (i in lo..hi) {
                val d = (t - i) * cutoff
                if (abs(d) >= half) continue
                val sinc = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
                val win = 0.5 + 0.5 * cos(PI * d / half)
                acc += x[i] * sinc * win
            }
            out[k] = (acc * cutoff).toFloat()
            if (c < 0) out[k] = 0f
        }
        return Audio(rate, out)
    }

    /** f0 of a whole recording for note estimation. */
    fun f0(a: Audio): Curve = Pitch.yin(a.samples, a.sampleRate, hop = 0.01)
}

/** Finding the sounding parts of a recording without labels. */
object Silence {
    /**
     * Parts louder than [thresholdDb] below the loudest 10 ms; quieter gaps shorter than [minPause] are bridged,
     * parts shorter than [minSound] dropped, [pad] of silence kept around each (never into the neighbour).
     */
    fun sounding(samples: FloatArray, sampleRate: Int, thresholdDb: Double = -40.0, minPause: Double = 0.3, minSound: Double = 0.2, pad: Double = 0.15): List<Pair<Double, Double>> {
        val hop = (sampleRate / 100).coerceAtLeast(1)
        val n = samples.size / hop
        if (n == 0) return emptyList()
        val level = DoubleArray(n) { k -> var s = 0.0; for (i in k * hop until (k + 1) * hop) s += samples[i] * samples[i]; kotlin.math.sqrt(s / hop) }
        val limit = (level.maxOrNull() ?: 0.0) * kotlin.math.exp(thresholdDb / 20.0 * kotlin.math.ln(10.0))
        val runs = ArrayList<IntArray>()
        var k = 0
        while (k < n) {
            if (level[k] <= limit) { k++; continue }
            var j = k
            while (j + 1 < n && level[j + 1] > limit) j++
            val last = runs.lastOrNull()
            if (last != null && (k - last[1]) * 0.01 < minPause) last[1] = j + 1 else runs += intArrayOf(k, j + 1)
            k = j + 1
        }
        val dur = samples.size.toDouble() / sampleRate
        val parts = runs.filter { (it[1] - it[0]) * 0.01 >= minSound }.map { it[0] * 0.01 to it[1] * 0.01 }
        return parts.mapIndexed { i, (a, b) ->
            val lo = if (i > 0) (parts[i - 1].second + a) / 2 else 0.0
            val hi = if (i < parts.size - 1) (b + parts[i + 1].first) / 2 else dur
            maxOf(lo, a - pad) to minOf(hi, b + pad)
        }
    }
}
