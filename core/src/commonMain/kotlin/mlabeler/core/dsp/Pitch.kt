package mlabeler.core.dsp

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A curve sampled every [hop] seconds. */
class Curve(val hop: Double, val values: FloatArray) {
    fun at(time: Double): Float {
        val i = (time / hop).toInt()
        return if (i in values.indices) values[i] else Float.NaN
    }
}

object Pitch {
    /**
     * f0 in Hz per frame (0 = unvoiced) with YIN on audio downsampled to about 11 kHz.
     * Fast enough for long files on phones; for precise curves use the toolkit's RMVPE.
     */
    fun yin(samples: FloatArray, sampleRate: Int, hop: Double = 0.005, fmin: Double = 55.0, fmax: Double = 1100.0, threshold: Double = 0.15): Curve {
        val factor = max(1, sampleRate / 11025)
        val sr = sampleRate.toDouble() / factor
        val n = samples.size / factor
        val x = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (k in 0 until factor) acc += samples[i * factor + k]
            x[i] = acc / factor
        }
        val tauMin = max(2, (sr / fmax).toInt())
        val tauMax = min((sr / fmin).toInt(), 2048)
        val w = tauMax + 64
        val hopS = max(1, (hop * sr).toInt())
        val frames = max(0, (n - w - tauMax) / hopS + 1)
        val out = FloatArray(max(frames, 0))
        val d = DoubleArray(tauMax + 1)
        for (f in 0 until frames) {
            val s = f * hopS
            // silence is unvoiced
            var energy = 0.0
            for (i in s until s + w) energy += x[i] * x[i]
            if (energy / w < 1e-6) { out[f] = 0f; continue }
            for (tau in 1..tauMax) {
                var sum = 0.0
                for (i in s until s + w) {
                    val diff = x[i] - x[i + tau]
                    sum += diff * diff
                }
                d[tau] = sum
            }
            // cumulative mean normalised difference
            var running = 0.0
            var best = -1
            for (tau in 1..tauMax) {
                running += d[tau]
                val cm = if (running == 0.0) 1.0 else d[tau] * tau / running
                d[tau] = cm
            }
            var tau = tauMin
            while (tau <= tauMax) {
                if (d[tau] < threshold) {
                    while (tau + 1 <= tauMax && d[tau + 1] < d[tau]) tau++
                    best = tau
                    break
                }
                tau++
            }
            if (best < 0) { out[f] = 0f; continue }
            // parabolic interpolation
            val t0 = if (best > 1 && best < tauMax) {
                val a = d[best - 1]; val b = d[best]; val c = d[best + 1]
                val den = a - 2 * b + c
                if (den != 0.0) best + (a - c) / (2 * den) else best.toDouble()
            } else best.toDouble()
            out[f] = (sr / t0).toFloat()
        }
        // the curve starts at the window centre
        val shift = ((w / 2.0) / sr / hop).toInt()
        val aligned = FloatArray(out.size + shift)
        out.copyInto(aligned, shift)
        return Curve(hopS / sr, aligned)
    }

    /** RMS level in dB per frame. */
    fun power(samples: FloatArray, sampleRate: Int, hop: Double = 0.005, window: Double = 0.02): Curve {
        val h = max(1, (hop * sampleRate).toInt())
        val w = max(h, (window * sampleRate).toInt())
        val frames = max(1, samples.size / h)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            val c = f * h
            val a = max(0, c - w / 2)
            val b = min(samples.size, c + w / 2)
            var sum = 0.0
            for (i in a until b) sum += samples[i] * samples[i]
            val rms = if (b > a) sqrt(sum / (b - a)) else 0.0
            out[f] = (20 * log10(rms + 1e-9)).toFloat()
        }
        return Curve(h.toDouble() / sampleRate, out)
    }

    fun hzToMidi(hz: Double) = 69 + 12 * log2(hz / 440.0)
}
