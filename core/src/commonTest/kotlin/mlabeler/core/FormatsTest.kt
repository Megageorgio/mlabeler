package mlabeler.core

import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.dsp.Peaks
import mlabeler.core.dsp.Spectrogram
import mlabeler.core.edit.BoundRef
import mlabeler.core.edit.Edits
import mlabeler.core.edit.History
import mlabeler.core.edit.IntervalRef
import mlabeler.core.edit.MoveOptions
import mlabeler.core.format.AudacityLabels
import mlabeler.core.format.DsCsv
import mlabeler.core.format.HtkLab
import mlabeler.core.format.NoteNames
import mlabeler.core.format.OtoIni
import mlabeler.core.format.TextGridFormat
import mlabeler.core.io.naturalOrder
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.NoteTier
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatsTest {
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a != $b")

    @Test
    fun labRoundTrip() {
        val text = "0 1500000 pau\n1500000 2000000 k\n2000000 5000000 a\n"
        val doc = HtkLab.read(text)
        val t = doc.tiers[0] as IntervalTier
        assertEquals(listOf("pau", "k", "a"), t.texts)
        near(t.endOf(1), 0.2)
        assertEquals(text, HtkLab.write(doc))
    }

    @Test
    fun labGapsBecomeEmptyIntervals() {
        val doc = HtkLab.read("1000000 2000000 a\n3000000 4000000 b\n", duration = 0.5)
        val t = doc.tiers[0] as IntervalTier
        assertEquals(listOf("", "a", "", "b", ""), t.texts)
        near(t.end, 0.5)
    }

    @Test
    fun textGridLongAndShort() {
        val long = """
            File type = "ooTextFile"
            Object class = "TextGrid"

            xmin = 0
            xmax = 1.5
            tiers? <exists>
            size = 2
            item []:
                item [1]:
                    class = "IntervalTier"
                    name = "words"
                    xmin = 0
                    xmax = 1.5
                    intervals: size = 2
                    intervals [1]:
                        xmin = 0
                        xmax = 0.5
                        text = ""
                    intervals [2]:
                        xmin = 0.5
                        xmax = 1.5
                        text = "ka QQxQQQ
                item [2]:
                    class = "TextTier"
                    name = "marks"
                    xmin = 0
                    xmax = 1.5
                    points: size = 1
                    points [1]:
                        number = 0.7
                        mark = "p"
        """.trimIndent().replace("Q", "\"")
        val doc = TextGridFormat.read(long)
        val w = doc.tiers[0] as IntervalTier
        assertEquals("words", w.name)
        assertEquals("ka \"x\"", w.texts[1])
        val again = TextGridFormat.read(TextGridFormat.write(doc))
        assertEquals(doc, again)
        val short = "\"ooTextFile\"\n\"TextGrid\"\n0\n1\n<exists>\n1\n\"IntervalTier\"\n\"phones\"\n0\n1\n2\n0\n0.4\n\"a\"\n0.4\n1\n\"b\"\n"
        val s = TextGridFormat.read(short).tiers[0] as IntervalTier
        assertEquals(listOf("a", "b"), s.texts)
    }

    @Test
    fun audacity() {
        val doc = AudacityLabels.read("0.000000\t0.500000\ta\n\\\t100\t200\n0.500000\t1.000000\tb\n")
        assertEquals(listOf("a", "b"), (doc.tiers[0] as IntervalTier).texts)
        assertTrue(AudacityLabels.write(doc).startsWith("0\t0.5\ta\n"))
    }

    @Test
    fun dsCsv() {
        val csv = "name,ph_seq,ph_dur,ph_num,note_seq,note_dur,note_slur\nx,SP k a SP,0.1 0.05 0.3 0.2,1 2 1,rest C#4 rest,0.1 0.35 0.2,0 0 0\n"
        val rows = DsCsv.read(csv)
        val doc = rows[0].doc
        assertEquals(listOf("SP", "k", "a", "SP"), (doc.tiers[doc.phonemeTierIndex()] as IntervalTier).texts)
        assertEquals(listOf(1, 2, 1), DsCsv.phNum(doc))
        assertEquals(61.0, (doc.tiers.last() as NoteTier).notes[1].pitch)
        assertEquals(csv, DsCsv.write(rows))
    }

    @Test
    fun noteNames() {
        assertEquals(60.0, NoteNames.parse("C4"))
        assertEquals(70.0, NoteNames.parse("Bb4"))
        assertEquals("A4", NoteNames.format(69.0))
        assertEquals("A4+20", NoteNames.format(69.2))
    }

    @Test
    fun oto() {
        val text = "a.wav=- a,100,50,-300,80,20\r\na.wav=a,500,40,0,60,-10\r\n"
        val e = OtoIni.read(text)
        near(e[0].endMs(1000.0), 400.0)
        near(e[1].endMs(1000.0), 1000.0)
        near(e[1].absolute(1000.0).overlap, 490.0)
        assertEquals(text, OtoIni.write(e))
    }

    @Test
    fun moveSingleRippleLinked() {
        val ph = IntervalTier("phones", listOf(0.0, 0.5, 0.7, 1.0), listOf("a", "b", "c"))
        val w = IntervalTier("words", listOf(0.0, 0.5, 1.0), listOf("x", "y"))
        val doc = LabelDoc(listOf(w, ph))
        val moved = Edits.moveBound(doc, BoundRef(1, 1), 0.6, 1.0)
        near((moved.tiers[1] as IntervalTier).bounds[1], 0.6)
        near((moved.tiers[0] as IntervalTier).bounds[1], 0.6) // linked
        val clamped = Edits.moveBound(doc, BoundRef(1, 1), 0.9, 1.0, MoveOptions(linked = false))
        near((clamped.tiers[1] as IntervalTier).bounds[1], 0.699)
        val ripple = Edits.moveBound(doc, BoundRef(1, 1), 0.6, 1.0, MoveOptions(ripple = true, linked = false))
        assertEquals(listOf(0.0, 0.6, 0.7999999999999999, 1.0).map { (it * 1e6).toLong() }, (ripple.tiers[1] as IntervalTier).bounds.map { (it * 1e6).toLong() })
    }

    @Test
    fun splitMergeHistory() {
        val doc = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 1.0), listOf("a"))))
        val h = History(doc)
        val (split, ref) = Edits.split(doc, 0, 0.4, "b")!!
        h.push(split)
        assertEquals(BoundRef(0, 1), ref)
        assertEquals(listOf("a", "b"), (h.current.tiers[0] as IntervalTier).texts)
        h.push(Edits.mergeWithNext(h.current, IntervalRef(0, 0)))
        assertEquals(listOf("a"), (h.current.tiers[0] as IntervalTier).texts)
        assertTrue(h.dirty)
        h.undo(); h.undo()
        assertEquals(doc, h.current)
        assertTrue(!h.dirty)
    }

    @Test
    fun wavAndAnalysis() {
        val sr = 16000
        val samples = FloatArray(sr) { (0.5 * sin(2 * Math.PI * 440 * it / sr)).toFloat() }
        val audio = Wav.decode(Wav.encode16(Audio(sr, samples)))
        assertEquals(sr, audio.samples.size)
        assertTrue(abs(audio.samples[100] - samples[100]) < 1e-3)
        val peaks = Peaks.build(audio.samples, sr)
        val (lo, hi) = peaks.range(audio.samples, 0, sr)
        assertTrue(hi > 0.49 && lo < -0.49)
        val spec = Spectrogram.compute(audio.samples, sr)
        // strongest band should be near 440 Hz
        val f = spec.frames / 2
        val best = (0 until spec.bands).maxBy { spec.value(f, it) }
        val hz = spec.freqOfBand(best + 0.5)
        assertTrue(hz in 380.0..520.0, "peak at $hz")
    }

    @Test
    fun natural() {
        assertEquals(listOf("a1", "a2", "a10"), listOf("a10", "a2", "a1").sortedWith(naturalOrder()))
    }
}

