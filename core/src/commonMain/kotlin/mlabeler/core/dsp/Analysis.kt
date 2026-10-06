package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 complex FFT. */
class Fft(val size: Int) {
    private val levels = 31 - size.countLeadingZeroBits()
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { sin(2 * PI * it / size) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] + im[l] * sinT[k]
                    val tim = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}

private object Integer {
    fun reverse(v: Int): Int {
        var x = v
        x = (x and 0x55555555 shl 1) or (x ushr 1 and 0x55555555)
        x = (x and 0x33333333 shl 2) or (x ushr 2 and 0x33333333)
        x = (x and 0x0F0F0F0F shl 4) or (x ushr 4 and 0x0F0F0F0F)
        x = (x shl 24) or ((x and 0xFF00) shl 8) or ((x ushr 8) and 0xFF00) or (x ushr 24)
        return x
    }
}

/**
 * Min/max pyramid for drawing a waveform at any zoom. Level 0 groups [base] samples, each next level 4× more.
 */
class Peaks private constructor(val sampleRate: Int, val length: Int, val base: Int, private val levels: List<Pair<FloatArray, FloatArray>>) {

    /** Min and max of samples in [from, to). */
    fun range(samples: FloatArray, from: Int, to: Int): Pair<Float, Float> {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(0, length)
        if (b <= a) return 0f to 0f
        val span = b - a
        // pick the coarsest level whose bucket is at most a quarter of the span
        var level = -1
        var bucket = base
        while (level + 1 < levels.size && bucket * 4 <= span) {
            level++
            bucket *= 4
        }
        if (level < 0 || span < base * 2) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in a until b) {
                val v = samples[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return lo to hi
        }
        bucket /= 4
        val (mins, maxs) = levels[level]
        val first = a / bucket
        val last = min((b - 1) / bucket, mins.size - 1)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in first..last) {
            if (mins[i] < lo) lo = mins[i]
            if (maxs[i] > hi) hi = maxs[i]
        }
        return lo to hi
    }

    companion object {
        fun build(samples: FloatArray, sampleRate: Int, base: Int = 32): Peaks {
            val levels = mutableListOf<Pair<FloatArray, FloatArray>>()
            var n = (samples.size + base - 1) / base
            var mins = FloatArray(n)
            var maxs = FloatArray(n)
            for (i in 0 until n) {
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                val end = min(samples.size, (i + 1) * base)
                for (j in i * base until end) {
                    val v = samples[j]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                mins[i] = lo
                maxs[i] = hi
            }
            levels += mins to maxs
            while (n > 4) {
                val m = (n + 3) / 4
                val nm = FloatArray(m) { Float.MAX_VALUE }
                val nx = FloatArray(m) { -Float.MAX_VALUE }
                for (i in 0 until n) {
                    val k = i / 4
                    nm[k] = min(nm[k], mins[i])
                    nx[k] = max(nx[k], maxs[i])
                }
                mins = nm
                maxs = nx
                n = m
                levels += mins to maxs
            }
            return Peaks(sampleRate, samples.size, base, levels)
        }
    }
}

/**
 * Mel-band spectrogram stored as dB values quantised to bytes over one range for the whole file,
 * so brightness does not jump between parts of the file.
 */
