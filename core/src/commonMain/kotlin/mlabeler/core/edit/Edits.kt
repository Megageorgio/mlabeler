package mlabeler.core.edit

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.NoteTier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A boundary: [bound] indexes IntervalTier.bounds of tier [tier]. */
data class BoundRef(val tier: Int, val bound: Int)

/** An interval: [index] indexes IntervalTier.texts of tier [tier]. */
data class IntervalRef(val tier: Int, val index: Int)

data class MoveOptions(
    /** Move this boundary and every boundary after it by the same amount. */
    val ripple: Boolean = false,
    /** Also move boundaries at the same time in other interval tiers. */
    val linked: Boolean = true,
    /** Smallest interval length, seconds. */
    val minGap: Double = 0.001,
)

object Edits {
    const val SAME_TIME = 1e-6

    /** Boundaries in other interval tiers that sit at the same time as [ref]. */
    fun linkedBounds(doc: LabelDoc, ref: BoundRef): List<BoundRef> {
        val tier = doc.tiers[ref.tier] as IntervalTier
        val t = tier.bounds[ref.bound]
        val out = mutableListOf<BoundRef>()
        for ((k, other) in doc.tiers.withIndex()) {
            if (k == ref.tier || other !is IntervalTier) continue
            val b = other.nearestBound(t)
            if (abs(other.bounds[b] - t) < SAME_TIME) out += BoundRef(k, b)
        }
        return out
    }

    /** Range of allowed shifts (min, max) for one boundary. */
    private fun allowedShift(tier: IntervalTier, b: Int, ripple: Boolean, duration: Double, gap: Double): Pair<Double, Double> {
        val t = tier.bounds[b]
        val lo = if (b > 0) tier.bounds[b - 1] + gap - t else -t
        val hi = if (ripple) {
            val last = tier.bounds.size - 1
            if (b == last) duration - t
            else {
                // the last boundary stays at the end of the file if it is there; the interval before it shrinks
                val lastAtEnd = abs(tier.bounds[last] - duration) < SAME_TIME
                if (lastAtEnd) {
                    if (b == last - 1) tier.bounds[last] - gap - t else tier.bounds[last] - gap - tier.bounds[last - 1]
                } else {
                    duration - tier.bounds[last]
                }
            }
        } else {
            if (b < tier.bounds.size - 1) tier.bounds[b + 1] - gap - t else duration - t
        }
        return lo to max(lo, hi)
    }

    private fun shift(tier: IntervalTier, b: Int, delta: Double, ripple: Boolean, duration: Double): IntervalTier {
        val nb = tier.bounds.toMutableList()
        if (!ripple) {
            nb[b] += delta
        } else {
            val last = nb.size - 1
            val lastAtEnd = abs(nb[last] - duration) < SAME_TIME && b != last
            val stop = if (lastAtEnd) last - 1 else last
            for (i in b..stop) nb[i] += delta
        }
        return tier.copy(bounds = nb)
    }

    /** Moves a boundary to [time]; the result is clamped so no interval gets shorter than the minimum. */
    fun moveBound(doc: LabelDoc, ref: BoundRef, time: Double, duration: Double, options: MoveOptions = MoveOptions()): LabelDoc {
        val tier = doc.tiers[ref.tier] as IntervalTier
        val targets = listOf(ref) + if (options.linked) linkedBounds(doc, ref) else emptyList()
        var lo = -Double.MAX_VALUE
        var hi = Double.MAX_VALUE
        for (r in targets) {
            val (a, b) = allowedShift(doc.tiers[r.tier] as IntervalTier, r.bound, options.ripple, duration, options.minGap)
            lo = max(lo, a)
            hi = min(hi, b)
        }
        if (hi < lo) return doc
        val delta = (time - tier.bounds[ref.bound]).coerceIn(lo, hi)
        if (delta == 0.0) return doc
        var out = doc
        for (r in targets) out = out.replace(r.tier, shift(out.tiers[r.tier] as IntervalTier, r.bound, delta, options.ripple, duration))
        return out
    }

