package mlabeler.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.core.format.Csv
import mlabeler.core.format.DsCsv
import mlabeler.core.format.DsFile
import mlabeler.core.model.IntervalTier
import kotlin.test.Test
import kotlin.test.assertEquals

class DsKeepTest {
    private val ds = """
        [{"offset": 1.0, "text": "a", "ph_seq": "SP k a SP", "ph_dur": "0.1 0.1 0.3 0.1", "ph_num": "1 2 1",
          "note_seq": "rest C4 rest", "note_dur": "0.1 0.4 0.1", "note_slur": "0 0 0", "f0_seq": "1 2 3", "f0_timestep": "0.005"},
         {"offset": 3.0, "text": "b", "ph_seq": "SP s o SP", "ph_dur": "0.2 0.1 0.5 0.2", "ph_num": "1 2 1",
          "note_seq": "rest D4 rest", "note_dur": "0.2 0.6 0.2", "note_slur": "0 0 0", "f0_seq": "4 5 6", "f0_timestep": "0.005"}]
    """.trimIndent()

    @Test
    fun sentencesStay() {
        val doc = DsFile.read(ds, 5.0)
        val ph = doc.tiers[doc.phonemeTierIndex()] as IntervalTier
        // move the boundary k|a of the first sentence 50 ms later
        val k = ph.texts.indexOf("k")
        val moved = doc.copy(tiers = doc.tiers.map { if (it === ph) ph.copy(bounds = ph.bounds.toMutableList().also { b -> b[k + 1] += 0.05 }) else it })
        val out = Json.parseToJsonElement(DsFile.write(moved, ds)).jsonArray
        assertEquals(2, out.size)
        val s0 = out[0].jsonObject
        val s1 = out[1].jsonObject
        assertEquals(1.0, s0["offset"]!!.jsonPrimitive.content.toDouble())
        assertEquals(3.0, s1["offset"]!!.jsonPrimitive.content.toDouble())
        assertEquals("SP k a SP", s0["ph_seq"]!!.jsonPrimitive.content)
        assertEquals(listOf(0.1, 0.15, 0.25, 0.1), s0["ph_dur"]!!.jsonPrimitive.content.split(" ").map { it.toDouble() })
        assertEquals("SP s o SP", s1["ph_seq"]!!.jsonPrimitive.content)
        assertEquals("1 2 1", s1["ph_num"]!!.jsonPrimitive.content)
        assertEquals("4 5 6", s1["f0_seq"]!!.jsonPrimitive.content)
        assertEquals("b", s1["text"]!!.jsonPrimitive.content)
        assertEquals("rest D4 rest", s1["note_seq"]!!.jsonPrimitive.content)
    }

    @Test
    fun drawnF0GoesIntoTheSentence() {
        val doc = DsFile.read(ds, 5.0)
        // second sentence starts at 3.0, step 5 ms: its middle point is at 3.005
        val out = Json.parseToJsonElement(DsFile.write(doc, ds) { t -> if (kotlin.math.abs(t - 3.005) < 1e-6) 440f else null }).jsonArray
        assertEquals("1 2 3", out[0].jsonObject["f0_seq"]!!.jsonPrimitive.content)
        assertEquals("4 440 6", out[1].jsonObject["f0_seq"]!!.jsonPrimitive.content)
        assertEquals(2, DsFile.readF0(ds).size)
    }

    @Test
    fun csvKeepsOtherColumnsAndRows() {
        val text = "name,ph_seq,ph_dur,ph_num,extra\nx,SP a SP,0.1 0.2 0.1,1 1 1,keep me\ny,SP o SP,0.1 0.3 0.1,1 1 1,me too\n"
        val rows = DsCsv.read(text)
        val doc = rows.first { it.name == "y" }.doc
        val ph = doc.tiers[doc.phonemeTierIndex()] as IntervalTier
        val edited = doc.copy(tiers = doc.tiers.map { if (it === ph) ph.copy(texts = listOf("SP", "u", "SP")) else it })
        val out = DsCsv.update(Csv.parse(text), "y", edited)
        assertEquals(listOf("name", "ph_seq", "ph_dur", "ph_num", "extra"), out[0])
        assertEquals(listOf("x", "SP a SP", "0.1 0.2 0.1", "1 1 1", "keep me"), out[1])
        assertEquals("SP u SP", out[2][1])
        assertEquals("me too", out[2][4])
    }
}

