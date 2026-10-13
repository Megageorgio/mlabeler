package mlabeler.core.mcfl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mlabeler.core.ds.Dataset
import mlabeler.core.audio.Audio
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * MCFL (Mel Convolutions For Labeling, github.com/Megageorgio/MCFL): phonemes of singing without lyrics, in plain
 * Kotlin, so it runs wherever mLabeler runs (old Windows, phones) without native libraries.
 *
 * The network: audio 16 kHz -> STFT -> 80 log-mel -> stride-2 convolution (20 ms frames) + GELU -> + language
 * embedding -> blocks of a depthwise dilated convolution and two pointwise ones with a residual -> B-/I- tag
 * log-probabilities and the start/end offsets inside a frame. The model is the "mcfl-v1" release format of MCFL
 * (model.json + model.safetensors, fp16 weights with the batch norms folded in); the decoding is that of the
 * repository's mcfl_infer.py.
 */
class Mcfl(config: String, weights: ByteArray) {
    /** [confidence]: the mean probability of the label over its frames (0..1), when asked for. */
    data class Segment(val start: Double, val end: Double, val label: String, val confidence: Float? = null)

    val name: String
    /** Language codes, in the order of their ids. */
    val languages: List<String>
    val labels: List<String>
    private val langPhonemes: Map<String, Set<String>>
    /** Shared (merged) phoneme name -> the language's own name, per language. */
    private val merge: Map<String, Map<String, String>>
    private val shared: Set<String>
    private val decode: JsonObject

    private val sr: Int
    private val nFft: Int
    private val hop: Int
    private val nMels: Int
    private val ch: Int
    private val kernel: Int
    private val stemK: Int
    private val hidden: Int
    private val dilations: IntArray

    private val melFb: FloatArray      // [bins][mels]
    private val melMean: FloatArray
    private val melStd: FloatArray
    private val stemW: FloatArray      // [k][mels][ch]
    private val stemB: FloatArray
    private val lang: FloatArray       // [langs][ch]
    private val dwW: Array<FloatArray> // [k][ch]
    private val dwB: Array<FloatArray>
    private val pw1W: Array<FloatArray> // [ch][hidden]
    private val pw1B: Array<FloatArray>
    private val pw2W: Array<FloatArray> // [hidden][ch]
    private val pw2B: Array<FloatArray>
    private val clsW: FloatArray       // [ch][labels]
    private val clsB: FloatArray
    private val offW: FloatArray       // [ch][2]
    private val offB: FloatArray

    private val cosT: FloatArray       // [bins][nFft], the periodic Hann window included
    private val sinT: FloatArray

