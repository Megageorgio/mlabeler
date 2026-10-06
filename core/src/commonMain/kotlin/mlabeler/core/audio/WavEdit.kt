package mlabeler.core.audio

import kotlin.math.roundToLong

/**
 * Sample-exact editing of a WAV file: reads each channel at its own bit depth and writes back only the
 * samples that were changed. Header, other chunks and every untouched sample stay byte-for-byte the same.
 * PCM 16/24/32 bit and IEEE float 32 are supported.
 */
class WavEdit(original: ByteArray) {
    val bytes: ByteArray = original.copyOf()
    val sampleRate: Int
    val channels: Int
    val bits: Int
    val format: Int
    private val dataStart: Int
    val frames: Int
    private val bps: Int

    init {
        if (!Wav.isWav(bytes)) throw AudioException("Not a WAV file")
        var pos = 12
        var fmt = -1; var ch = 0; var rate = 0; var b = 0; var start = -1; var size = 0L; var ds64 = -1L
        while (pos + 8 <= bytes.size) {
            val id = bytes.decodeToString(pos, pos + 4)
            var sz = le32(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "ds64" -> if (body + 16 <= bytes.size) ds64 = (le32(body + 8).toLong() and 0xFFFFFFFFL) or (le32(body + 12).toLong() shl 32)
                "fmt " -> {
                    fmt = le16(body); ch = le16(body + 2); rate = le32(body + 4); b = le16(body + 14)
                    if (fmt == 0xFFFE && sz >= 40) fmt = le16(body + 24)
                }
                "data" -> {
                    if (sz == 0xFFFFFFFFL && ds64 >= 0) sz = ds64
                    start = body
                    size = minOf(sz, (bytes.size - body).toLong())
                    break
                }
            }
            pos = body + sz.toInt() + (sz.toInt() and 1)
        }
        if (fmt < 0 || start < 0 || ch <= 0 || rate <= 0) throw AudioException("WAV header is broken")
        val ok = (fmt == 1 && b in setOf(16, 24, 32)) || (fmt == 3 && b == 32)
        if (!ok) throw AudioException("Only 16/24/32-bit PCM and 32-bit float WAV can be cleaned (this one: format $fmt, $b bit)")
        format = fmt; channels = ch; sampleRate = rate; bits = b; dataStart = start
        bps = b / 8
        frames = (size / (bps * ch)).toInt()
    }

    private fun u8(i: Int) = bytes[i].toInt() and 0xFF
    private fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)

    private fun offset(frame: Int, ch: Int) = dataStart + (frame * channels + ch) * bps

    fun get(frame: Int, ch: Int): Float {
        val i = offset(frame, ch)
        return when {
            format == 3 -> Float.fromBits(le32(i))
            bps == 2 -> le16(i).toShort() / 32768f
            bps == 3 -> (u8(i) or (u8(i + 1) shl 8) or (bytes[i + 2].toInt() shl 16)) / 8388608f
            else -> (le32(i) / 2147483648.0).toFloat()
        }
    }

    fun channel(ch: Int, from: Int = 0, to: Int = frames): FloatArray = FloatArray(to - from) { get(from + it, ch) }

    /** Writes [v] at ([frame], [ch]); rounds to the file's depth and clips. Returns true if the stored value changed. */
    fun set(frame: Int, ch: Int, v: Float): Boolean {
        val i = offset(frame, ch)
        val before = (0 until bps).map { bytes[i + it] }
        val x = v.coerceIn(-1f, 1f)
        when {
            format == 3 -> put(i, x.toRawBits().toLong(), 4)
            bps == 2 -> put(i, (x * 32768.0).roundToLong().coerceIn(-32768, 32767), 2)
            bps == 3 -> put(i, (x * 8388608.0).roundToLong().coerceIn(-8388608, 8388607), 3)
            else -> put(i, (x * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()), 4)
        }
        return (0 until bps).any { bytes[i + it] != before[it] }
    }

    private fun put(i: Int, v: Long, n: Int) {
        for (k in 0 until n) bytes[i + k] = (v shr (8 * k)).toByte()
    }
}
