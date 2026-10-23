@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol
import kotlin.math.ceil

actual class AudioIn actual constructor() {
    private var engine: AVAudioEngine? = null
    private var observer: NSObjectProtocol? = null
    private var tapInstalled = false
    private var generation = 0L
    private var permissionRequest = 0L

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        val request = ++permissionRequest
        AVAudioSession.sharedInstance().requestRecordPermission { ok ->
            NSOperationQueue.mainQueue.addOperationWithBlock { if (request == permissionRequest) onResult(ok) }
        }
    }

    actual fun start(sampleRate: Int, onChunk: (FloatArray) -> Unit) {
        stop()
        require(sampleRate > 0) { "Invalid recording sample rate" }
        val run = ++generation
        try {
            AppleAudioSession.acquire(this, recording = true) { if (generation == run) stop() }
            val e = AVAudioEngine()
            engine = e
            val input = e.inputNode
            val format = input.outputFormatForBus(0u)
            check(format.sampleRate > 0 && format.channelCount > 0u) { "No microphone input is available" }
            val target = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
            val converter = AVAudioConverter(fromFormat = format, toFormat = target)
                ?: error("Cannot convert microphone audio")
            input.installTapOnBus(0u, 1024u, format) { buffer, _ ->
                val b = buffer ?: return@installTapOnBus
                if (generation != run || b.frameLength == 0u) return@installTapOnBus
                try {
                    val capacity = ceil(b.frameLength.toDouble() * sampleRate / format.sampleRate).toUInt() + 64u
                    val converted = AVAudioPCMBuffer(pCMFormat = target, frameCapacity = capacity)
                    var supplied = false
                    val status = converter.convertToBuffer(converted, error = null) { _, inputStatus ->
                        if (supplied) {
                            inputStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                            null
                        } else {
                            supplied = true
                            inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                            b
                        }
                    }
                    check(status != AVAudioConverterOutputStatus_Error) { "Microphone conversion failed" }
                    val n = converted.frameLength.toInt()
                    val ch = converted.floatChannelData?.get(0)
                    if (n > 0 && ch != null) onChunk(FloatArray(n) { ch[it] })
                } catch (_: Exception) {
                    // Never throw through an Objective-C audio callback.
                    NSOperationQueue.mainQueue.addOperationWithBlock { if (generation == run) stop() }
                }
            }
            tapInstalled = true
            e.prepare()
            checkAppleAudio("Start recording") { e.startAndReturnError(it) }
            observer = AppleAudioSession.watch(e) { if (engine === e && generation == run) stop() }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    actual fun stop() {
        permissionRequest++
        generation++
        AppleAudioSession.unwatch(observer)
        observer = null
        val e = engine
        engine = null
        if (tapInstalled) e?.inputNode?.removeTapOnBus(0u)
        tapInstalled = false
        e?.stop()
        AppleAudioSession.release(this)
    }

    actual val isRecording: Boolean get() = engine != null
}
