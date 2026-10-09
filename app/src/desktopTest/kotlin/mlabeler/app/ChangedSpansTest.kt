package mlabeler.app

import mlabeler.app.state.EditorState
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangedSpansTest {
    private val before = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.5, 1.0, 1.5, 2.0), listOf("SP", "a", "k", "SP"))))

    @Test
    fun nothingChanged() {
        assertEquals(emptyMap(), EditorState.changedSpansOf(before, before))
    }

    @Test
    fun movedBoundaryGlowsAsOneSpan() {
        // the boundary at 1.0 moved: both intervals around it changed and glow together
        val after = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.5, 1.1, 1.5, 2.0), listOf("SP", "a", "k", "SP"))))
        assertEquals(mapOf(0 to listOf(0.5 to 1.5)), EditorState.changedSpansOf(before, after))
    }

    @Test
    fun renamedInterval() {
        val after = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.5, 1.0, 1.5, 2.0), listOf("pau", "a", "k", "SP"))))
        assertEquals(mapOf(0 to listOf(0.0 to 0.5)), EditorState.changedSpansOf(before, after))
    }
}
