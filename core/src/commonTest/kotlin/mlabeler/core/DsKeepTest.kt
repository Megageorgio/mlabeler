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