class RemoveTimeTest {
    @Test
    fun cutsAndShifts() {
        val t = IntervalTier("phones", listOf(0.0, 1.0, 2.0, 3.0), listOf("A", "B", "C"))
        val d = mlabeler.core.edit.Edits.removeTime(mlabeler.core.model.LabelDoc(listOf(t)), 0.5, 2.5).tiers[0] as IntervalTier
        assertEquals(listOf(0.0, 0.5, 1.0), d.bounds)
        assertEquals(listOf("A", "C"), d.texts)
        val e = mlabeler.core.edit.Edits.removeTime(mlabeler.core.model.LabelDoc(listOf(t)), 1.5, 2.5).tiers[0] as IntervalTier
        assertEquals(listOf(0.0, 1.0, 1.5, 2.0), e.bounds)
    }

    @Test
    fun wavShortens() {
        val sr = 1000
        val samples = FloatArray(1000) { if (it < 500) 0.5f else -0.5f }
        val wav = mlabeler.core.audio.Wav.encode16(mlabeler.core.audio.Audio(sr, samples))
        val out = mlabeler.core.audio.WavEdit(wav).withoutFrames(200, 700, 5)
        val back = mlabeler.core.audio.Wav.decode(out)
        assertEquals(500, back.samples.size)
        assertEquals(0.5f, back.samples[100], 1e-3f)
        assertEquals(-0.5f, back.samples[300], 1e-3f)
    }
}

class FormantsTest {
    @Test
    fun findsResonances() {
        // a buzz at 120 Hz through two resonances (700 and 1200 Hz) — a rough "a"
        val sr = 22050
        val n = sr / 2
        val src = FloatArray(n) { if (it % (sr / 120) == 0) 1f else 0f }
        fun reson(x: FloatArray, f: Double, bw: Double): FloatArray {
            val r = kotlin.math.exp(-kotlin.math.PI * bw / sr)
            val c = 2 * r * kotlin.math.cos(2 * kotlin.math.PI * f / sr)
            val y = FloatArray(x.size)
            for (i in x.indices) y[i] = (x[i] + c * (if (i > 0) y[i - 1] else 0f) - r * r * (if (i > 1) y[i - 2] else 0f)).toFloat()
            return y
        }
        val v = reson(reson(src, 700.0, 80.0), 1200.0, 90.0)
        val t = mlabeler.core.dsp.Formants.track(v, sr)
        val mid = t.f[0].size / 2
        val f1 = t.f[0][mid]; val f2 = t.f[1][mid]
        kotlin.test.assertTrue(kotlin.math.abs(f1 - 700) < 120, "F1 $f1")
        kotlin.test.assertTrue(kotlin.math.abs(f2 - 1200) < 150, "F2 $f2")
    }
}

class NotesLengthTest {
    @Test
    fun notesMustCoverTheSentence() {
        val ph = IntervalTier("phones", listOf(0.0, 0.2, 0.5, 0.8), listOf("SP", "a", "SP"))
        fun doc(end: Double) = mlabeler.core.model.LabelDoc(listOf(ph, mlabeler.core.model.NoteTier("notes",
            listOf(mlabeler.core.model.Note(0.0, 0.2, null), mlabeler.core.model.Note(0.2, end, 60.0)))))
        assertEquals(0, mlabeler.core.check.Checks.notesLength(doc(0.8), ph, 0).size)
        assertEquals(1, mlabeler.core.check.Checks.notesLength(doc(0.7), ph, 0).size)
    }
}
