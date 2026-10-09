package mlabeler.core

import mlabeler.core.format.Ust
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UstTest {
    private val ust = """
        [#VERSION]
        UST Version1.2
        [#SETTING]
        Tempo=120.00
        [#0000]
        Length=480
        Lyric=R
        NoteNum=60
        [#0001]
        Length=960
        Lyric=- か
        NoteNum=62
        [#0002]
        Length=480
        Lyric=a き
        NoteNum=64
        Tempo=60
        [#TRACKEND]
    """.trimIndent()

    @Test
    fun notesFollowTheTempo() {
        val (tempo, notes) = Ust.read(ust)
        assertEquals(120.0, tempo)
        assertEquals(3, notes.size)
        // a quarter at 120 is 0.5 s; the last note is at 60
        assertEquals(listOf(0.0 to 0.5, 0.5 to 1.5, 1.5 to 2.5), Ust.times(tempo, notes))
        val (nt, words) = Ust.tiers(ust)
        assertNull(nt.notes[0].pitch)
        assertEquals(62.0, nt.notes[1].pitch)
        assertEquals(listOf("SP", "か", "き"), words.texts)
        assertEquals(setOf("- か", "a き"), Ust.aliases(ust))
    }
}
