package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** The first three formants per frame (Hz, NaN where none was found) every [hop] seconds. */
class FormantTrack(val hop: Double, val f: List<FloatArray>)

/**
 * Formants by linear prediction, as speech tools usually do it: the sound brought down to about 11 kHz,
 * pre-emphasised, 25 ms Hamming frames every 10 ms, LPC of order 10, the roots of the predictor turned into
 * frequencies and bandwidths. Quiet frames are skipped.
 */
object Formants {
    fun track(samples: FloatArray, sampleRate: Int, hop: Double = 0.01, order: Int = 10): FormantTrack {
        val factor = maxOf(1, sampleRate / 11025)
        val sr = sampleRate.toDouble() / factor
        // averaging blocks of [factor] samples is a rough low-pass, enough below 5 kHz
        val n = samples.size / factor
        val x = DoubleArray(n) { i -> var s = 0.0; for (k in 0 until factor) s += samples[i * factor + k]; s / factor }
        for (i in n - 1 downTo 1) x[i] -= 0.97 * x[i - 1]
        val win = (0.025 * sr).toInt()
        val step = (hop * sr).toInt().coerceAtLeast(1)
        val frames = if (n < win) 0 else (n - win) / step + 1
        val out = List(3) { FloatArray(frames) { Float.NaN } }
        val w = DoubleArray(win) { 0.54 - 0.46 * cos(2 * PI * it / (win - 1)) }
        val energy = DoubleArray(frames) { fr -> var e = 0.0; for (i in 0 until win) { val v = x[fr * step + i]; e += v * v }; e / win }
        val loud = (energy.maxOrNull() ?: 0.0) * 1e-4
        val frame = DoubleArray(win)
        for (fr in 0 until frames) {
            if (energy[fr] < loud || energy[fr] <= 0) continue
            for (i in 0 until win) frame[i] = x[fr * step + i] * w[i]
            val a = lpc(frame, order) ?: continue
            val found = roots(a).filter { it.second > 0 }.map { (re, im) ->
                val f = atan2(im, re) * sr / (2 * PI)
                val bw = -ln(sqrt(re * re + im * im)) * sr / PI
                f to bw
            }.filter { (f, bw) -> f > 90 && f < sr / 2 - 50 && bw < 400 }.map { it.first }.sorted()
            for (k in 0 until minOf(3, found.size)) out[k][fr] = found[k].toFloat()
        }
        return FormantTrack(step / sr, out)
    }

    /** Predictor 1, a1 … ap by autocorrelation and Levinson–Durbin; null for silence. */
    private fun lpc(x: DoubleArray, p: Int): DoubleArray? {
        val r = DoubleArray(p + 1) { k -> var s = 0.0; for (i in 0 until x.size - k) s += x[i] * x[i + k]; s }
        if (r[0] <= 1e-12) return null
        r[0] *= 1.0001
        val a = DoubleArray(p + 1).also { it[0] = 1.0 }
        var e = r[0]
        for (i in 1..p) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val k = -acc / e
            val prev = a.copyOf()
            for (j in 1 until i) a[j] = prev[j] + k * prev[i - j]
            a[i] = k
            e *= 1 - k * k
            if (e <= 0) return null
        }
        return a
    }

    /** Roots of 1 + a1 z^-1 + … (that is z^p + a1 z^(p-1) + …) by Durand–Kerner: pairs of (re, im). */
    private fun roots(a: DoubleArray): List<Pair<Double, Double>> {
        val p = a.size - 1
        val re = DoubleArray(p) { cos(2 * PI * it / p + 0.4) * 0.9 }
        val im = DoubleArray(p) { kotlin.math.sin(2 * PI * it / p + 0.4) * 0.9 }
        repeat(200) {
            var moved = 0.0
            for (i in 0 until p) {
                // value of the polynomial at root i
                var vr = 1.0; var vi = 0.0
                for (k in 1..p) { val nr = vr * re[i] - vi * im[i] + a[k]; val ni = vr * im[i] + vi * re[i]; vr = nr; vi = ni }
                // product of differences to the other roots
                var dr = 1.0; var di = 0.0
                for (j in 0 until p) if (j != i) {
                    val xr = re[i] - re[j]; val xi = im[i] - im[j]
                    val nr = dr * xr - di * xi; val ni = dr * xi + di * xr; dr = nr; di = ni
                }
                val den = dr * dr + di * di
                if (den < 1e-30) continue
                val qr = (vr * dr + vi * di) / den
                val qi = (vi * dr - vr * di) / den
                re[i] -= qr; im[i] -= qi
                moved += abs(qr) + abs(qi)
            }
            if (moved < 1e-10) return (0 until p).map { re[it] to im[it] }
        }
        return (0 until p).map { re[it] to im[it] }
    }
}
