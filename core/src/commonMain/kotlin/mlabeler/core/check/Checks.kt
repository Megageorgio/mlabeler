package mlabeler.core.check

import kotlinx.serialization.Serializable
import mlabeler.core.edit.IntervalRef
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

enum class Severity { Warning, Error }

data class Problem(val kind: Kind, val ref: IntervalRef, val severity: Severity = Severity.Warning, val detail: String = "") {
    enum class Kind { Short, Empty, UnknownPhoneme, LowConfidence, NoPauseAtEdge }
}

@Serializable
data class CheckSettings(
    val minDurationMs: Double = 50.0,
    val pauses: Set<String> = setOf("SP", "AP", "pau", "sil", "br", "cl", "R", "-"),
    /** Allowed phonemes; empty = not checked. */
    val phonemeSet: Set<String> = emptySet(),
    val confidenceBelow: Float = 0.5f,
    val pauseAtEdges: Boolean = false,
)

object Checks {
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
            if (k == ph && s.pauseAtEdges && t.size > 0) {
                if (t.texts.first() !in s.pauses) out += Problem(Problem.Kind.NoPauseAtEdge, IntervalRef(k, 0))
                if (t.texts.last() !in s.pauses) out += Problem(Problem.Kind.NoPauseAtEdge, IntervalRef(k, t.size - 1))
            }
        }
        return out
    }
}
