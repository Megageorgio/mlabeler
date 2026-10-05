package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Time stretching that keeps pitch (WSOLA), for slow playback. */
object Stretch {
    /** Returns [x] played at [speed] (0.25..1): longer by 1/speed, same pitch. */
    fun wsola(x: FloatArray, sampleRate: Int, speed: Double): FloatArray {
        if (speed >= 0.999 || x.size < 4096) return x
        val n = (sampleRate * 0.04).toInt().let { var p = 256; while (p < it) p *= 2; p } // ~40 ms frames
        val hs = n / 2
        val ha = hs * speed
        val tol = n / 4
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val outLen = (x.size / speed).toInt() + n
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)
        var prevEnd = 0 // where the natural continuation of the last frame starts in x
        var k = 0
        while (true) {
            val outPos = k * hs
            val target = (k * ha).toInt()
            if (target + n + tol >= x.size || outPos + n >= outLen) break
            var best = target
            if (k > 0) {
                // pick the offset whose frame best continues the previous one
                var bestScore = -Double.MAX_VALUE
                val lo = max(0, target - tol)
                val hi = min(x.size - n, target + tol)
                var d = lo
                while (d <= hi) {
                    var s = 0.0
                    var i = 0
                    while (i < hs) {
                        s += x[d + i] * x[prevEnd + i]
                        i += 4
                    }
                    if (s > bestScore) { bestScore = s; best = d }
                    d += 2
                }
            }
            for (i in 0 until n) {
                out[outPos + i] += x[best + i] * win[i]
                norm[outPos + i] += win[i]
            }
            prevEnd = min(x.size - n, best + hs)
            k++
        }
        val len = k * hs + hs
        val res = FloatArray(min(len, outLen))
        for (i in res.indices) res[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
        return res
    }
}
