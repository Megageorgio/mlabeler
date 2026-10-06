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

class DiffSingerChecksTest {
    @kotlin.test.Test
    fun clearCasesOnly() {
        val t = mlabeler.core.model.IntervalTier("phones", listOf(0.0, 0.5, 0.505, 0.505, 0.8, 1.0, 1.5), listOf("SP", "k", "x", "a i", "SP", "SP"))
        val doc = mlabeler.core.model.LabelDoc(listOf(t))
        val kinds = mlabeler.core.check.Checks.run(doc, mlabeler.core.check.CheckSettings(minDurationMs = 0.0)).map { it.kind to it.ref.index }
        kotlin.test.assertTrue((mlabeler.core.check.Problem.Kind.BelowFrame to 1) in kinds)
        kotlin.test.assertTrue((mlabeler.core.check.Problem.Kind.ZeroLength to 2) in kinds)
        kotlin.test.assertTrue((mlabeler.core.check.Problem.Kind.SpaceInPhoneme to 3) in kinds)
        kotlin.test.assertTrue((mlabeler.core.check.Problem.Kind.TwoPauses to 5) in kinds)
        val off = mlabeler.core.check.Checks.run(doc, mlabeler.core.check.CheckSettings(minDurationMs = 0.0, diffsinger = false)).map { it.kind }
        kotlin.test.assertTrue(off.none { it == mlabeler.core.check.Problem.Kind.TwoPauses || it == mlabeler.core.check.Problem.Kind.BelowFrame })
    }
}