class Spectrogram(
    val sampleRate: Int,
    val hop: Int,
    val frames: Int,
    val bands: Int,
    val maxFreq: Double,
    val minDb: Float,
    val maxDb: Float,
    /** frames × bands, band 0 = lowest frequency. 0 = minDb, 255 = maxDb. */
    val data: ByteArray,
) {
    /** Frames computed so far; the rest of [data] is zero while computing. */
    var ready: Int = frames
        internal set

    fun timeOfFrame(f: Int): Double = (f * hop).toDouble() / sampleRate
    fun frameAt(time: Double): Int = (time * sampleRate / hop).toInt()
    fun value(frame: Int, band: Int): Int = data[frame * bands + band].toInt() and 0xFF

    /** Frequency (Hz) at the centre of [band]. */
    fun freqOfBand(band: Double): Double = melToHz(band / bands * hzToMel(maxFreq))

    companion object {
        fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun melToHz(m: Double) = 700.0 * (Math_pow10(m / 2595.0) - 1.0)
        private fun Math_pow10(x: Double) = exp(x * ln(10.0))

        fun compute(
            samples: FloatArray,
            sampleRate: Int,
            hopSeconds: Double = 0.0025,
            windowSeconds: Double = 0.025,
            bands: Int = 192,
            maxFreq: Double = 12000.0,
            minDb: Float = -100f,
            maxDb: Float = -10f,
            progress: (Spectrogram) -> Unit = {},
        ): Spectrogram {
            val b = Builder(samples, sampleRate, hopSeconds, windowSeconds, bands, maxFreq, minDb, maxDb)
            var f = 0
            while (f < b.frames) {
                val to = min(b.frames, f + 1024)
                b.fill(f, to)
                f = to
                b.result.ready = f
                if (f < b.frames) progress(b.result)
            }
            return b.result
        }
    }

    /**
     * Fills a spectrogram frame range by frame range; separate ranges can be filled at the same time
     * (each call has its own buffers), so a long recording can use every core.
     */
    class Builder(
        private val samples: FloatArray,
        sampleRate: Int,
        hopSeconds: Double,
        windowSeconds: Double,
        private val bands: Int,
        maxFreq: Double,
        private val minDb: Float,
        maxDb: Float,
    ) {
        private val hop = max(1, (hopSeconds * sampleRate).toInt())
        private val win: Int = run {
            var w = 64
            while (w * 2 <= windowSeconds * sampleRate * 1.5) w *= 2
            w
        }
        private val window = DoubleArray(win) { 0.35875 - 0.48829 * cos(2 * PI * it / (win - 1)) + 0.14128 * cos(4 * PI * it / (win - 1)) - 0.01168 * cos(6 * PI * it / (win - 1)) }
        private val wsum = window.sum()
        private val top = min(maxFreq, sampleRate / 2.0)
        val frames = max(1, (samples.size + hop - 1) / hop)
        private val nyqBins = win / 2
        private val edges = run {
            val melTop = hzToMel(top)
            DoubleArray(bands + 1) { melToHz(it * melTop / bands) * win / sampleRate }
        }
        private val data = ByteArray(frames * bands)
        private val scale = 255.0 / (maxDb - minDb)
        val result = Spectrogram(sampleRate, hop, frames, bands, top, minDb, maxDb, data).also { it.ready = 0 }

        /** Frames from the start that are done (the picture shows only those). */
        fun setReady(n: Int) { result.ready = n.coerceIn(0, frames) }

        /** Computes frames [from, to). */
        fun fill(from: Int, to: Int) {
            val fft = Fft(win)
            val re = DoubleArray(win)
            val im = DoubleArray(win)
            val mag = DoubleArray(nyqBins + 1)
            for (f in from until min(to, frames)) {
                val start = f * hop - win / 2
                for (i in 0 until win) {
                    val s = start + i
                    re[i] = if (s in samples.indices) samples[s] * window[i] else 0.0
                    im[i] = 0.0
                }
                fft.transform(re, im)
                for (k in 0..nyqBins) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * 2 / wsum
                for (b in 0 until bands) {
                    val lo = edges[b]
                    val hi = edges[b + 1]
                    var v: Double
                    if (hi - lo < 1.0) {
                        // narrow band: interpolate at its centre
                        val c = (lo + hi) / 2
                        val k = c.toInt().coerceIn(0, nyqBins - 1)
                        val t = c - k
                        v = mag[k] * (1 - t) + mag[k + 1] * t
                    } else {
                        v = 0.0
                        val a = lo.toInt().coerceIn(0, nyqBins)
                        val z = hi.toInt().coerceIn(a, nyqBins)
                        for (k in a..z) v = max(v, mag[k])
                    }
                    val db = 20 * log10(v + 1e-12)
                    data[f * bands + b] = ((db - minDb) * scale).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
