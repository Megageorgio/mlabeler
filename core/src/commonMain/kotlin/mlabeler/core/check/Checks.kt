package mlabeler.core.check

import kotlinx.serialization.Serializable
import mlabeler.core.edit.IntervalRef
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

enum class Severity { Warning, Error }

data class Problem(val kind: Kind, val ref: IntervalRef, val severity: Severity = Severity.Warning, val detail: String = "") {
    enum class Kind { Short, Empty, UnknownPhoneme, LowConfidence, NoPauseAtEdge, Long, LongPause, LongPhrase, Script, ZeroLength, SpaceInPhoneme, TwoPauses, BelowFrame }
}

@Serializable
data class CheckSettings(
    val minDurationMs: Double = 50.0,
    val pauses: Set<String> = setOf("SP", "AP", "pau", "sil", "br", "cl", "R", "-"),
    /** Allowed phonemes; empty = not checked. */
    val phonemeSet: Set<String> = emptySet(),
    val confidenceBelow: Float = 0.5f,
    val pauseAtEdges: Boolean = false,
    /** A phoneme (not a pause) longer than this is a warning; 0 = not checked. */
    val maxDurationMs: Double = 0.0,
    /** A pause or an unnamed gap longer than this is an error; 0 = not checked. */
    val maxPauseSeconds: Double = 0.0,
    /** Singing without a pause (at least [phrasePauseMs] long) for longer than this is an error; 0 = not checked. */
    val maxPhraseSeconds: Double = 0.0,
    val phrasePauseMs: Double = 200.0,
    /**
     * Checks for DiffSinger datasets, only where there's no doubt: a phoneme shorter than one frame, a label with
     * a space inside (DiffSinger splits on spaces), two same pauses in a row, an interval of zero length.
     */
    val diffsinger: Boolean = true,
    /** One frame of DiffSinger, ms (hop 512 at 44.1 kHz). */
    val frameMs: Double = 11.6,
    /** Own checks: JavaScript files run on every change (see the settings page). */
    val scripts: Boolean = true,
)

object Checks {
    private fun sec(v: Double) = "${kotlin.math.round(v * 10) / 10} s"

    fun run(doc: LabelDoc, s: CheckSettings = CheckSettings()): List<Problem> {
        val out = mutableListOf<Problem>()
        val ph = doc.phonemeTierIndex()
        for ((k, t) in doc.tiers.withIndex()) {
            if (t !is IntervalTier) continue
            for (i in 0 until t.size) {
                val ref = IntervalRef(k, i)
                val text = t.texts[i]
                if (k == ph && text.isEmpty()) out += Problem(Problem.Kind.Empty, ref)
                if (text.isNotEmpty() && t.durationOf(i) * 1000 < s.minDurationMs && text !in s.pauses) {
                    out += Problem(Problem.Kind.Short, ref, detail = "${(t.durationOf(i) * 1000).toInt()} ms")
                }
                if (k == ph && s.phonemeSet.isNotEmpty() && text.isNotEmpty() && text !in s.phonemeSet && text !in s.pauses) {
                    out += Problem(Problem.Kind.UnknownPhoneme, ref, Severity.Error, text)
                }
                val c = t.confidenceOf(i)
                if (c != null && c < s.confidenceBelow) out += Problem(Problem.Kind.LowConfidence, ref, detail = "${(c * 100).toInt()}%")
            }
            if (k == ph) {
                for (i in 0 until t.size) {
                    val text = t.texts[i]
                    val pause = text.isEmpty() || text in s.pauses
                    val d = t.durationOf(i)
                    if (!pause && s.maxDurationMs > 0 && d * 1000 > s.maxDurationMs) out += Problem(Problem.Kind.Long, IntervalRef(k, i), detail = "${(d * 1000).toInt()} ms")
                    if (pause && s.maxPauseSeconds > 0 && d > s.maxPauseSeconds) out += Problem(Problem.Kind.LongPause, IntervalRef(k, i), Severity.Error, sec(d))
                }
                if (s.maxPhraseSeconds > 0) {
                    // stretches of singing between pauses long enough to cut at
                    var i = 0
                    while (i < t.size) {
                        fun cut(j: Int) = (t.texts[j].isEmpty() || t.texts[j] in s.pauses) && t.durationOf(j) * 1000 >= s.phrasePauseMs
                        if (cut(i)) { i++; continue }
                        var j = i
                        while (j + 1 < t.size && !cut(j + 1)) j++
                        val len = t.endOf(j) - t.startOf(i)
                        if (len > s.maxPhraseSeconds) out += Problem(Problem.Kind.LongPhrase, IntervalRef(k, i), Severity.Error, sec(len))
                        i = j + 1
                    }
                }
            }
            if (k == ph && s.diffsinger) {
                for (i in 0 until t.size) {
                    val text = t.texts[i]
                    val d = t.durationOf(i)
                    val ref = IntervalRef(k, i)
                    if (d <= 1e-6) out += Problem(Problem.Kind.ZeroLength, ref, Severity.Error)
                    else if (text.isNotEmpty() && text !in s.pauses && d * 1000 < s.frameMs) {
                        out += Problem(Problem.Kind.BelowFrame, ref, Severity.Error, "${(d * 1000).toInt()} ms")
                    }
                    if (text.trim().any { it.isWhitespace() }) out += Problem(Problem.Kind.SpaceInPhoneme, ref, Severity.Error, text)
                    if (i > 0 && text.isNotEmpty() && text in s.pauses && t.texts[i - 1] == text) out += Problem(Problem.Kind.TwoPauses, ref, detail = text)
                }
            }
            if (k == ph && s.pauseAtEdges && t.size > 0) {
                if (t.texts.first() !in s.pauses) out += Problem(Problem.Kind.NoPauseAtEdge, IntervalRef(k, 0))
                if (t.texts.last() !in s.pauses) out += Problem(Problem.Kind.NoPauseAtEdge, IntervalRef(k, t.size - 1))
            }
        }
        return out
    }
}