    init {
        val h = Json.parseToJsonElement(config).jsonObject
        if (h["format"]?.jsonPrimitive?.content != "mcfl-v1") throw IllegalArgumentException("Not an MCFL model (format mcfl-v1)")
        // safetensors: u64 length of the JSON header, the header, the data
        val n = le32(weights, 0)
        val tensors = Json.parseToJsonElement(weights.decodeToString(8, 8 + n)).jsonObject
        val base = 8 + n
        fun t(key: String): FloatArray {
            val d = tensors[key]?.jsonObject ?: throw IllegalArgumentException("The model has no $key")
            val (from, to) = d["data_offsets"]!!.jsonArray.map { it.jsonPrimitive.int }
            return when (val dt = d["dtype"]!!.jsonPrimitive.content) {
                "F16" -> FloatArray((to - from) / 2) { i -> halfToFloat(le16(weights, base + from + 2 * i)) }
                "F32" -> FloatArray((to - from) / 4) { i -> Float.fromBits(le32(weights, base + from + 4 * i)) }
                else -> throw IllegalArgumentException("$key: $dt is not supported")
            }
        }
        fun ints(key: String) = h[key]!!.jsonPrimitive.int
        name = h["name"]?.jsonPrimitive?.content ?: "MCFL"
        val ids = h["langs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        languages = ids.entries.sortedBy { it.value }.map { it.key }
        labels = h["labels"]!!.jsonArray.map { it.jsonPrimitive.content }
        langPhonemes = h["lang_phonemes"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        merge = (h["phoneme_merge_map"] as? JsonObject)?.mapValues { (_, v) -> v.jsonObject.mapValues { it.value.jsonPrimitive.content } }.orEmpty()
        shared = (h["shared_labels"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: setOf("AP", "SP", "cl", "vf", "exh")
        decode = JsonObject((h["decode"]?.jsonObject ?: JsonObject(emptyMap())) + listOfNotNull(h["frame_sec"]?.let { "frame_sec" to it }))
        sr = ints("sample_rate"); nFft = ints("n_fft"); hop = ints("hop"); nMels = ints("n_mels")
        ch = ints("channels"); kernel = ints("kernel")
        hidden = ch * ints("expand")
        dilations = h["dilations"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()

        melFb = t("mel_fb")
        melMean = t("mel_mean")
        melStd = t("mel_std")
        val sw = t("stem.w")
        stemK = sw.size / (ch * nMels)
        // [ch][mels][k] -> [k][mels][ch]
        stemW = FloatArray(sw.size).also { o -> for (c in 0 until ch) for (m in 0 until nMels) for (k in 0 until stemK) o[(k * nMels + m) * ch + c] = sw[(c * nMels + m) * stemK + k] }
        stemB = t("stem.b")
        lang = t("lang")
        val nb = dilations.size
        dwW = Array(nb) { i -> transpose(t("blocks.$i.dw.w"), ch, kernel) }
        dwB = Array(nb) { i -> t("blocks.$i.dw.b") }
        pw1W = Array(nb) { i -> transpose(t("blocks.$i.pw1.w"), hidden, ch) }
        pw1B = Array(nb) { i -> t("blocks.$i.pw1.b") }
        pw2W = Array(nb) { i -> transpose(t("blocks.$i.pw2.w"), ch, hidden) }
        pw2B = Array(nb) { i -> t("blocks.$i.pw2.b") }
        clsW = transpose(t("cls.w"), labels.size, ch)
        clsB = t("cls.b")
        offW = transpose(t("off.w"), 2, ch)
        offB = t("off.b")

        val bins = nFft / 2 + 1
        cosT = FloatArray(bins * nFft)
        sinT = FloatArray(bins * nFft)
        for (f in 0 until bins) for (k in 0 until nFft) {
            val win = 0.5 - 0.5 * cos(2 * PI * k / nFft)
            val a = 2 * PI * f * k / nFft
            cosT[f * nFft + k] = (cos(a) * win).toFloat()
            sinT[f * nFft + k] = (-sin(a) * win).toFloat()
        }
    }

    /** The phonemes the model may give in [language] (its own names). */
    fun phonemes(language: String): Set<String> = langPhonemes[language].orEmpty()

    /**
     * Labels [audio] in [language]: continuous segments covering all of it, in seconds from its start. Long audio
     * goes in overlapping windows. [native]: shared phoneme names become the language's own. [progress] gets 0..1.
     */
    suspend fun label(audio: Audio, language: String, native: Boolean = true, threads: Int = 1, confidence: Boolean = false,
                      progress: (Double) -> Unit = {}): List<Segment> {
        val lid = languages.indexOf(language)
        if (lid < 0) throw IllegalArgumentException("$name has no language $language: ${languages.joinToString(" ")}")
        val a = (if (audio.sampleRate == sr) audio.samples.copyOf() else Dataset.resample(audio, sr).samples)
        var peak = 0f
        for (v in a) peak = max(peak, abs(v))
        for (i in a.indices) a[i] = a[i] / (peak + 1e-8f)
        val (lp, off, frames) = frames(a, lid, threads, progress)
        var segs = Decoder(lid).decode(lp, off, frames, a)
        if (confidence) segs = withConfidence(segs, lp, frames)
        return if (!native) segs else segs.map { s -> merge[s.label]?.get(language)?.let { s.copy(label = it) } ?: s }
    }

    /** Log-probabilities and offsets of [audio] at 16 kHz (for tests). */
    internal suspend fun debugFrames(audio: Audio, language: String): Pair<FloatArray, FloatArray> {
        val a = audio.samples.copyOf()
        var peak = 0f
        for (v in a) peak = max(peak, abs(v))
        for (i in a.indices) a[i] = a[i] / (peak + 1e-8f)
        val (lp, off, _) = frames(a, languages.indexOf(language), 1) {}
        return lp to off
    }

    /** Each segment with the mean probability (B- + I- of its label) over its frames. */
    private fun withConfidence(segs: List<Segment>, lp: FloatArray, frames: Int): List<Segment> {
        val nl = labels.size
        val index = labels.withIndex().associate { (i, l) -> l to i }
        val fd = fd()
        return segs.map { s ->
            val cols = listOfNotNull(index["B-${s.label}"], index["I-${s.label}"])
            if (cols.isEmpty() || frames == 0) return@map s
            val f0 = (s.start / fd).toInt().coerceIn(0, frames - 1)
            val f1 = kotlin.math.round(s.end / fd).toInt().coerceIn(f0 + 1, frames)
            var sum = 0.0
            for (f in f0 until f1) for (c in cols) sum += exp(lp[f * nl + c].toDouble())
            s.copy(confidence = (sum / (f1 - f0)).toFloat())
        }
    }

    private fun fd() = decode["frame_sec"]?.jsonPrimitive?.double ?: 0.02

    /** Log-probabilities [frames][labels] and offsets [frames][2] of the whole audio. */
    private suspend fun frames(a: FloatArray, lid: Int, threads: Int, progress: (Double) -> Unit): Triple<FloatArray, FloatArray, Int> {
        val fd = fd()
        val nTotal = kotlin.math.ceil(a.size.toDouble() / sr / fd).toInt()
        val win = ((decode["window_sec"]?.jsonPrimitive?.double ?: 120.0) * sr).toInt()
        val ov = ((decode["overlap_sec"]?.jsonPrimitive?.double ?: 16.0) * sr).toInt()
        val nl = labels.size
        val lp = FloatArray(nTotal * nl)
        val off = FloatArray(nTotal * 2)
        if (a.size <= win) {
            val (l, o, t) = run(a, lid, threads) { p -> progress(p) }
            val n = min(t, nTotal)
            l.copyInto(lp, 0, 0, n * nl); o.copyInto(off, 0, 0, n * 2)
            return Triple(lp, off, nTotal)
        }
        val hopW = win - ov
        val fpf = (fd * sr).toInt()
        var done = 0
        var start = 0
        while (true) {
            coroutineContext.ensureActive()
            val end = min(start + win, a.size)
            val s0 = start
            val (l, o, t) = run(a.copyOfRange(start, end), lid, threads) { p -> progress((s0 + p * (end - s0)) / a.size) }
            val f0 = start / fpf
            val last = end >= a.size
            val keepTo = min(t, if (last) t else (start + win - ov / 2) / fpf - f0)
            val from = done - f0
            val n = min(keepTo - from, nTotal - done)
            if (n > 0) {
                l.copyInto(lp, done * nl, from * nl, (from + n) * nl)
                o.copyInto(off, done * 2, from * 2, (from + n) * 2)
                done += n
            }
            if (last || done >= nTotal) break
            start += hopW
        }
        return Triple(lp, off, nTotal)
    }

    /** One pass of the network: log-probabilities [T][labels], offsets [T][2], T. */
    private suspend fun run(audio: FloatArray, lid: Int, threads: Int, progress: (Double) -> Unit): Triple<FloatArray, FloatArray, Int> {
        val mel = melFrames(audio, threads)
        val nf = mel.size / nMels
        val pad = stemK / 2
        val t = (nf + 2 * pad - stemK) / 2 + 1
        var y = FloatArray(t * ch)
        parallel(t, threads) { t0, t1 ->
            for (i in t0 until t1) {
                val o = i * ch
                for (c in 0 until ch) y[o + c] = stemB[c]
                for (k in 0 until stemK) {
                    val f = 2 * i + k - pad
                    if (f < 0 || f >= nf) continue
                    for (m in 0 until nMels) {
                        val x = mel[f * nMels + m]
                        val w = (k * nMels + m) * ch
                        for (c in 0 until ch) y[o + c] += x * stemW[w + c]
                    }
                }
                for (c in 0 until ch) y[o + c] = gelu(y[o + c]) + lang[lid * ch + c]
            }
        }
        progress(0.1)
        var next = FloatArray(t * ch)
        val nb = dilations.size
        for (b in 0 until nb) {
            coroutineContext.ensureActive()
            val d = dilations[b]
            val p = d * (kernel - 1) / 2
            val dw = dwW[b]; val dwb = dwB[b]; val w1 = pw1W[b]; val b1 = pw1B[b]; val w2 = pw2W[b]; val b2 = pw2B[b]
            val cur = y; val out = next
            parallel(t, threads) { t0, t1 ->
                val z = FloatArray(ch)
                val hdn = FloatArray(hidden)
                for (i in t0 until t1) {
                    // depthwise dilated convolution
                    for (c in 0 until ch) z[c] = dwb[c]
                    for (k in 0 until kernel) {
                        val s = i + k * d - p
                        if (s < 0 || s >= t) continue
                        val so = s * ch; val wo = k * ch
                        for (c in 0 until ch) z[c] += cur[so + c] * dw[wo + c]
                    }
                    // pointwise, GELU, pointwise, residual
                    b1.copyInto(hdn)
                    for (c in 0 until ch) {
                        val x = z[c]
                        val wo = c * hidden
                        for (o in 0 until hidden) hdn[o] += x * w1[wo + o]
                    }
                    for (o in 0 until hidden) hdn[o] = gelu(hdn[o])
                    val oo = i * ch
                    for (c in 0 until ch) out[oo + c] = cur[oo + c] + b2[c]
                    for (j in 0 until hidden) {
                        val x = hdn[j]
                        val wo = j * ch
                        for (c in 0 until ch) out[oo + c] += x * w2[wo + c]
                    }
                }
            }
            y = out; next = cur
            progress(0.1 + 0.85 * (b + 1) / nb)
        }
        val nl = labels.size
        val lp = FloatArray(t * nl)
        val off = FloatArray(t * 2)
        val yy = y
        parallel(t, threads) { t0, t1 ->
            for (i in t0 until t1) {
                val o = i * nl
                for (l in 0 until nl) lp[o + l] = clsB[l]
                var o0 = offB[0]; var o1 = offB[1]
                for (c in 0 until ch) {
                    val x = yy[i * ch + c]
                    val w = c * nl
                    for (l in 0 until nl) lp[o + l] += x * clsW[w + l]
                    o0 += x * offW[c * 2]; o1 += x * offW[c * 2 + 1]
                }
                var m = Float.NEGATIVE_INFINITY
                for (l in 0 until nl) m = max(m, lp[o + l])
                var s = 0.0
                for (l in 0 until nl) s += exp((lp[o + l] - m).toDouble())
                val lse = m + ln(s).toFloat()
                for (l in 0 until nl) lp[o + l] -= lse
                off[i * 2] = sigmoid(o0); off[i * 2 + 1] = sigmoid(o1)
            }
        }
        progress(1.0)
        return Triple(lp, off, t)
    }

    /** Normalised log-mel frames [frames][mels], 10 ms apart (the audio padded by reflection, as torch does). */
    private suspend fun melFrames(a: FloatArray, threads: Int): FloatArray {
        val half = nFft / 2
        val n = a.size
        fun at(i: Int): Float {
            if (n == 1) return a[0]
            var j = i - half
            while (j < 0 || j >= n) { if (j < 0) j = -j; if (j >= n) j = 2 * (n - 1) - j }
            return a[j]
        }
        val nf = n / hop + 1
        val bins = nFft / 2 + 1
        val out = FloatArray(nf * nMels)
        parallel(nf, threads) { f0, f1 ->
            val fr = FloatArray(nFft)
            val pw = FloatArray(bins)
            for (f in f0 until f1) {
                for (k in 0 until nFft) fr[k] = at(f * hop + k)
                for (b in 0 until bins) {
                    var re = 0f; var im = 0f
                    val o = b * nFft
                    for (k in 0 until nFft) { re += fr[k] * cosT[o + k]; im += fr[k] * sinT[o + k] }
                    pw[b] = re * re + im * im
                }
                for (m in 0 until nMels) {
                    var s = 0f
                    for (b in 0 until bins) s += pw[b] * melFb[b * nMels + m]
                    out[f * nMels + m] = (ln(max(s, 1e-5f)) - melMean[m]) / melStd[m]
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ decoding (mcfl_infer.py)

    private inner class Decoder(lid: Int) {
        private val language = languages[lid]
        private val idx: IntArray
        private val phones: List<String>
        private val bIdx: IntArray
        private val iIdx: IntArray

        init {
            val keep = phonemes(language) + shared
            idx = labels.indices.filter { labels[it] == "O" || labels[it].drop(2) in keep }.toIntArray()
            val sub = idx.map { labels[it] }
            phones = sub.filter { it.startsWith("B-") && "I-${it.drop(2)}" in sub }.map { it.drop(2) }
            bIdx = phones.map { idx[sub.indexOf("B-$it")] }.toIntArray()
            iIdx = phones.map { idx[sub.indexOf("I-$it")] }.toIntArray()
        }

        fun decode(lp: FloatArray, off: FloatArray, t: Int, audio: FloatArray): List<Segment> {
            val nl = labels.size
            val fd = fd()
            val (isB, ph) = viterbi(lp, nl, t, decode["viterbi_bias"]?.jsonPrimitive?.double ?: 5.0)
            val segs = ArrayList<Segment>()
            var start = 0
            for (i in 1..t) {
                if (i == t || isB[i] || ph[i] != ph[i - 1]) {
                    val s = (start + off[start * 2]) * fd
                    val e = (i - 1 + off[(i - 1) * 2 + 1]) * fd
                    segs.add(Segment(s, e, phones[ph[start]]))
                    start = i
                }
            }
            val silence = decode["silence_label"]?.jsonPrimitive?.content ?: "SP"
            val hard = hardSilence(mergeAdjacent(segs), audio, decode["silence_threshold"]?.jsonPrimitive?.double ?: 0.005,
                decode["min_silence_sec"]?.jsonPrimitive?.double ?: 0.5, silence)
            return continuous(mergeAdjacent(hard), audio.size.toDouble() / sr, silence)
        }

        /** The best B/I tag path: B-x may follow anything, I-x only B-x or I-x (bonus log(bias)). */
        private fun viterbi(lp: FloatArray, nl: Int, t: Int, bias: Double): Pair<BooleanArray, IntArray> {
            val n = phones.size
            if (t == 0 || n == 0) return BooleanArray(t) to IntArray(t)
            val lb = DoubleArray(n); val li = DoubleArray(n)
            fun row(f: Int) {
                // log-softmax over the columns of the language's B- and I- tags
                var m = Double.NEGATIVE_INFINITY
                for (j in 0 until n) { m = max(m, lp[f * nl + bIdx[j]].toDouble()); m = max(m, lp[f * nl + iIdx[j]].toDouble()) }
                var s = 0.0
                for (j in 0 until n) s += exp(lp[f * nl + bIdx[j]] - m) + exp(lp[f * nl + iIdx[j]] - m)
                val lse = m + ln(s)
                for (j in 0 until n) { lb[j] = lp[f * nl + bIdx[j]] - lse; li[j] = lp[f * nl + iIdx[j]] - lse }
            }
            val logb = ln(bias)
            row(0)
            var sb = DoubleArray(n) { -ln(n.toDouble()) + lb[it] }
            var si = DoubleArray(n) { Double.NEGATIVE_INFINITY }
            val backAny = IntArray(t)
            val fromB = Array(t) { BooleanArray(0) }
            for (f in 1 until t) {
                row(f)
                val jb = argmax(sb); val ji = argmax(si)
                val m: Double
                if (sb[jb] >= si[ji]) { m = sb[jb]; backAny[f] = jb } else { m = si[ji]; backAny[f] = n + ji }
                val fb = BooleanArray(n) { sb[it] > si[it] }
                fromB[f] = fb
                val nsi = DoubleArray(n) { (if (fb[it]) sb[it] else si[it]) + logb + li[it] }
                val nsb = DoubleArray(n) { m + lb[it] }
                si = nsi; sb = nsb
            }
            val jb = argmax(sb); val ji = argmax(si)
            var state = if (sb[jb] >= si[ji]) jb else n + ji
            val path = IntArray(t)
            for (f in t - 1 downTo 0) {
                path[f] = state
                if (f == 0) break
                if (state < n) state = backAny[f]
                else if (fromB[f][state - n]) state -= n
            }
            return BooleanArray(t) { path[it] < n } to IntArray(t) { path[it] % n }
        }

        private fun argmax(a: DoubleArray): Int { var b = 0; for (i in 1 until a.size) if (a[i] > a[b]) b = i; return b }
    }

    private fun mergeAdjacent(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segs) if (out.isNotEmpty() && out.last().label == s.label) out[out.size - 1] = out.last().copy(end = s.end) else out.add(s)
        return out
    }

    /** Long quiet stretches become pauses (as WFL-ASR does without lyrics). */
    private fun hardSilence(segs: List<Segment>, audio: FloatArray, threshold: Double, minDur: Double, label: String): List<Segment> {
        val fl = sr / 100
        val nfl = (audio.size + fl - 1) / fl
        val iv = ArrayList<Pair<Double, Double>>()
        var start = -1
        for (i in 0..nfl) {
            val silent = i < nfl && run {
                var m = 0f
                for (k in i * fl until min(audio.size, (i + 1) * fl)) m = max(m, abs(audio[k]))
                m < threshold
            }
            if (silent && start < 0) start = i
            else if (!silent && start >= 0) {
                if ((i - start) * 0.01 >= minDur) iv.add(start * 0.01 to i * 0.01)
                start = -1
            }
        }
        var out = segs
        for ((s0, e0) in iv) {
            val nxt = ArrayList<Segment>()
            for (s in out) {
                if (s.end <= s0 || s.start >= e0) { nxt.add(s); continue }
                if (s.start < s0) nxt.add(s.copy(end = s0))
                if (s.end > e0) nxt.add(s.copy(start = e0))
            }
            nxt.add(Segment(s0, e0, label))
            out = nxt
        }
        return out.sortedBy { it.start }
    }

    private fun continuous(segs: List<Segment>, duration: Double, silence: String): List<Segment> {
        val starts = ArrayList<Pair<Double, String>>()
        for (s in segs.sortedBy { it.start }) {
            val st = max(0.0, s.start)
            if (st >= duration || (starts.isNotEmpty() && st <= starts.last().first)) continue
            starts.add(st to s.label)
        }
        if (starts.isEmpty()) return listOf(Segment(0.0, duration, silence))
        starts[0] = 0.0 to starts[0].second
        return starts.mapIndexed { i, (s, p) -> Segment(s, if (i + 1 < starts.size) starts[i + 1].first else duration, p) }
    }

    companion object {
        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

        private fun halfToFloat(h: Int): Float {
            val s = (h ushr 15) and 1
            val e = (h ushr 10) and 0x1F
            val m = h and 0x3FF
            val v = when (e) {
                0 -> m / 1024f * 6.1035156e-5f
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Float.fromBits(((e - 15 + 127) shl 23) or (m shl 13))
            }
            return if (s == 1) -v else v
        }

        /** [rows][cols] -> [cols][rows]. */
        private fun transpose(w: FloatArray, rows: Int, cols: Int) = FloatArray(w.size).also { o -> for (r in 0 until rows) for (c in 0 until cols) o[c * rows + r] = w[r * cols + c] }

        private fun sigmoid(x: Float) = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

        /** Exact GELU, x·Φ(x), with erf of Abramowitz and Stegun 7.1.26 (error below 1.5e-7). */
        internal fun gelu(x: Float): Float {
            val z = x / 1.4142135f
            val az = abs(z)
            val tt = 1f / (1f + 0.3275911f * az)
            val y = 1f - (((((1.0614054f * tt - 1.4531521f) * tt) + 1.4214138f) * tt - 0.28449672f) * tt + 0.2548296f) * tt * exp(-az * az)
            val erf = if (z >= 0) y else -y
            return 0.5f * x * (1f + erf)
        }

        /** Runs [body] over 0 until [n] in up to [threads] pieces at once. */
        private suspend fun parallel(n: Int, threads: Int, body: (Int, Int) -> Unit) {
            val k = min(threads, max(1, n / 16))
            if (k <= 1) { body(0, n); return }
            coroutineScope {
                (0 until k).map { i -> async(Dispatchers.Default) { body(n * i / k, n * (i + 1) / k) } }.awaitAll()
            }
        }
    }
}
