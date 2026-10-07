package mlabeler.core

import mlabeler.core.format.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun inventedCredits() {
        assertTrue(Lyrics.isInvented("Субтитры сделал DimaTorzok"))
        assertTrue(Lyrics.isInvented("Субтитры создавал DimaTorzok"))
        assertTrue(Lyrics.isInvented("Продолжение следует..."))
        assertEquals("Так мой, так мой.", Lyrics.clean("Так мой, так мой. Субтитры сделал DimaTorzok"))
        assertTrue(!Lyrics.isInvented("Дожди прошли, закат дали, черёмухи цвели."))
    }

    @Test
    fun longPhraseIntoLines() {
        val text = "Шипели, как локомотив, которому пора Шипели злые доктора, пинали по ногам Глядела с веток детвора"
        val lines = Lyrics.split(10.0, 20.0, text)
        assertEquals(listOf("Шипели, как локомотив, которому пора", "Шипели злые доктора, пинали по ногам", "Глядела с веток детвора"), lines.map { it.second })
        assertEquals(10.0, lines[0].first)
        assertTrue(lines[1].first > 10.0 && lines[2].first < 20.0)
        val long = Lyrics.split(0.0, 5.0, "Дожди прошли, закат дали, черёмухи цвели. Их пряный дух дразнил, твой нюх, пока тебя вели.")
        assertEquals(2, long.size)
        assertTrue(long.all { it.second.length <= 60 })
    }
}