    /** Splits the interval at [time] into two; the right part gets [rightText]. Returns the doc and the new boundary. */
    fun split(doc: LabelDoc, tierIndex: Int, time: Double, rightText: String = "", minGap: Double = 0.001): Pair<LabelDoc, BoundRef>? {
        val tier = doc.tiers[tierIndex] as IntervalTier
        val i = tier.indexAt(time)
        if (i < 0) return null
        if (time - tier.startOf(i) < minGap || tier.endOf(i) - time < minGap) return null
        val nb = tier.bounds.toMutableList().apply { add(i + 1, time) }
        val nt = tier.texts.toMutableList().apply { add(i + 1, rightText) }
        val nc = tier.confidence?.toMutableList()?.apply { add(i + 1, null) }
        return doc.replace(tierIndex, tier.copy(bounds = nb, texts = nt, confidence = nc)) to BoundRef(tierIndex, i + 1)
    }

    /** Removes an inner boundary, joining its two intervals. The left text is kept unless it is empty. */
    /** Removes a boundary, joining its two intervals; the joined one keeps the left text, or the right with [keepRight]
     *  (an empty text never wins over a non-empty one). */
    fun removeBound(doc: LabelDoc, ref: BoundRef, keepRight: Boolean = false): LabelDoc {
        val tier = doc.tiers[ref.tier] as IntervalTier
        val b = ref.bound
        if (b <= 0 || b >= tier.bounds.size - 1) return doc
        val left = tier.texts[b - 1]
        val right = tier.texts[b]
        val nb = tier.bounds.toMutableList().apply { removeAt(b) }
        val nt = tier.texts.toMutableList().apply {
            this[b - 1] = if (keepRight) right.ifEmpty { left } else left.ifEmpty { right }
            removeAt(b)
        }
        val nc = tier.confidence?.toMutableList()?.apply { removeAt(b) }
        return doc.replace(ref.tier, tier.copy(bounds = nb, texts = nt, confidence = nc))
    }

    fun mergeWithNext(doc: LabelDoc, ref: IntervalRef): LabelDoc = removeBound(doc, BoundRef(ref.tier, ref.index + 1))

    fun setText(doc: LabelDoc, ref: IntervalRef, text: String): LabelDoc {
        val tier = doc.tiers[ref.tier] as IntervalTier
        if (tier.texts[ref.index] == text) return doc
        val nt = tier.texts.toMutableList().apply { this[ref.index] = text }
        return doc.replace(ref.tier, tier.copy(texts = nt))
    }

    /** Sets texts of several intervals at once (bulk rename). */
    fun setTexts(doc: LabelDoc, refs: Collection<IntervalRef>, text: (String) -> String): LabelDoc {
        var out = doc
        for (r in refs) {
            val t = out.tiers[r.tier] as IntervalTier
            out = setText(out, r, text(t.texts[r.index]))
        }
        return out
    }

    fun addTier(doc: LabelDoc, name: String, duration: Double, at: Int = doc.tiers.size): LabelDoc =
        doc.copy(tiers = doc.tiers.toMutableList().apply { add(at, IntervalTier.empty(name, max(duration, doc.end))) })

    /** A copy of [source] tier with all its boundaries and empty texts. */
    fun duplicateTierBounds(doc: LabelDoc, source: Int, name: String): LabelDoc {
        val t = doc.tiers[source] as IntervalTier
        return doc.copy(tiers = doc.tiers.toMutableList().apply { add(source, IntervalTier(name, t.bounds, List(t.size) { "" })) })
    }

    fun removeTier(doc: LabelDoc, index: Int): LabelDoc =
        if (doc.tiers.size <= 1) doc else doc.copy(tiers = doc.tiers.toMutableList().apply { removeAt(index) })

    fun renameTier(doc: LabelDoc, index: Int, name: String): LabelDoc = doc.replace(index, doc.tiers[index].renamed(name))

    fun moveTier(doc: LabelDoc, from: Int, to: Int): LabelDoc {
        if (from == to || to !in doc.tiers.indices) return doc
        val l = doc.tiers.toMutableList()
        val t = l.removeAt(from)
        l.add(to, t)
        return doc.copy(tiers = l)
    }

