package mlabeler.core

import mlabeler.core.check.CheckSettings
import mlabeler.core.check.Checks
import mlabeler.core.check.Problem
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksMoreTest {
    private fun doc(vararg p: Pair<String, Double>): LabelDoc {
        val b = mutableListOf(0.0)
        for ((_, d) in p) b += b.last() + d
        return LabelDoc(listOf(IntervalTier("phones", b, p.map { it.first })))
    }

    @Test
    fun longPausesAndPhrases() {
        val d = doc("SP" to 4.0, "a" to 8.0, "AP" to 0.1, "i" to 8.0, "SP" to 0.5, "u" to 2.0, "" to 0.3)
        val s = CheckSettings(maxPauseSeconds = 3.0, maxPhraseSeconds = 15.0, maxDurationMs = 5000.0)
        val p = Checks.run(d, s)
        assertEquals(1, p.count { it.kind == Problem.Kind.LongPause })
        // a 16.1 s stretch: the 0.1 s breath is too short to cut at
        assertEquals(listOf(1), p.filter { it.kind == Problem.Kind.LongPhrase }.map { it.ref.index })
        assertEquals(2, p.count { it.kind == Problem.Kind.Long })
        assertEquals(0, Checks.run(d, CheckSettings()).count { it.kind in setOf(Problem.Kind.LongPause, Problem.Kind.LongPhrase, Problem.Kind.Long) })
    }
}
