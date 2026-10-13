package mlabeler.core

import mlabeler.core.check.ModelScore
import mlabeler.core.model.IntervalTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelScoreTest {
    private fun tier(vararg p: Pair<String, Double>): IntervalTier {
        var t = 0.0
        return IntervalTier.fromIntervals("phones", p.map { (s, d) -> Triple(t, t + d, s).also { t += d } }, t, null)
    }

    @Test
    fun errorsAndBoundaries() {
        val ref = tier("SP" to 0.2, "k" to 0.1, "a" to 0.3, "t" to 0.1, "SP" to 0.2)
        // g for k and n for t (substitutions), the pause before them 10 ms longer
        val hyp = tier("SP" to 0.21, "g" to 0.09, "a" to 0.3, "n" to 0.1, "AP" to 0.2)
        val s = ModelScore.score(ref, hyp)
        assertEquals(3, s.ref); assertEquals(3, s.hyp)
        assertEquals(2, s.errors)          // k→g, t→n
        assertEquals(1, s.matched)         // a
        assertTrue(s.boundaryMs.all { it < 0.5 }, "${s.boundaryMs}")
        assertEquals(2.0 / 3, s.per, 1e-9)
        val same = ModelScore.score(ref, ref)
        assertEquals(0.0, same.per); assertEquals(1.0, same.within(20.0))
        // only the middle part
        val part = ModelScore.score(ref, hyp, 0.25, 0.4)
        assertEquals(1, part.ref); assertEquals(1, part.substitutions)
        val missing = ModelScore.score(ref, tier("SP" to 1.0))
        assertEquals(3, missing.deletions); assertEquals(1.0, missing.per)
        assertEquals(6, (s + missing).ref)
    }
}
