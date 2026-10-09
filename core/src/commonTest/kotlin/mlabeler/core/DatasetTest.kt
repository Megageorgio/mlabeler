package mlabeler.core

import mlabeler.core.audio.Audio
import mlabeler.core.ds.Dataset
import mlabeler.core.ds.DatasetOptions
import mlabeler.core.ds.Grouping
import mlabeler.core.ds.NoteSource
import mlabeler.core.ds.PhonemeDict
import mlabeler.core.ds.SegmentOptions
import mlabeler.core.format.DsCsv
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.NoteTier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatasetTest {
    @Test
    fun groupsStartAtVowels() {
        val ja = PhonemeDict.japanese
        // SP+k | a+sh | i | N | AP
        assertEquals(listOf(2, 2, 1, 1, 1), Grouping.phNum(listOf("SP", "k", "a", "sh", "i", "N", "AP"), ja))
        // っ (cl) is a note of its own, as in DiffSinger's Japanese dictionary
        assertEquals(listOf(2, 1, 2, 1), Grouping.phNum(listOf("SP", "k", "a", "cl", "t", "a"), ja))
        // pinyin: y and w are consonants of the next syllable, which join the group before (as add_ph_num.py does)
        assertEquals(listOf(2, 2, 1), Grouping.phNum(listOf("SP", "w", "o", "d", "e"), PhonemeDict.chinese))
        // Korean: the final consonant (NG) stays with its syllable's vowel; w before a vowel joins the group before
        assertEquals(listOf(2, 3, 2), Grouping.phNum(listOf("SP", "g", "a", "NG", "w", "a"), PhonemeDict.korean))
        // y before a vowel belongs to the group before it
        assertEquals(listOf(2, 1), Grouping.phNum(listOf("SP", "y", "a"), ja))
        // without a dictionary letters decide; multi-language prefixes are ignored
        assertEquals(listOf(2, 3, 1), Grouping.phNum(listOf("SP", "ja/k", "ja/a", "zh/sh", "zh/t", "zh/i"), PhonemeDict.auto))
    }

    @Test
    fun semivowelAloneIsANote() {
        val d = PhonemeDict(semivowels = listOf("w"))
        // "k w t": w has no vowel next to it, so it's a note of its own
        assertEquals(listOf(2, 2, 1), Grouping.phNum(listOf("SP", "k", "w", "t", "a"), d))
        assertEquals(listOf(3, 1), Grouping.phNum(listOf("SP", "k", "w", "a"), d))
    }

    private fun doc(vararg p: Pair<String, Double>): LabelDoc {
        val b = mutableListOf(0.0)
        for ((_, d) in p) b += b.last() + d
        return LabelDoc(listOf(IntervalTier("phones", b, p.map { it.first })))
    }

    @Test
    fun cutsAtPausesAndPads() {
        // 3 phrases of 4 s with 1 s pauses
        val parts = mutableListOf("SP" to 1.0)
        repeat(3) { parts += "k" to 0.5; parts += "a" to 3.5; parts += "SP" to 1.0 }
        val d = doc(*parts.toTypedArray())
        val cuts = Dataset.cuts(d, 16.0, SegmentOptions(maxSeconds = 9.0, padSeconds = 0.3), PhonemeDict.japanese)
        assertEquals(2, cuts.size, cuts.toString())
        assertTrue(abs(cuts[0].first - 0.7) < 1e-9 && abs(cuts[0].second - 10.3) < 1e-9, cuts.toString())
        assertTrue(abs(cuts[1].first - 10.7) < 1e-9 && abs(cuts[1].second - 15.3) < 1e-9, cuts.toString())
    }

    @Test
    fun segmentsBecomeCsvRows() {
        val sr = 8000
        val parts = mutableListOf("" to 1.0, "k" to 0.2, "a" to 1.8, "" to 1.0, "s" to 0.2, "i" to 1.8, "SP" to 1.0)
        val d = doc(*parts.toTypedArray())
        // 220 Hz everywhere: notes should be A3
        val audio = Audio(sr, FloatArray(sr * 7) { (0.5 * sin(2 * PI * 220.0 * it / sr)).toFloat() })
        val o = DatasetOptions(SegmentOptions(maxSeconds = 4.0, minSeconds = 1.0), PhonemeDict.japanese, notes = NoteSource.Pitch)
        val segs = Dataset.segments("take", d, 7.0, Dataset.f0(audio), o)
        assertEquals(listOf("take_001", "take_002"), segs.map { it.name })
        val csv = DsCsv.write(segs.map { DsCsv.Row(it.name, it.doc) })
        val rows = DsCsv.read(csv)
        assertEquals("SP k a SP", (rows[0].doc.tiers.first { it is IntervalTier && it.name == "phones" } as IntervalTier).texts.joinToString(" "))
        assertTrue(csv.lines()[0].contains("ph_num") && csv.lines()[0].contains("note_seq"), csv)
        val notes = rows[0].doc.tiers.filterIsInstance<NoteTier>().first().notes
        assertEquals(listOf(null, 57.0, null), notes.map { it.pitch }, csv)
        // durations add up
        val ph = rows[0].doc.tiers.first { it is IntervalTier && it.name == "phones" } as IntervalTier
        assertTrue(abs(ph.end - notes.last().end) < 1e-5)
        val cut = Dataset.cutAudio(audio, segs[0].from, segs[0].to, 16000)
        assertTrue(abs(((segs[0].to - segs[0].from) * 16000).toInt() - cut.samples.size) <= 2)
    }

    @Test
    fun resampleKeepsTone() {
        val a = Audio(48000, FloatArray(48000) { sin(2 * PI * 440.0 * it / 48000).toFloat() })
        val b = Dataset.resample(a, 44100)
        assertEquals(44100, b.samples.size)
        val i = 22050
        val expect = sin(2 * PI * 440.0 * i / 44100)
        assertTrue(abs(b.samples[i] - expect) < 0.02, "${b.samples[i]} vs $expect")
    }
}

class DatasetStatsTest {
    @kotlin.test.Test
    fun countsPhonemesNotesAndDictionary() {
        val t1 = mlabeler.core.model.IntervalTier("phones", listOf(0.0, 1.0, 1.5, 2.0, 3.0), listOf("SP", "k", "a", "SP"))
        val n = mlabeler.core.model.NoteTier("notes", listOf(mlabeler.core.model.Note(1.0, 2.0, 60.2)))
        val t2 = mlabeler.core.model.IntervalTier("phones", listOf(0.0, 0.5, 1.0), listOf("a", "zz"))
        val dict = mlabeler.core.ds.PhonemeDict("x", vowels = listOf("a", "i"), consonants = listOf("k"))
        val s = mlabeler.core.ds.DatasetStats.of(
            listOf(mlabeler.core.model.LabelDoc(listOf(t1, n)), mlabeler.core.model.LabelDoc(listOf(t2))), setOf("SP", "AP"), dict)
        kotlin.test.assertEquals(4.0, s.seconds, 1e-9)
        kotlin.test.assertEquals(2.0, s.singingSeconds, 1e-9)
        val a = s.phonemes.first { it.name == "a" }
        kotlin.test.assertEquals(2, a.count); kotlin.test.assertEquals(2, a.files)
        kotlin.test.assertEquals(listOf("zz"), s.notInDictionary)
        kotlin.test.assertEquals(listOf("i"), s.unusedFromDictionary)
        kotlin.test.assertEquals(1, s.notes[60]?.count)
        kotlin.test.assertEquals(listOf("k", "zz"), s.rare(1).map { it.name }.filter { it != "SP" }.sorted().let { l -> l.filter { it != "a" } })
    }
}
