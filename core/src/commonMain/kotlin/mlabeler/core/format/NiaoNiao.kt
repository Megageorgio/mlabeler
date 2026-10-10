package mlabeler.core.format

import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * NiaoNiao (袅袅虚拟歌手) voicebanks.
 *
 * Source: a folder of `<sound>.wav` (44.1 kHz, 16-bit, mono) each with `<sound>.inf`: one line
 * `start end consonant decay pitch consonantLevel level` — the sound starts and ends at `start` and `end`, its
 * consonant ends at `consonant` and it fades from `decay` (samples of the wav); `pitch` is in Hz; the levels are the
 * mean absolute sample values of start..consonant and of start..end.
 *
 * Built bank: `voice.d`, the samples start..end of every sound one after another, and `inf.d`, lines in base64:
 * `v1`, `<count> 0 0 0 0 0 0 0 0 0`, then per sound (by name) `name offset length consonant decay pitch
 * consonantLevel level` with offset and length in bytes of voice.d and the marks counted from the sound's start.
 */
object NiaoNiao {
    const val SAMPLE_RATE = 44100
    const val TIER = "niaoniao"
    const val CONSONANT = "consonant"
    const val VOWEL = "vowel"
    const val DECAY = "decay"

    data class Inf(
        val start: Int, val end: Int, val consonant: Int, val decay: Int,
        val pitch: Double = 0.0, val consonantLevel: Int = 0, val level: Int = 0,
    ) {
        fun write(): String = "$start $end $consonant $decay ${formatPitch(pitch)} $consonantLevel $level"
    }

    fun formatPitch(hz: Double): String = formatNumber(hz, 1).let { if ('.' in it) it else "$it.0" }

    /** True for the one line of seven numbers of an .inf. */
    fun looksLike(text: String): Boolean {
        val p = text.trim().split(Regex("\\s+"))
        return p.size == 7 && p.all { it.toDoubleOrNull() != null }
    }

    fun read(text: String): Inf {
        val p = text.trim().split(Regex("\\s+"))
        require(p.size >= 4) { "Not a NiaoNiao .inf" }
        fun i(k: Int) = p.getOrNull(k)?.toDoubleOrNull()?.roundToInt() ?: 0
        return Inf(i(0), i(1), i(2), i(3), p.getOrNull(4)?.toDoubleOrNull() ?: 0.0, i(5), i(6))
    }

    /** The marks as a tier: consonant, vowel and decay between the start and the end. */
    fun toDoc(inf: Inf, sampleRate: Int, duration: Double?): LabelDoc {
        fun t(s: Int) = (s.toDouble() / sampleRate).coerceIn(0.0, duration ?: Double.MAX_VALUE)
        val a = t(inf.start)
        val e = t(inf.end).coerceAtLeast(a)
        val c = t(inf.consonant).coerceIn(a, e)
        val d = t(inf.decay).coerceIn(c, e)
        val items = listOf(Triple(a, c, CONSONANT), Triple(c, d, VOWEL), Triple(d, e, DECAY)).filter { it.second > it.first }
        return LabelDoc(listOf(IntervalTier.fromIntervals(TIER, items, duration)))
    }

    /**
     * The marks of [doc] (its NiaoNiao tier, else its phoneme tier): the start of the first part to the end of the
     * last; the consonant ends where the vowel starts and the decay starts at the part named so. [old] gives the
     * pitch and levels, which only [measure] works out.
     */
    fun fromDoc(doc: LabelDoc, sampleRate: Int, old: Inf?): Inf? {
        val tier = (doc.tiers.firstOrNull { it is IntervalTier && it.name == TIER } ?: doc.tiers.getOrNull(doc.phonemeTierIndex())) as? IntervalTier
            ?: return null
        val parts = (0 until tier.size).filter { tier.texts[it].isNotBlank() }
        if (parts.isEmpty()) return null
        fun s(t: Double) = (t * sampleRate).roundToInt()
        val start = tier.startOf(parts.first())
        val end = tier.endOf(parts.last())
        // parts named so, else the second and the third part
        val c = (parts.firstOrNull { tier.texts[it] == VOWEL } ?: parts.getOrNull(1))?.let { tier.startOf(it) } ?: start
        val d = (parts.firstOrNull { tier.texts[it] == DECAY } ?: parts.getOrNull(2))?.let { tier.startOf(it) } ?: end
        return Inf(s(start), s(end), s(c), s(maxOf(c, d)), old?.pitch ?: 0.0, old?.consonantLevel ?: 0, old?.level ?: 0)
    }