private object Math { const val PI = kotlin.math.PI }

class OtoEditsTest {
    @Test
    fun lockedAndSingleMoves() {
        val e = mlabeler.core.format.OtoEntry("a.wav", "a", 100.0, 50.0, -300.0, 80.0, 20.0)
        val a = e.absolute(1000.0)
        val locked = mlabeler.core.format.OtoEdits.move(a, mlabeler.core.format.OtoMarker.Preutterance, 280.0, 1000.0, true)
        assertEquals(200.0, locked.left)
        assertEquals(500.0, locked.right)
        val back = mlabeler.core.format.OtoEdits.set(e, locked, 1000.0)
        assertEquals(-300.0, back.cutoff)
        assertEquals(80.0, back.preutterance)
        val single = mlabeler.core.format.OtoEdits.move(a, mlabeler.core.format.OtoMarker.Left, 500.0, 1000.0, false)
        assertEquals(150.0, single.left) // stops at the consonant end
        val clamped = mlabeler.core.format.OtoEdits.move(a, mlabeler.core.format.OtoMarker.Right, 2000.0, 1000.0, true)
        assertEquals(1000.0, clamped.right)
    }
}

class PitchTest {
    @Test
    fun yinFindsSine() {
        val sr = 44100
        val x = FloatArray(sr) { (0.5 * kotlin.math.sin(2 * kotlin.math.PI * 220.0 * it / sr)).toFloat() }
        val c = mlabeler.core.dsp.Pitch.yin(x, sr)
        val mid = c.values[c.values.size / 2]
        assertTrue(mid in 215f..225f, "f0 $mid")
        val p = mlabeler.core.dsp.Pitch.power(x, sr)
        assertTrue(p.values[p.values.size / 2] in -10f..-8f, "power ${p.values[p.values.size / 2]}")
    }
}

