package mlabeler.core.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A part of the spectrogram painted with the brush: round dabs in time (seconds) and pitch (mel, as the
 * spectrogram is drawn), each with a soft edge like an airbrush. A dab that erases takes away from what is painted.
 * The value at a point is 0..1: how much of the sound there goes when the painted part is removed.
 */
class SpectralMask {
    /** [rt] and [rMel]: radius in seconds and in mel; [softness] 0 (hard edge) .. 1 (soft from the centre). */
    data class Dab(val t: Double, val mel: Double, val rt: Double, val rMel: Double, val softness: Float, val erase: Boolean)

    private val list = ArrayList<Dab>()
    val dabs: List<Dab> get() = list
    fun isEmpty() = list.none { !it.erase }
    fun add(d: Dab) { list.add(d) }
    fun clear() = list.clear()

    /** Time painted, start to end (seconds); null when nothing is. */
    fun span(): Pair<Double, Double>? {
        val add = list.filter { !it.erase }
        if (add.isEmpty()) return null
        return add.minOf { it.t - it.rt } to add.maxOf { it.t + it.rt }
    }

    /**
     * The mask on a grid: [nt] times from [t0] every [dt] seconds, and the mel values [mels] (ascending). Returns
     * [nt × mels.size] values 0..1, time-major.
     */
    fun raster(t0: Double, dt: Double, nt: Int, mels: DoubleArray): FloatArray {
        val nk = mels.size
        val g = FloatArray(nt * nk)
        if (nt <= 0 || nk == 0) return g
        for (d in list) {
            val i0 = max(0, kotlin.math.ceil((d.t - d.rt - t0) / dt).toInt())
            val i1 = min(nt - 1, kotlin.math.floor((d.t + d.rt - t0) / dt).toInt())
            if (i1 < i0) continue
            val k0 = lowerBound(mels, d.mel - d.rMel)
            val k1 = lowerBound(mels, d.mel + d.rMel + 1e-9) - 1
            if (k1 < k0) continue
            for (i in i0..i1) {
                val x = (t0 + i * dt - d.t) / d.rt
                val o = i * nk
                for (k in k0..k1) {
                    val y = (mels[k] - d.mel) / d.rMel
                    val w = weight(sqrt(x * x + y * y), d.softness)
                    if (w <= 0f) continue
                    g[o + k] = if (d.erase) g[o + k] * (1f - w) else max(g[o + k], w)
                }
            }
        }
        return g
    }

    companion object {
        /** 1 inside the hard core, falling smoothly to 0 at the edge ([d] = distance / radius). */
        fun weight(d: Double, softness: Float): Float {
            if (d >= 1.0) return 0f
            val core = 1.0 - softness.coerceIn(0f, 1f)
            if (d <= core) return 1f
            val x = (d - core) / (1.0 - core)
            return (1.0 - x * x * (3 - 2 * x)).toFloat()
        }

        private fun lowerBound(a: DoubleArray, v: Double): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (a[m] < v) lo = m + 1 else hi = m
            }
            return lo
        }
    }
}
