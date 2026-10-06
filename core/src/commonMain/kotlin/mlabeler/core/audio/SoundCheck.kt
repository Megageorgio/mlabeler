package mlabeler.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Problems of a recording itself (not of its labels) that hurt training: clipping, level, noise, silence. */
object SoundCheck {
    enum class Kind { Clipping, Quiet, Noisy, DcOffset, SilenceAtStart, SilenceAtEnd }

    /** [at]: where to look, s; [value]: the measured number (dB, s or a count, depending on the kind). */
    data class Finding(val kind: Kind, val at: Double, val value: Double)

    data class Limits(
        /** A peak below this is a quiet recording, dBFS. */
        val quietPeakDb: Double = -18.0,
        /** The quietest tenth of the recording above this is noise, dBFS. */
        val noiseFloorDb: Double = -50.0,
        /** Silence at either end longer than this, s. */
        val edgeSilence: Double = 2.0,
        val dcOffset: Double = 0.01,
    )

    data class Report(val peakDb: Double, val noiseDb: Double, val findings: List<Finding>)

    private fun db(v: Double) = if (v <= 1e-10) -200.0 else 20 * log10(v)

    fun analyze(a: Audio, limits: Limits = Limits()): Report {
        val x = a.samples
        if (x.isEmpty()) return Report(-200.0, -200.0, emptyList())
        val out = mutableListOf<Finding>()
        // clipping: 3 or more samples in a row at full scale
        var peak = 0f
        var run = 0
        var runs = 0
        var firstClip = -1
        var sum = 0.0
        for ((i, v) in x.withIndex()) {
            val m = abs(v)
            if (m > peak) peak = m
            sum += v
            if (m >= 0.999f) {
                run++
                if (run == 3) { runs++; if (firstClip < 0) firstClip = i - 2 }
            } else run = 0
        }
        if (runs > 0) out += Finding(Kind.Clipping, firstClip.toDouble() / a.sampleRate, runs.toDouble())
        val peakDb = db(peak.toDouble())
        if (peakDb < limits.quietPeakDb) out += Finding(Kind.Quiet, 0.0, peakDb)
        val mean = sum / x.size
        if (abs(mean) > limits.dcOffset) out += Finding(Kind.DcOffset, 0.0, mean)
        // levels of 20 ms frames
        val frame = max(1, a.sampleRate / 50)
        val levels = DoubleArray((x.size + frame - 1) / frame) { f ->
            var e = 0.0
            val from = f * frame
            val to = minOf(x.size, from + frame)
            for (i in from until to) e += x[i].toDouble() * x[i]
            db(sqrt(e / (to - from)))
        }
        val sorted = levels.sorted()
        val noiseDb = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
        val loud = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.size - 1)]
        // noise is measured in the pauses: a take without pauses (its quietest part about as loud as the rest) says nothing
        if (noiseDb > limits.noiseFloorDb && loud - noiseDb > 12) out += Finding(Kind.Noisy, 0.0, noiseDb)
        // silence at the ends: frames well below the loud part
        val quiet = max(noiseDb + 10, loud - 40)
        val lead = levels.indexOfFirst { it > quiet }.let { if (it < 0) levels.size else it }
        val tail = levels.size - 1 - levels.indexOfLast { it > quiet }.let { if (it < 0) -1 else it }
        val fs = frame.toDouble() / a.sampleRate
        if (lead < levels.size && lead * fs > limits.edgeSilence) out += Finding(Kind.SilenceAtStart, 0.0, lead * fs)
        if (lead < levels.size && tail * fs > limits.edgeSilence) out += Finding(Kind.SilenceAtEnd, a.duration - tail * fs, tail * fs)
        return Report(peakDb, noiseDb, out)
    }
}
