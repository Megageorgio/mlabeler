package mlabeler.core.model

/**
 * A tier of intervals that cover a time range without gaps: interval i spans bounds[i]..bounds[i + 1].
 * Gaps from formats that allow them are stored as intervals with empty text.
 * Times are seconds.
 */
data class IntervalTier(
    val name: String,
    val bounds: List<Double>,
    val texts: List<String>,
    /** Aligner confidence per interval (0..1), if known. */
    val confidence: List<Float?>? = null,
) : Tier {
    init {
        require(bounds.size == texts.size + 1) { "bounds must have one more element than texts" }
        require(confidence == null || confidence.size == texts.size)
    }

    val size: Int get() = texts.size
    val start: Double get() = bounds.first()
    val end: Double get() = bounds.last()

    fun startOf(i: Int) = bounds[i]
    fun endOf(i: Int) = bounds[i + 1]
    fun durationOf(i: Int) = bounds[i + 1] - bounds[i]
    fun confidenceOf(i: Int): Float? = confidence?.getOrNull(i)

    /** Index of the interval containing [time], or -1 outside of the tier. */
    fun indexAt(time: Double): Int {
        if (texts.isEmpty() || time < bounds.first() || time > bounds.last()) return -1
        var lo = 0
        var hi = texts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (bounds[mid] <= time) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the bound nearest to [time]. */
    fun nearestBound(time: Double): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in bounds.indices) {
            val d = kotlin.math.abs(bounds[i] - time)
            if (d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    override fun renamed(name: String) = copy(name = name)

    companion object {
        fun empty(name: String, duration: Double) = IntervalTier(name, listOf(0.0, duration), listOf(""))

        /**
         * Builds a gapless tier from possibly sparse intervals (start, end, text), sorted by start.
         * Gaps become empty intervals, overlaps are cut at the start of the next interval.
         */
        fun fromIntervals(
            name: String,
            items: List<Triple<Double, Double, String>>,
            duration: Double? = null,
            confidence: List<Float?>? = null,
        ): IntervalTier {
            val sorted = items.withIndex().sortedBy { it.value.first }
            val bounds = mutableListOf<Double>()
            val texts = mutableListOf<String>()
            val conf = mutableListOf<Float?>()
            for ((k, iv) in sorted.withIndex()) {
                val (s, e, t) = iv.value
                if (bounds.isEmpty()) {
                    if (s > 0.0) {
                        bounds += 0.0
                        texts += ""
                        conf += null
                    }
                    bounds += s
                } else {
                    val last = bounds.last()
                    if (s > last + 1e-9) {
                        texts += ""
                        conf += null
                        bounds += s
                    }
                }
                val next = sorted.getOrNull(k + 1)?.value?.first
                var end = maxOf(e, bounds.last())
                if (next != null && end > next) end = maxOf(next, bounds.last())
                texts += t
                conf += confidence?.getOrNull(iv.index)
                bounds += end
            }
            if (bounds.isEmpty()) return empty(name, duration ?: 0.0)
            if (duration != null && duration > bounds.last() + 1e-9) {
                texts += ""
                conf += null
                bounds += duration
            }
            return IntervalTier(name, bounds, texts, if (confidence != null) conf else null)
        }
    }
}

data class Point(val time: Double, val text: String)

data class PointTier(val name: String, val points: List<Point>) : Tier {
    override fun renamed(name: String) = copy(name = name)
}

/** A note; [pitch] is a MIDI number (60 = C4), fractional for detuned notes, null for rests. */
data class Note(val start: Double, val end: Double, val pitch: Double?, val slur: Boolean = false, val text: String = "")

data class NoteTier(val name: String, val notes: List<Note>) : Tier {
    override fun renamed(name: String) = copy(name = name)
}

sealed interface Tier {
    fun renamed(name: String): Tier
}

val Tier.name: String
    get() = when (this) {
        is IntervalTier -> name
        is PointTier -> name
        is NoteTier -> name
    }

/** All labels of one audio file. */
data class LabelDoc(val tiers: List<Tier>) {
    val intervalTiers: List<IntervalTier> get() = tiers.filterIsInstance<IntervalTier>()

    fun tierIndex(name: String) = tiers.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    fun replace(index: Int, tier: Tier) = copy(tiers = tiers.toMutableList().also { it[index] = tier })

    /** The main phoneme tier: named "phones"/"phonemes"/"phone", or the last interval tier. */
    fun phonemeTierIndex(): Int {
        val named = tiers.indexOfFirst { it is IntervalTier && it.name.lowercase() in PHONEME_NAMES }
        if (named >= 0) return named
        return tiers.indexOfLast { it is IntervalTier }
    }

    fun wordTierIndex(): Int = tiers.indexOfFirst { it is IntervalTier && it.name.lowercase() in WORD_NAMES }

    val end: Double
        get() = tiers.maxOfOrNull {
            when (it) {
                is IntervalTier -> it.end
                is PointTier -> it.points.maxOfOrNull { p -> p.time } ?: 0.0
                is NoteTier -> it.notes.maxOfOrNull { n -> n.end } ?: 0.0
            }
        } ?: 0.0

    companion object {
        val PHONEME_NAMES = setOf("phones", "phonemes", "phone", "phoneme", "ph")
        val WORD_NAMES = setOf("words", "word", "syllables", "lyrics")
        fun empty(duration: Double) = LabelDoc(listOf(IntervalTier.empty("phones", duration)))
    }
}
