package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Simple, predictable clean-up: click detection and repair by interpolation (only the click samples change),
 * and spectral noise gating from a noise profile (changes the processed range, as much as the settings say).
 */
object Repair {
    /** Linear prediction coefficients (autocorrelation + Levinson-Durbin): x[n] ≈ Σ a[k]·x[n-1-k]. */
    fun lpc(x: FloatArray, order: Int, windowed: Boolean = true): DoubleArray {
        val n = x.size
        val p = min(order, n - 1).coerceAtLeast(1)
        // Hann-windowed autocorrelation keeps the estimate stable on short, tonal pieces
        val w = DoubleArray(n) { i -> if (windowed) x[i] * (0.5 - 0.5 * cos(2 * PI * (i + 0.5) / n)) else x[i].toDouble() }
        val r = DoubleArray(p + 1)
        for (lag in 0..p) {
            var s = 0.0
            for (i in lag until n) s += w[i] * w[i - lag]
            r[lag] = s
        }
        if (r[0] <= 1e-12) return DoubleArray(p)
        r[0] *= 1.0 + 1e-7 // tiny regularisation
        val a = DoubleArray(p + 1)
        var err = r[0]
        for (i in 1..p) {
            var acc = r[i]
            for (j in 1 until i) acc -= a[j] * r[i - j]
            val k = acc / err
            val prev = a.copyOf()
            a[i] = k
            for (j in 1 until i) a[j] = prev[j] - k * prev[i - j]
            err *= (1 - k * k)
            if (err <= 1e-12) break
        }
        return DoubleArray(p) { a[it + 1] }
    }

    /**
     * New values for x[from until to], predicted forward from the audio before the gap and backward from the audio
     * after it, cross-faded. Nothing outside the gap changes.
     */
    fun interpolate(x: FloatArray, from: Int, to: Int, order: Int = 32): FloatArray {
        val gap = to - from
        if (gap <= 0) return FloatArray(0)
        val ctx = max(2048, gap * 8).coerceAtMost(16384)
        val leftStart = max(0, from - ctx)
        val rightEnd = min(x.size, to + ctx)
        val left = x.copyOfRange(leftStart, from)
        val right = x.copyOfRange(to, rightEnd).reversedArray()
        val fwd = predict(left, gap, order)
        val bwd = predict(right, gap, order).reversedArray()
        return FloatArray(gap) { i ->
            val w = if (gap == 1) 0.5 else 0.5 - 0.5 * cos(PI * i / (gap - 1)) // 0 → 1
            when {
                left.size < 4 -> bwd[i]
                right.size < 4 -> fwd[i]
                else -> ((1 - w) * fwd[i] + w * bwd[i]).toFloat()
            }
        }
    }

    private fun predict(history: FloatArray, n: Int, order: Int): FloatArray {
        if (history.size < 4) return FloatArray(n)
        val a = lpc(history, min(order, history.size / 2))
        val p = a.size
        val buf = DoubleArray(p + n)
        for (k in 0 until p) buf[k] = history[history.size - p + k].toDouble()
        for (i in 0 until n) {
            var s = 0.0
            for (k in 0 until p) s += a[k] * buf[p + i - 1 - k]
            buf[p + i] = s
        }
        return FloatArray(n) { buf[p + it].toFloat() }
    }

    /**
     * Clicks: places where the signal suddenly departs from what linear prediction expects, much more than the
     * prediction error around them. [sensitivity] 1..10 (higher finds weaker clicks); longer events than
     * [maxWidthMs] are left alone (they are sound, not clicks).
     */
    fun findClicks(x: FloatArray, sampleRate: Int, sensitivity: Float, maxWidthMs: Float, from: Int = 0, to: Int = x.size): List<IntRange> {
        val order = 16
        val block = 2048
        val e = FloatArray(to - from)
        var b = from
        while (b < to) {
            val end = min(to, b + block)
            val fitFrom = max(0, b - block)
            val a = lpc(x.copyOfRange(fitFrom, min(x.size, end + block / 2)), order, windowed = false)
            for (n in b until end) {
                var s = 0.0
                for (k in a.indices) { val j = n - 1 - k; if (j >= 0) s += a[k] * x[j] }
                e[n - from] = abs(x[n] - s.toFloat())
            }
            b = end
        }
        // local level of the error: running mean over ~10 ms, robust against the clicks themselves via a median of blocks
        val win = max(64, sampleRate / 100)
        val level = FloatArray(e.size)
        var i = 0
        while (i < e.size) {
            val end = min(e.size, i + win)
            val part = e.copyOfRange(max(0, i - win), min(e.size, end + win)).sorted()
            val med = part[part.size / 2].coerceAtLeast(1e-6f)
            for (k in i until end) level[k] = med
            i = end
        }
        val factor = 40f / sensitivity.coerceIn(1f, 10f) + 4f // 8 (sensitive) .. 44 (strict)
        // voiced sound leaves a regular train of spikes in the prediction error (one per glottal pulse); a click is
        // a spike much higher than anything around it, so compare with the largest error nearby, its own
        // surroundings excluded
        val ratio = 1.5f + (10f - sensitivity.coerceIn(1f, 10f)) * 0.25f
        val blk = max(16, sampleRate / 1000)
        val blockMax = FloatArray((e.size + blk - 1) / blk) { bi -> var m = 0f; for (k in bi * blk until min(e.size, (bi + 1) * blk)) if (e[k] > m) m = e[k]; m }
        val guard = max(2, sampleRate / 400 / blk) // ~2.5 ms on each side belongs to the click
        val reach = max(guard + 2, sampleRate / 30 / blk) // ~33 ms around
        fun isolated(n: Int): Boolean {
            val bi = n / blk
            var around = 0f
            for (j in max(0, bi - reach)..min(blockMax.size - 1, bi + reach)) if (abs(j - bi) > guard && blockMax[j] > around) around = blockMax[j]
            return e[n] > around * ratio
        }
        val maxW = (maxWidthMs / 1000f * sampleRate).toInt().coerceAtLeast(2)
        val hits = mutableListOf<IntRange>()
        var start = -1
        var lastHit = -1
        val joinGap = max(4, sampleRate / 4000)
        for (n in e.indices) {
            // the very start of the file has nothing to predict from
            if (from + n < order * 2) continue
            if (e[n] > level[n] * factor && isolated(n)) {
                if (start < 0 || n - lastHit > joinGap) {
                    if (start >= 0) hits += start..lastHit
                    start = n
                }
                lastHit = n
            }
        }
        if (start >= 0) hits += start..lastHit
        val margin = max(2, sampleRate / 8000)
        return hits.map { r -> (from + r.first - margin).coerceAtLeast(0)..(from + r.last + margin).coerceAtMost(x.size - 1) }
            .filter { it.last - it.first + 1 <= maxW }
    }

