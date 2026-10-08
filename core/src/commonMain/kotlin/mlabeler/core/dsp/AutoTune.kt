package mlabeler.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The well-known "autotune" effect: the pitch of a voice is pulled to the nearest note (with [amount] 1 at once,
 * which gives the robotic jumps; less keeps some of the singing). TD-PSOLA: the voice is cut into pieces one
 * period long at its pitch marks and laid out again at the new period, so the timbre stays and the length too.
 */
object AutoTune {
    /** [scale]: allowed pitch classes (0 = C … 11 = B); null = every semitone. */
    fun process(x: FloatArray, sampleRate: Int, amount: Double = 1.0, scale: Set<Int>? = null): FloatArray {
        if (x.isEmpty()) return x
        val hopS = 0.005
        val f0 = Pitch.yin(x, sampleRate, hopS).values
        val hop = sampleRate * hopS
        fun pitchAt(n: Int): Float = f0.getOrElse((n / hop).toInt()) { 0f }
        val unvoicedPeriod = (sampleRate * 0.01).roundToInt()

        // analysis marks: one per period, on the biggest sample of each period where the voice sounds
        val marks = ArrayList<Int>()
        var m = 0
        while (m < x.size) {
            marks += m
            val f = pitchAt(m)
            if (f <= 0f) { m += unvoicedPeriod; continue }
            val p = (sampleRate / f).roundToInt().coerceAtLeast(16)
            val c = m + p
            var best = c
            var bestV = -Float.MAX_VALUE
            for (i in max(m + p * 3 / 4, 0)..min(c + p / 4, x.size - 1)) if (x[i] > bestV) { bestV = x[i]; best = i }
            m = if (best <= m) c else best
        }

        // the note each voiced stretch is pulled to: the nearest allowed one, kept until the voice is clearly nearer
        // another (no flicker between two notes)
        var held = Double.NaN
        fun target(f: Float): Double {
            val midi = 69 + 12 * ln(f / 440.0) / ln(2.0)
            fun nearest(v: Double): Double {
                if (scale == null || scale.isEmpty()) return kotlin.math.round(v)
                var best = kotlin.math.round(v); var d = Double.MAX_VALUE
                for (k in (v - 6).toInt()..(v + 6).toInt()) if (((k % 12) + 12) % 12 in scale && abs(k - v) < d) { d = abs(k - v); best = k.toDouble() }
                return best
            }
            val n = nearest(midi)
            if (held.isNaN() || abs(midi - held) > 0.6) held = n
            val note = midi + (held - midi) * amount
            return 440.0 * 2.0.pow((note - 69) / 12)
        }

        val out = FloatArray(x.size)
        var t = marks.first().toDouble()
        var k = 0
        while (t < x.size) {
            val ti = t.toInt()
            while (k + 1 < marks.size && abs(marks[k + 1] - ti) <= abs(marks[k] - ti)) k++
            val mark = marks[k]
            val f = pitchAt(mark)
            val pa = if (f > 0f) (sampleRate / f).roundToInt().coerceAtLeast(16) else unvoicedPeriod
            if (f <= 0f) held = Double.NaN
            val ps = if (f > 0f) (sampleRate / target(f)).coerceAtLeast(16.0) else pa.toDouble()
            // a two-period piece around the analysis mark, Hann-windowed, centred on the synthesis time (the windows
            // keep their own shape: their spacing is what makes the new pitch; the level follows the spacing)
            val gain = (ps / pa).toFloat()
            for (j in -pa until pa) {
                val src = mark + j
                val dst = ti + j
                if (src !in x.indices || dst !in out.indices) continue
                val w = (0.5 - 0.5 * cos(PI * (j + pa) / pa)).toFloat()
                out[dst] += x[src] * w * gain
            }
            t += ps
        }
        return out
    }
}
