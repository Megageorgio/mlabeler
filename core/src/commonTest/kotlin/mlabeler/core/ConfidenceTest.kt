package mlabeler.core

import mlabeler.core.check.CheckSettings
import mlabeler.core.check.Checks
import mlabeler.core.check.Problem
import mlabeler.core.edit.Edits
import mlabeler.core.edit.IntervalRef
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConfidenceTest {
    private fun doc() = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.2, 0.5, 0.8), listOf("SP", "a", "SP"), listOf(0.9f, 0.3f, 0.8f))))

    @Test
    fun unsurePhonemeIsMarkedUntilSetByHand() {
        val d = doc()
        val unsure = Checks.run(d, CheckSettings()).filter { it.kind == Problem.Kind.LowConfidence }
        assertEquals(listOf("30%"), unsure.map { it.detail })
        // confirmed with Enter, the same label: no longer the model's guess
        val same = Edits.setText(d, IntervalRef(0, 1), "a")
        assertNull((same.tiers[0] as IntervalTier).confidenceOf(1))
        assertEquals(0.9f, (same.tiers[0] as IntervalTier).confidenceOf(0))
        assertEquals(0, Checks.run(same, CheckSettings()).count { it.kind == Problem.Kind.LowConfidence })
        // nothing to clear and nothing changed: the same document
        assertSame(same, Edits.setText(same, IntervalRef(0, 1), "a"))
    }
}