class RangeEditsTest {
    @Test
    fun replaceMiddle() {
        val t = IntervalTier("phones", listOf(0.0, 1.0, 2.0, 3.0), listOf("a", "b", "c"))
        val part = IntervalTier("phones", listOf(0.5, 1.2, 2.5), listOf("x", "y"))
        val r = mlabeler.core.edit.RangeEdits.replace(t, 0.5, 2.5, part)
        assertEquals(listOf("a", "x", "y", "c"), r.texts)
        assertEquals(listOf(0.0, 0.5, 1.2, 2.5, 3.0), r.bounds)
    }
}

class StretchTest {
    @Test
    fun halfSpeedKeepsPitch() {
        val sr = 44100
        val x = FloatArray(sr) { (0.5 * kotlin.math.sin(2 * kotlin.math.PI * 220.0 * it / sr)).toFloat() }
        val y = mlabeler.core.dsp.Stretch.wsola(x, sr, 0.5)
        assertTrue(y.size in (sr * 19 / 10)..(sr * 21 / 10), "length ${y.size}")
        val f = mlabeler.core.dsp.Pitch.yin(y, sr)
        val mid = f.values[f.values.size / 2]
        assertTrue(mid in 212f..228f, "f0 $mid")
    }
}

class AutoOtoTest {
    private fun synth(sr: Int, parts: List<Triple<Double, Double, Int>>): FloatArray {
        // (duration s, f0 Hz or 0 = noise, -1 = silence)
        val out = ArrayList<Float>()
        val rnd = kotlin.random.Random(1)
        var ph = 0.0
        for ((dur, f0, kind) in parts.map { Triple(it.first, it.second, it.third) }) {
            val n = (dur * sr).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = min(1.0, min(t / 0.02, (dur - t) / 0.02))
                out += when (kind) {
                    -1 -> (rnd.nextDouble() - 0.5).toFloat() * 0.002f
                    0 -> (rnd.nextDouble() - 0.5).toFloat() * 0.2f
                    else -> { ph += 2 * kotlin.math.PI * f0 / sr; ((0.5 * kotlin.math.sin(ph) + 0.2 * kotlin.math.sin(2 * ph)) * env).toFloat() }
                }
            }
        }
        return out.toFloatArray()
    }
    private fun min(a: Double, b: Double) = kotlin.math.min(a, b)

    @Test
    fun namesAndSegments() {
        val syl = mlabeler.core.oto.Syllables.fromName("_かさなきゃ")
        assertEquals(listOf("か", "さ", "な", "きゃ"), syl.map { it.text })
        assertEquals("ky", syl[3].consonant)
        assertEquals("a", syl[3].vowel)
        assertEquals(listOf("ma", "mo"), mlabeler.core.oto.Syllables.fromName("ma_mo").map { it.text })
        val sr = 22050
        val x = synth(sr, listOf(
            Triple(0.4, 0.0, -1), Triple(0.08, 0.0, 0), Triple(0.45, 220.0, 1), Triple(0.12, 0.0, 0),
            Triple(0.5, 247.0, 1), Triple(0.12, 0.0, 0), Triple(0.6, 262.0, 1), Triple(0.5, 0.0, -1),
        ))
        val t = mlabeler.core.oto.AutoOto.segment(x, sr, mlabeler.core.oto.Syllables.fromName("_かさな"))
        assertEquals(3, t.size)
        val expectC = listOf(0.40, 0.93, 1.55)
        val expectV = listOf(0.48, 1.05, 1.67)
        for (k in 0..2) {
            assertTrue(kotlin.math.abs(t[k].cStart - expectC[k]) < 0.04, "c$k ${t[k].cStart}")
            assertTrue(kotlin.math.abs(t[k].vStart - expectV[k]) < 0.04, "v$k ${t[k].vStart}")
        }
        val e = mlabeler.core.oto.AutoOto.entries("_かさな.wav", t, x.size * 1000.0 / sr, mlabeler.core.oto.AutoOtoSettings())
        assertEquals(listOf("- か", "a さ", "a な"), e.map { it.alias })
        assertTrue(e.all { it.preutterance > it.overlap && it.cutoff < 0 })
    }
}

class VLabelerImportTest {
    @Test
    fun otoProject() {
        val text = """{"version":4,"rootSampleDirectory":"/v/bank","labelerConf":{"name":"oto-plus.default","fields":[{"name":"fixed"},{"name":"preu"},{"name":"ovl"},{"name":"left"}]},
          "modules":[{"name":"","sampleDirectory":"","entries":[
            {"sample":"_あ.wav","name":"- あ","start":90,"end":700,"points":[300,250,120,100],"extras":[],"notes":{"done":true,"star":false,"tag":"x"}},
            {"sample":"_あ.wav","name":"a あ","start":500,"end":-100,"points":[800,750,620,600],"extras":[]}
          ],"currentIndex":0}]}"""
        val p = mlabeler.core.format.VLabelerProject.read(text)
        val e = p.oto["/v/bank"]!!
        assertEquals(100.0, e[0].offset)
        assertEquals(150.0, e[0].preutterance)
        assertEquals(-600.0, e[0].cutoff)
        assertEquals(100.0, e[1].cutoff)
        assertEquals(true, p.marks["/v/bank|_あ.wav|- あ"]!!.done)
    }
}
