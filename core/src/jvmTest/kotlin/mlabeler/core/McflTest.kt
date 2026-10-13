package mlabeler.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.core.audio.Audio
import mlabeler.core.mcfl.Mcfl
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in MCFL model run in Kotlin against the package's own Python + onnxruntime (mcfl-golden.json, made from
 * the same synthetic sound with model.onnx in fp32).
 */
class McflTest {
    private val dir = File("../app/builtin/mcfl/MCFL_multi13_s_v0.0.1")
    private val model by lazy { Mcfl(File(dir, "model.json").readText(), File(dir, "model.safetensors").readBytes()) }
    private val golden by lazy { Json.parseToJsonElement(javaClass.getResource("/mcfl-golden.json")!!.readText()).jsonObject }

    private fun synth(): Audio {
        val sr = 16000
        val n = (4.5 * sr).toInt()
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            if (i >= 2 * sr && i < (2.8 * sr).toInt()) 0f
            else (0.5 * sin(2 * PI * 220 * t) * (1 + 0.5 * sin(2 * PI * 3 * t)) + 0.2 * sin(2 * PI * (300 + 400 * t) * t)).toFloat()
        }
        return Audio(sr, x)
    }

    @Test
    fun languagesAndLabels() {
        assertEquals(listOf("de", "el", "en", "es", "fr", "it", "ja", "ko", "nan", "pt", "ru", "yue", "zh"), model.languages)
        assertEquals(359, model.labels.size)
    }

    @Test
    fun framesMatchOnnx() = runBlocking {
        for (lang in listOf("ru", "ja")) {
            val g = golden["frames"]!!.jsonObject[lang]!!.jsonObject
            val (lp, off) = model.debugFrames(synth(), lang)
            val nl = model.labels.size
            for ((f, v) in g) {
                val fr = f.toInt()
                val top = v.jsonObject["top"]!!.jsonArray.map { it.jsonPrimitive.int }
                val logp = v.jsonObject["logp"]!!.jsonArray.map { it.jsonPrimitive.double }
                assertEquals(top[0], (0 until nl).maxBy { lp[fr * nl + it] }, "$lang frame $f: best label")
                for (k in 0 until 3) assertTrue(abs(lp[fr * nl + top[k]] - logp[k]) < 0.05, "$lang frame $f label ${top[k]}: ${lp[fr * nl + top[k]]} vs ${logp[k]}")
                val o = v.jsonObject["off"]!!.jsonArray.map { it.jsonPrimitive.double }
                assertTrue(abs(off[fr * 2] - o[0]) < 0.01 && abs(off[fr * 2 + 1] - o[1]) < 0.01, "$lang frame $f offsets")
            }
        }
    }

    @Test
    fun knownPhonemesArePlacedWhereTheModelHearsThem() = runBlocking {
        // the phonemes it recognises itself, given back: the same names, the boundaries close to its own
        val got = model.label(synth(), "ru", confidence = true)
        val aligned = model.align(synth(), "ru", got.map { it.label }, threads = 2, confidence = true)
        assertEquals(got.map { it.label }, aligned.map { it.label })
        val d = got.zip(aligned).drop(1).map { (a, b) -> abs(a.start - b.start) }.sorted()
        assertTrue(d[d.size / 2] < 0.03, "median boundary difference ${d[d.size / 2]} s")
        assertEquals(0.0, aligned.first().start); assertEquals(4.5, aligned.last().end, 1e-6)
        assertTrue(aligned.zipWithNext().all { (a, b) -> a.end == b.start && b.start > a.start })
        assertTrue(aligned.all { it.confidence != null })
    }

    @Test
    fun segmentsMatchPython() = runBlocking {
        for (lang in listOf("ru", "ja")) {
            val want = golden["segments"]!!.jsonObject[lang]!!.jsonArray.map { s -> s.jsonArray.let { Triple(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.content) } }
            val got = model.label(synth(), lang, threads = 2)
            assertEquals(want.map { it.third }, got.map { it.label }, lang)
            for ((w, s) in want.zip(got)) assertTrue(abs(w.first - s.start) < 0.005 && abs(w.second - s.end) < 0.005, "$lang: $w vs $s")
        }
    }
}
