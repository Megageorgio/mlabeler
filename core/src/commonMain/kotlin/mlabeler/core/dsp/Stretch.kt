package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** Changing the tempo and the key of a recording independently (WSOLA time-stretch + resampling), for practice. */
object Stretch {
    /**
     * [x] played at [speed] (0.5 = half as fast) and [semitones] higher or lower. Good enough for a backing track to
     * sing along; not for the dataset itself.
     */
    fun process(x: FloatArray, sampleRate: Int, speed: Double = 1.0, semitones: Double = 0.0): FloatArray {
        if (speed == 1.0 && semitones == 0.0) return x
        val f = 2.0.pow(semitones / 12.0)
        val stretched = wsola(x, sampleRate, speed / f)
        return if (f == 1.0) stretched else resample(stretched, f)
    }

    /** Reads [x] [step] samples at a time with linear interpolation (step > 1: shorter and higher). */
    fun resample(x: FloatArray, step: Double): FloatArray {
        val n = (x.size / step).toInt()
        return FloatArray(n) { i ->
            val p = i * step
            val a = p.toInt()
            val t = (p - a).toFloat()
            val va = x.getOrElse(a) { 0f }
            val vb = x.getOrElse(a + 1) { va }
            va + (vb - va) * t
        }
    }

    /** Time-stretch without changing the pitch: [rate] < 1 makes it longer (slower), > 1 shorter. */
    fun wsola(x: FloatArray, sampleRate: Int, rate: Double): FloatArray {
        if (rate == 1.0 || x.isEmpty()) return x
        val n = (sampleRate * 0.04).toInt().let { it - it % 2 }      // frame
        val hopOut = n / 2
        val hopIn = hopOut * rate
        val delta = (sampleRate * 0.01).toInt()                       // how far a frame may move to fit
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val outLen = (x.size / rate).toInt() + n
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)
        var prevStart = 0
        var k = 0
        val cmp = n / 4
        while (true) {
            val outPos = k * hopOut
            val nominal = (k * hopIn).toInt()
            if (nominal + n >= x.size || outPos + n >= outLen) break
            var best = nominal
            if (k > 0) {
                // the frame start that continues the previous frame best (its natural next half-frame)
                val natural = prevStart + hopOut
                var bestScore = Double.NEGATIVE_INFINITY
                var d = -delta
                while (d <= delta) {
                    val s = nominal + d
                    if (s >= 0 && s + n < x.size && natural + cmp < x.size) {
                        var c = 0.0
                        var j = 0
                        while (j < cmp) { c += x[s + j] * x[natural + j]; j += 4 }
                        if (c > bestScore) { bestScore = c; best = s }
                    }
                    d += 2
                }
            }
            for (j in 0 until n) {
                out[outPos + j] += x[best + j] * window[j]
                norm[outPos + j] += window[j]
            }
            prevStart = best
            k++
        }
        val len = (x.size / rate).toInt().coerceAtMost(outLen)
        return FloatArray(len) { i -> if (norm[i] > 1e-3f) out[i] / norm[i] else out[i] }
    }
}