    /** Makes interval tiers end exactly at [duration]: the last interval is stretched or a pause added. */
    fun fitToDuration(doc: LabelDoc, duration: Double): LabelDoc = doc.copy(tiers = doc.tiers.map { t ->
        if (t !is IntervalTier || duration <= 0) return@map t
        when {
            abs(t.end - duration) < SAME_TIME -> t
            t.end < duration -> t.copy(bounds = t.bounds + duration, texts = t.texts + "", confidence = t.confidence?.plus(null))
            else -> {
                // cut intervals past the end
                val keep = t.bounds.indexOfFirst { it >= duration - SAME_TIME }.coerceAtLeast(1)
                IntervalTier(t.name, t.bounds.take(keep) + duration, t.texts.take(keep), t.confidence?.take(keep))
            }
        }
    })
}

/** Undo/redo over immutable values. */
class History<T>(initial: T, private val limit: Int = 500) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    var current: T = initial
        private set

    /** Version counter, changes on every push/undo/redo. */
    var version: Long = 0
        private set
    /** Version at the last save. */
    var savedVersion: Long = 0

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val dirty get() = version != savedVersion

    private var counter = 0L
    private val versions = ArrayDeque<Long>()
    private val redoVersions = ArrayDeque<Long>()

    fun push(value: T) {
        if (value == current) return
        undoStack.addLast(current)
        versions.addLast(version)
        if (undoStack.size > limit) {
            undoStack.removeFirst()
            versions.removeFirst()
        }
        redoStack.clear()
        redoVersions.clear()
        current = value
        version = ++counter
    }

    /** Replaces the current value without a new undo step (continuous drags). */
    fun amend(value: T) {
        current = value
        version = ++counter
    }

    fun undo(): Boolean {
        val v = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        redoVersions.addLast(version)
        current = v
        version = versions.removeLast()
        return true
    }

    fun redo(): Boolean {
        val v = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        versions.addLast(version)
        current = v
        version = redoVersions.removeLast()
        return true
    }

    fun markSaved() {
        savedVersion = version
    }
}

object RangeEdits {
    /**
     * Replaces the part [from]..[to] of an interval tier with [part] (a tier covering that range, possibly starting
     * earlier). Boundaries at [from] and [to] are added when missing; everything between them is replaced.
     */
    fun replace(tier: IntervalTier, from: Double, to: Double, part: IntervalTier): IntervalTier {
        val keepBefore = tier.bounds.indexOfLast { it <= from + Edits.SAME_TIME }.coerceAtLeast(0)
        val keepAfter = tier.bounds.indexOfFirst { it >= to - Edits.SAME_TIME }.let { if (it < 0) tier.bounds.size - 1 else it }
        val b = mutableListOf<Double>()
        val t = mutableListOf<String>()
        val c = mutableListOf<Float?>()
        for (i in 0 until keepBefore) {
            b += tier.bounds[i]; t += tier.texts[i]; c += tier.confidenceOf(i)
        }
        if (tier.bounds[keepBefore] < from - Edits.SAME_TIME) {
            b += tier.bounds[keepBefore]; t += tier.texts[keepBefore]; c += tier.confidenceOf(keepBefore)
        }
        // the new part, clipped to the range
        val pb = part.bounds.map { it.coerceIn(from, to) }
        for (i in 0 until part.size) {
            if (pb[i + 1] - pb[i] < 1e-6) continue
            b += pb[i]; t += part.texts[i]; c += part.confidenceOf(i)
        }
        if (tier.bounds[keepAfter] > to + Edits.SAME_TIME) {
            b += to; t += tier.texts[keepAfter - 1]; c += tier.confidenceOf(keepAfter - 1)
        }
        for (i in keepAfter until tier.size) {
            b += tier.bounds[i]; t += tier.texts[i]; c += tier.confidenceOf(i)
        }
        b += tier.bounds.last()
        val anyConf = c.any { it != null }
        return IntervalTier(tier.name, b, t, if (anyConf) c else null)
    }
}

/** Edits of a notes tier. Notes are kept in time order; neighbouring notes share their border when they touch. */
object NoteEdits {
    fun indexAt(t: NoteTier, time: Double) = t.notes.indexOfFirst { time >= it.start && time < it.end }

