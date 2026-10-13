package mlabeler.core.check

import mlabeler.core.model.IntervalTier
import kotlin.math.abs
import kotlin.math.min

/**
 * How close a model's phonemes come to reference ones: phoneme error rate (substitutions, deletions and insertions
 * of the best alignment, pauses left out) and, for the phonemes both have, how far their boundaries are.
 */
data class PhonemeScore(
    /** Phonemes of the reference and of the model (pauses left out). */
    val ref: Int,
    val hyp: Int,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    /** Boundary errors (ms) of the phonemes both have, starts and ends. */
    val boundaryMs: List<Double>,
) {
    val errors: Int get() = substitutions + deletions + insertions
    /** Phoneme error rate, 0..1 (can pass 1 when the model adds a lot). */
    val per: Double get() = if (ref == 0) (if (hyp == 0) 0.0 else 1.0) else errors.toDouble() / ref
    val matched: Int get() = boundaryMs.size / 2
    val meanMs: Double get() = if (boundaryMs.isEmpty()) 0.0 else boundaryMs.average()
    val medianMs: Double get() = boundaryMs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    fun within(ms: Double): Double = if (boundaryMs.isEmpty()) 0.0 else boundaryMs.count { it <= ms }.toDouble() / boundaryMs.size

    operator fun plus(o: PhonemeScore) = PhonemeScore(ref + o.ref, hyp + o.hyp, substitutions + o.substitutions,
        deletions + o.deletions, insertions + o.insertions, boundaryMs + o.boundaryMs)

    companion object {
        val EMPTY = PhonemeScore(0, 0, 0, 0, 0, emptyList())
    }
}

object ModelScore {
    /** Labels that are not phonemes: pauses and breaths are left out of the error rate. */
    val PAUSES = setOf("", "SP", "AP", "pau", "sil", "sp", "br", "<SP>", "<AP>")

    /**
     * [hyp] against the reference [ref] over [from]..[to] (seconds; an interval belongs to the range by its
     * middle). Labels are compared as they are; [pauses] are left out.
     */
    fun score(ref: IntervalTier, hyp: IntervalTier, from: Double = 0.0, to: Double = Double.MAX_VALUE, pauses: Set<String> = PAUSES): PhonemeScore {
        data class Ph(val text: String, val start: Double, val end: Double)
        fun phones(t: IntervalTier) = (0 until t.size).mapNotNull { i ->
            val s = t.startOf(i); val e = t.endOf(i)
            if ((s + e) / 2 !in from..to || t.texts[i].trim() in pauses) null else Ph(t.texts[i].trim(), s, e)
        }
        val r = phones(ref)
        val h = phones(hyp)
        val n = r.size
        val m = h.size
        // edit distance with the alignment kept
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + if (r[i - 1].text == h[j - 1].text) 0 else 1)
        }
        var i = n
        var j = m
        var sub = 0; var del = 0; var ins = 0
        val bounds = ArrayList<Double>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (r[i - 1].text == h[j - 1].text) 0 else 1) {
                if (r[i - 1].text == h[j - 1].text) {
                    bounds += abs(r[i - 1].start - h[j - 1].start) * 1000
                    bounds += abs(r[i - 1].end - h[j - 1].end) * 1000
                } else sub++
                i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) { del++; i-- } else { ins++; j-- }
        }
        return PhonemeScore(n, m, sub, del, ins, bounds)
    }
}
