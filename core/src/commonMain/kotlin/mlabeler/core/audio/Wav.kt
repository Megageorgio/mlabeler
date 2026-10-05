package mlabeler.core.audio

/** Decoded audio: a mono mix as floats in -1..1 plus the original channel count. */
class Audio(val sampleRate: Int, val samples: FloatArray, val channels: Int = 1) {
    val duration: Double get() = samples.size.toDouble() / sampleRate
    val durationMs: Double get() = duration * 1000.0
}

class AudioException(message: String) : Exception(message)

/** WAV reader: PCM 8 (unsigned), 16, 24, 32 bit, IEEE float 32/64, WAVE_FORMAT_EXTENSIBLE, RF64. */
object Wav {
    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
    private fun ByteArray.le64(i: Int) = (le32(i).toLong() and 0xFFFFFFFFL) or (le32(i + 4).toLong() shl 32)
    private fun ByteArray.tag(i: Int) = if (i + 4 <= size) decodeToString(i, i + 4) else ""

    fun isWav(bytes: ByteArray) = bytes.size >= 12 && bytes.tag(0) in setOf("RIFF", "RF64", "BW64") && bytes.tag(8) == "WAVE"

    fun decode(bytes: ByteArray): Audio {
        if (!isWav(bytes)) throw AudioException("Not a WAV file")
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0L
        var ds64DataSize = -1L
        while (pos + 8 <= bytes.size) {
            val id = bytes.tag(pos)
            var size = bytes.le32(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "ds64" -> if (body + 16 <= bytes.size) ds64DataSize = bytes.le64(body + 8)
                "fmt " -> {
                    format = bytes.le16(body)
                    channels = bytes.le16(body + 2)
                    rate = bytes.le32(body + 4)
                    bits = bytes.le16(body + 14)
                    if (format == 0xFFFE && size >= 40) format = bytes.le16(body + 24) // sub-format GUID starts with the tag
                }
                "data" -> {
                    if (size == 0xFFFFFFFFL && ds64DataSize >= 0) size = ds64DataSize
                    dataStart = body
                    dataSize = minOf(size, (bytes.size - body).toLong())
                    break
                }
            }
            pos = body + size.toInt() + (size.toInt() and 1)
            if (size > Int.MAX_VALUE) break
        }
        if (format < 0) throw AudioException("WAV has no format chunk")
        if (dataStart < 0) throw AudioException("WAV has no data")
        if (channels <= 0 || rate <= 0) throw AudioException("WAV header is broken")
        val bytesPerSample = (bits + 7) / 8
        val frameSize = bytesPerSample * channels
        if (frameSize == 0) throw AudioException("WAV header is broken")
        val frames = (dataSize / frameSize).toInt()
        val out = FloatArray(frames)
        val read: (Int) -> Float = when {
            format == 1 && bytesPerSample == 1 -> { i -> (bytes.u8(i) - 128) / 128f }
            format == 1 && bytesPerSample == 2 -> { i -> (bytes.le16(i).toShort()) / 32768f }
            format == 1 && bytesPerSample == 3 -> { i -> ((bytes.u8(i) or (bytes.u8(i + 1) shl 8) or (bytes[i + 2].toInt() shl 16))) / 8388608f }
            format == 1 && bytesPerSample == 4 -> { i -> (bytes.le32(i) / 2147483648.0).toFloat() }
            format == 3 && bytesPerSample == 4 -> { i -> Float.fromBits(bytes.le32(i)) }
            format == 3 && bytesPerSample == 8 -> { i -> Double.fromBits(bytes.le64(i)).toFloat() }
            else -> throw AudioException("Unsupported WAV encoding (format $format, $bits bit)")
        }
        val inv = 1f / channels
        var p = dataStart
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += read(p)
                p += bytesPerSample
            }
            out[f] = acc * inv
        }
        return Audio(rate, out, channels)
    }

    /** 16-bit mono WAV, used for exports and tests. */
    fun encode16(audio: Audio): ByteArray {
        val n = audio.samples.size
        val out = ByteArray(44 + n * 2)
        fun put32(i: Int, v: Int) { out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte(); out[i + 2] = (v shr 16).toByte(); out[i + 3] = (v shr 24).toByte() }
        fun put16(i: Int, v: Int) { out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte() }
        "RIFF".encodeToByteArray().copyInto(out, 0)
        put32(4, 36 + n * 2)
        "WAVEfmt ".encodeToByteArray().copyInto(out, 8)
        put32(16, 16); put16(20, 1); put16(22, 1); put32(24, audio.sampleRate); put32(28, audio.sampleRate * 2); put16(32, 2); put16(34, 16)
        "data".encodeToByteArray().copyInto(out, 36)
        put32(40, n * 2)
        for (i in 0 until n) put16(44 + i * 2, (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt())
        return out
    }
}