    // ---------- noise reduction ----------

    private const val N = 2048
    private const val HOP = N / 4
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / N) }

    /** Average magnitude per frequency bin of a part with noise only. */
    fun noiseProfile(x: FloatArray): DoubleArray {
        val fft = Fft(N)
        val prof = DoubleArray(N / 2 + 1)
        var frames = 0
        var p = 0
        while (p + N <= x.size || frames == 0) {
            val re = DoubleArray(N) { i -> (if (p + i < x.size) x[p + i].toDouble() else 0.0) * window[i] }
            val im = DoubleArray(N)
            fft.transform(re, im)
            for (k in prof.indices) prof[k] += sqrt(re[k] * re[k] + im[k] * im[k])
            frames++
            p += HOP
            if (p + N > x.size) break
        }
        for (k in prof.indices) prof[k] /= frames
        return prof
    }

    /**
     * Spectral gate: bins quieter than the noise profile × sensitivity are lowered by [reductionDb]; the gain is
     * smoothed over [smoothingBands] neighbouring bins and over time, so nothing chirps. Returns the new samples.
     */
    fun reduceNoise(x: FloatArray, profile: DoubleArray, reductionDb: Float, sensitivityDb: Float, smoothingBands: Int): FloatArray {
        if (x.isEmpty()) return x
        val fft = Fft(N)
        val bins = N / 2 + 1
        val floor = exp(-reductionDb / 20.0 * ln(10.0))
        val thr = DoubleArray(bins) { profile[it] * exp(sensitivityDb / 20.0 * ln(10.0)) }
        val pad = N
        val len = x.size + 2 * pad
        val src = DoubleArray(len) { i -> val j = i - pad; if (j in x.indices) x[j].toDouble() else 0.0 }
        val out = DoubleArray(len)
        val norm = DoubleArray(len)
        var prevGain = DoubleArray(bins) { 1.0 }
        var p = 0
        while (p + N <= len) {
            val re = DoubleArray(N) { src[p + it] * window[it] }
            val im = DoubleArray(N)
            fft.transform(re, im)
            val g = DoubleArray(bins) { k -> if (sqrt(re[k] * re[k] + im[k] * im[k]) < thr[k]) floor else 1.0 }
            // smooth across frequency
            val sm = DoubleArray(bins)
            val r = smoothingBands.coerceAtLeast(0)
            for (k in 0 until bins) {
                var s = 0.0; var c = 0
                for (j in max(0, k - r)..min(bins - 1, k + r)) { s += g[j]; c++ }
                sm[k] = s / c
            }
            // smooth over time: fast opening, slower closing
            for (k in 0 until bins) sm[k] = if (sm[k] > prevGain[k]) sm[k] else prevGain[k] * 0.6 + sm[k] * 0.4
            prevGain = sm
            for (k in 0 until bins) {
                re[k] *= sm[k]; im[k] *= sm[k]
                if (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
            }
            // inverse via the forward transform of the conjugate
            for (k in 0 until N) im[k] = -im[k]
            fft.transform(re, im)
            for (i in 0 until N) {
                out[p + i] += re[i] / N * window[i]
                norm[p + i] += window[i] * window[i]
            }
            p += HOP
        }
        return FloatArray(x.size) { i -> val j = i + pad; if (norm[j] > 1e-9) (out[j] / norm[j]).toFloat() else x[i] }
    }
}
