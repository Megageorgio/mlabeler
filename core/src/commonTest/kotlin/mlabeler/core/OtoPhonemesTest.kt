package mlabeler.core

import mlabeler.core.format.OtoEntry
import mlabeler.core.oto.OtoPhonemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtoPhonemesTest {
    @Test
    fun aliasesGiveTheSyllableTheyEndOn() {
        assertEquals("ka", OtoPhonemes.syllable("- ka")?.text)
        assertEquals("ka", OtoPhonemes.syllable("a ka")?.text)
        assertEquals("ka", OtoPhonemes.syllable("か")?.text)
        assertEquals("k", OtoPhonemes.syllable("a ka")?.consonant)
        assertEquals("a", OtoPhonemes.syllable("- a")?.vowel)
        assertNull(OtoPhonemes.syllable("a k"))
        assertNull(OtoPhonemes.syllable("-"))
    }

    @Test
    fun aTakeOfTwoSyllablesBecomesPhonemes() {
        // "_かき": - ka at 100 ms, ki at 500 ms
        val e = listOf(
            OtoEntry("_kaki.wav", "- ka", 100.0, 120.0, -350.0, 100.0, 30.0),
            OtoEntry("_kaki.wav", "a ki", 380.0, 140.0, -400.0, 120.0, 40.0),
        )
        val iv = OtoPhonemes.intervals(e, 1000.0)
        assertEquals(listOf("SP", "k", "a", "k", "i", "SP"), iv.map { it.third })
        assertEquals(0.13, iv[1].first, 1e-9)
        assertEquals(0.2, iv[2].first, 1e-9)
        assertEquals(0.42, iv[2].second, 1e-9)
        assertEquals(0.5, iv[4].first, 1e-9)
        assertEquals(0.78, iv[4].second, 1e-9)
    }
}