    /** 16-bit values of [samples] (-1..1), as WAV decoding gives them back exactly. */
    fun toInt16(samples: FloatArray, from: Int = 0, to: Int = samples.size): ShortArray =
        ShortArray((to - from).coerceAtLeast(0)) { k -> (samples[from + k] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort() }

    /**
     * The levels and the pitch of [inf] measured on [samples]: mean absolute 16-bit values of start..consonant and
     * start..end, and the pitch of the vowel as the NiaoNiao tool gives it (a whole number of samples per period).
     */
    fun measure(inf: Inf, samples: FloatArray, sampleRate: Int, f0: mlabeler.core.dsp.Curve? = null): Inf {
        val s = inf.start.coerceIn(0, samples.size)
        val e = inf.end.coerceIn(s, samples.size)
        val c = inf.consonant.coerceIn(s, e)
        fun mean(a: Int, b: Int): Int {
            if (b <= a) return 0
            var sum = 0.0
            for (i in a until b) sum += abs((samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767))
            return (sum / (b - a)).toInt()
        }
        val curve = f0 ?: mlabeler.core.dsp.Pitch.yin(samples, sampleRate)
        val d = inf.decay.coerceIn(c, e)
        val vals = (((c.toDouble() / sampleRate) / curve.hop).toInt() until ((d.toDouble() / sampleRate) / curve.hop).toInt())
            .mapNotNull { curve.values.getOrNull(it)?.takeIf { v -> v > 0f && !v.isNaN() } }.sorted()
        val pitch = if (vals.isEmpty()) inf.pitch else {
            val hz = vals[vals.size / 2].toDouble()
            val period = (SAMPLE_RATE / hz).roundToInt().coerceAtLeast(1)
            (SAMPLE_RATE.toDouble() / period * 10).roundToInt() / 10.0
        }
        return inf.copy(pitch = pitch, consonantLevel = mean(s, c), level = mean(s, e))
    }

    /**
     * Marks found from the loudness: the sound is where it is louder than [thresholdDb] below its loudest part; the
     * consonant ends where the loudness first reaches half of the loudest, the decay starts where it last does.
     */
    fun auto(samples: FloatArray, sampleRate: Int, thresholdDb: Double = -30.0): Inf? {
        val hop = (sampleRate / 200).coerceAtLeast(1)
        val n = samples.size / hop
        if (n < 3) return null
        val level = DoubleArray(n) { k ->
            var sum = 0.0
            for (i in k * hop until minOf(samples.size, (k + 2) * hop)) sum += samples[i].toDouble() * samples[i]
            kotlin.math.sqrt(sum / (2 * hop))
        }
        val top = level.maxOrNull() ?: return null
        if (top <= 1e-6) return null
        val floor = top * kotlin.math.exp(thresholdDb / 20 * kotlin.math.ln(10.0))
        val first = level.indexOfFirst { it >= floor }
        val last = level.indexOfLast { it >= floor }
        if (first < 0 || last <= first) return null
        val half = top * 0.5
        val rise = (first..last).firstOrNull { level[it] >= half } ?: first
        val fall = (first..last).lastOrNull { level[it] >= half } ?: last
        val start = first * hop
        val end = minOf(samples.size, (last + 2) * hop)
        val consonant = (rise * hop).coerceIn(start, end)
        val decay = (fall * hop).coerceIn(consonant, end)
        return Inf(start, end, consonant, decay)
    }

    /**
     * The marks of an UTAU CV entry [e] of a recording: the start at the offset, the consonant ends at the end of the
     * fixed part (else at the preutterance), the end at the cutoff. oto.ini has no decay, so it is where the
     * loudness falls off, else the last fifth of the vowel.
     */
    fun fromOto(e: OtoEntry, samples: FloatArray, sampleRate: Int): Inf {
        val lengthMs = samples.size * 1000.0 / sampleRate
        fun s(ms: Double) = (ms * sampleRate / 1000).roundToInt().coerceIn(0, samples.size)
        val start = s(e.offset)
        val end = s(e.endMs(lengthMs)).coerceAtLeast(start)
        val fixed = if (e.consonant > 0) e.consonant else e.preutterance
        val consonant = s(e.offset + fixed).coerceIn(start, end)
        val fall = if (end - start > 3) auto(samples.copyOfRange(start, end), sampleRate)?.decay?.plus(start) else null
        val decay = (fall?.takeIf { it > consonant } ?: (end - (end - consonant) / 5)).coerceIn(consonant, end)
        return Inf(start, end, consonant, decay)
    }

    /** A CV entry of oto.ini for [inf]: the offset at the start, the preutterance and the fixed part at the vowel. */
    fun toOto(sample: String, alias: String, inf: Inf, sampleRate: Int): OtoEntry {
        fun ms(x: Int) = (x * 1000.0 / sampleRate * 10).roundToInt() / 10.0
        val offset = ms(inf.start)
        val vowel = ms(inf.consonant) - offset
        return OtoEntry(sample, alias, offset, vowel, -(ms(inf.end) - offset), vowel, (vowel / 3 * 10).roundToInt() / 10.0)
    }

    /** Mandarin syllables in pinyin (ü written as v), the set a NiaoNiao bank is usually recorded with. */
    val PINYIN: List<String> = ("a o e ai ei ao ou an en ang eng er yi ya yo ye yao you yan yin yang ying yong wu wa wo wai wei wan wen wang weng yu yue yuan yun " +
        "ba bo bai bei bao ban ben bang beng bi bie biao bian bin bing bu " +
        "pa po pai pei pao pou pan pen pang peng pi pie piao pian pin ping pu " +
        "ma mo me mai mei mao mou man men mang meng mi mie miao miu mian min ming mu " +
        "fa fo fei fou fan fen fang feng fu " +
        "da de dai dei dao dou dan den dang deng dong di die diao diu dian ding du duo dui duan dun " +
        "ta te tai tao tou tan tang teng tong ti tie tiao tian ting tu tuo tui tuan tun " +
        "na ne nai nei nao nou nan nen nang neng nong ni nie niao niu nian nin niang ning nu nuo nuan nv nve " +
        "la le lai lei lao lou lan lang leng long li lia lie liao liu lian lin liang ling lu luo luan lun lv lve " +
        "ga ge gai gei gao gou gan gen gang geng gong gu gua guo guai gui guan gun guang " +
        "ka ke kai kei kao kou kan ken kang keng kong ku kua kuo kuai kui kuan kun kuang " +
        "ha he hai hei hao hou han hen hang heng hong hu hua huo huai hui huan hun huang " +
        "ji jia jie jiao jiu jian jin jiang jing jiong ju jue juan jun " +
        "qi qia qie qiao qiu qian qin qiang qing qiong qu que quan qun " +
        "xi xia xie xiao xiu xian xin xiang xing xiong xu xue xuan xun " +
        "zha zhe zhi zhai zhei zhao zhou zhan zhen zhang zheng zhong zhu zhua zhuo zhuai zhui zhuan zhun zhuang " +
        "cha che chi chai chao chou chan chen chang cheng chong chu chua chuo chuai chui chuan chun chuang " +
        "sha she shi shai shei shao shou shan shen shang sheng shu shua shuo shuai shui shuan shun shuang " +
        "re ri rao rou ran ren rang reng rong ru ruo rui ruan run " +
        "za ze zi zai zei zao zou zan zen zang zeng zong zu zuo zui zuan zun " +
        "ca ce ci cai cao cou can cen cang ceng cong cu cuo cui cuan cun " +
        "sa se si sai sao sou san sen sang seng song su suo sui suan sun").split(' ')

    /** What [check] found: syllables of the set with no sound, sounds not in the set, pitches out of range. */
    class Report(
        val missing: List<String>, val extra: List<String>,
        /** Name and pitch (Hz) of the sounds below [low] or above [high] (MIDI numbers). */
        val outOfRange: List<Pair<String, Double>>, val noPitch: List<String>,
        val low: Double, val high: Double, val middle: Double,
    )

    /**
     * Checks the sounds' [pitches] (Hz by name, 0 = not measured) against [full] (null: no set) and the range
     * [low]..[high] in MIDI numbers; without a range it is [spread] semitones around the middle pitch of the bank.
     */
    fun check(pitches: Map<String, Double>, full: List<String>?, low: Double?, high: Double?, spread: Double): Report {
        val names = pitches.keys.associateBy { it.lowercase() }
        val set = full?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct()
        val missing = set?.filter { it !in names } ?: emptyList()
        val extra = if (set == null) emptyList() else names.filterKeys { it !in set.toSet() }.values.sorted()
        val midi = pitches.filterValues { it > 0 }.mapValues { mlabeler.core.dsp.Pitch.hzToMidi(it.value) }
        val sorted = midi.values.sorted()
        val middle = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
        val lo = low ?: (middle - spread)
        val hi = high ?: (middle + spread)
        val out = midi.filterValues { it < lo || it > hi }.keys.sorted().map { it to pitches.getValue(it) }
        return Report(missing, extra, out, pitches.filterValues { it <= 0 }.keys.sorted(), lo, hi, middle)
    }

    /** One sound of a bank: its name, its marks and its 16-bit samples from start to end. */
    class Sound(val name: String, val inf: Inf, val samples: ShortArray)

    /** voice.d and inf.d (the text with its lines) of [sounds], in the order of their names. */
    @OptIn(ExperimentalEncodingApi::class)
    fun pack(sounds: List<Sound>, version: Int = 1): Pair<ByteArray, String> {
        val sorted = sounds.sortedWith(compareBy { it.name })
        val total = sorted.sumOf { it.samples.size }
        val voice = ByteArray(total * 2)
        var pos = 0
        val lines = mutableListOf("v$version", "${sorted.size} 0 0 0 0 0 0 0 0 0")
        for (s in sorted) {
            val offset = pos * 2
            for (v in s.samples) { voice[pos * 2] = v.toByte(); voice[pos * 2 + 1] = (v.toInt() shr 8).toByte(); pos++ }
            val i = s.inf
            lines += "${s.name} $offset ${s.samples.size * 2} ${i.consonant - i.start} ${i.decay - i.start} ${formatPitch(i.pitch)} ${i.consonantLevel} ${i.level}\n"
        }
        return voice to lines.joinToString("") { Base64.encode(it.encodeToByteArray()) + "\n" }
    }

}