    /** Moves the border between note [i] and the next one (or the end of the last note). */
    fun moveEnd(t: NoteTier, i: Int, time: Double, minLen: Double = 0.01): NoteTier {
        val n = t.notes.toMutableList()
        val cur = n[i]
        val next = n.getOrNull(i + 1)
        val touching = next != null && kotlin.math.abs(next.start - cur.end) < Edits.SAME_TIME
        val hi = if (next != null) (if (touching) next.end - minLen else next.start) else Double.MAX_VALUE
        val v = time.coerceIn(cur.start + minLen, hi)
        n[i] = cur.copy(end = v)
        if (touching) n[i + 1] = next!!.copy(start = v)
        return t.copy(notes = n)
    }

    fun moveStart(t: NoteTier, i: Int, time: Double, minLen: Double = 0.01): NoteTier {
        if (i > 0) return moveEnd(t, i - 1, time, minLen).let { r ->
            // a gap before the note: only this note's start moves
            if (kotlin.math.abs(t.notes[i - 1].end - t.notes[i].start) < Edits.SAME_TIME) r
            else t.copy(notes = t.notes.toMutableList().also { it[i] = it[i].copy(start = time.coerceIn(t.notes[i - 1].end, t.notes[i].end - minLen)) })
        }
        val n = t.notes.toMutableList()
        n[0] = n[0].copy(start = time.coerceIn(0.0, n[0].end - minLen))
        return t.copy(notes = n)
    }

    /** Splits a note at [time]; the second part is a slur of the same pitch. */
    fun split(t: NoteTier, time: Double, minLen: Double = 0.01): Pair<NoteTier, Int>? {
        val i = indexAt(t, time)
        if (i < 0) return null
        val nt = t.notes[i]
        if (time - nt.start < minLen || nt.end - time < minLen) return null
        val n = t.notes.toMutableList()
        n[i] = nt.copy(end = time)
        n.add(i + 1, nt.copy(start = time, slur = nt.pitch != null))
        return t.copy(notes = n) to i + 1
    }

    /** Joins note [i] with the next one, keeping the first one's pitch. */
    fun mergeNext(t: NoteTier, i: Int): NoteTier {
        if (i + 1 >= t.notes.size) return t
        val n = t.notes.toMutableList()
        n[i] = n[i].copy(end = n[i + 1].end)
        n.removeAt(i + 1)
        return t.copy(notes = n)
    }

    fun setPitch(t: NoteTier, i: Int, pitch: Double?): NoteTier =
        t.copy(notes = t.notes.toMutableList().also { it[i] = it[i].copy(pitch = pitch) })

    fun setSlur(t: NoteTier, i: Int, slur: Boolean): NoteTier =
        t.copy(notes = t.notes.toMutableList().also { it[i] = it[i].copy(slur = slur) })

    /**
     * Pitch of each note from an f0 curve: the median of voiced frames inside it, rounded to semitones when
     * [round]. Notes with too few voiced frames become rests only when [restWhenUnvoiced].
     */
    fun pitchFromCurve(t: NoteTier, f0: mlabeler.core.dsp.Curve, round: Boolean, only: Set<Int>? = null, restWhenUnvoiced: Boolean = false): NoteTier =
        t.copy(notes = t.notes.mapIndexed { i, n ->
            if (only != null && i !in only) return@mapIndexed n
            val a = (n.start / f0.hop).toInt().coerceAtLeast(0)
            val b = (n.end / f0.hop).toInt().coerceAtMost(f0.values.size)
            // the middle part, away from transitions
            val m = (b - a) / 5
            val vals = (a + m until b - m).map { f0.values[it] }.filter { it > 0f }.sorted()
            if (vals.size < 3) return@mapIndexed if (restWhenUnvoiced) n.copy(pitch = null, slur = false) else n
            val midi = mlabeler.core.dsp.Pitch.hzToMidi(vals[vals.size / 2].toDouble())
            n.copy(pitch = if (round) kotlin.math.round(midi) else kotlin.math.round(midi * 100) / 100)
        })
}
