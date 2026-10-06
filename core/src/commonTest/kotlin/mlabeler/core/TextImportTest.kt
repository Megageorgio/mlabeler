package mlabeler.core

import mlabeler.core.format.TextImport
import kotlin.test.Test
import kotlin.test.assertEquals

class TextImportTest {
    @Test
    fun lyricsLoseTagsPunctuationAndRepeats() {
        val lrc = "[ar:Someone]\n[00:12.34]Привет, мир! (x2)\n[00:15.00]Как дела — хорошо?\n"
        assertEquals("Привет мир Как дела хорошо", TextImport.clean(lrc, phonemes = false))
    }

    @Test
    fun wordsKeepInnerHyphensAndApostrophes() {
        assertEquals("кто-то don't stop", TextImport.clean("Кто-то... \"don't\" stop!".replaceFirst("К", "к"), phonemes = false))
    }

    @Test
    fun subtitlesLoseNumbersAndTimes() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nhello world\n\n2\n00:00:03,000 --> 00:00:04,000\nagain\n"
        assertEquals("hello world again", TextImport.clean(srt, phonemes = false))
    }

    @Test
    fun labGivesItsLabels() {
        val lab = "0 1000000 SP\n1000000 2500000 k\n2500000 4000000 a\n"
        assertEquals("SP k a", TextImport.clean(lab, phonemes = true))
        assertEquals("SP k a", TextImport.clean(lab, phonemes = false))
    }

    @Test
    fun phonemesKeepTheirSpelling() {
        assertEquals("SP k a sh i AP", TextImport.clean("SP k a\nsh, i  AP\n", phonemes = true))
    }
}
