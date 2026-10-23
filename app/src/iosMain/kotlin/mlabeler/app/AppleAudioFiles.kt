@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import kotlinx.cinterop.*
import mlabeler.core.audio.Audio
import platform.AVFAudio.*
import platform.Foundation.*

internal fun decodeAppleAudio(path: String): Audio = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = error.ptr)
        ?: error(error.value?.localizedDescription ?: "Cannot open audio: $path")
    val format = file.processingFormat
    check(format.channelCount > 0u && format.sampleRate > 0) { "Invalid audio format" }
    check(file.length >= 0 && file.length <= Int.MAX_VALUE.toLong() / format.channelCount.toLong()) { "Audio file is too large" }
    val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = file.length.toUInt().coerceAtLeast(1u))
    checkAppleAudio("Read audio") { file.readIntoBuffer(buffer, it) }
    val channels = buffer.floatChannelData ?: error("Unsupported audio sample format")
    val count = format.channelCount.toInt()
    val samples = FloatArray(buffer.frameLength.toInt()) { frame ->
        var sum = 0f
        for (channel in 0 until count) sum += channels[channel]!![frame]
        sum / count
    }
    Audio(format.sampleRate.toInt(), samples, count)
}
