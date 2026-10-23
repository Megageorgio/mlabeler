@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.darwin.NSObjectProtocol

internal fun checkAppleAudio(operation: String, action: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    check(action(error.ptr)) { "$operation: ${error.value?.localizedDescription ?: "audio device is unavailable"}" }
}

/** One process-wide session: playing a guide must not disable an already active microphone. Accessed on main. */
internal object AppleAudioSession {
    private data class Owner(val recording: Boolean, val stop: () -> Unit)
    private val owners = mutableMapOf<Any, Owner>()
    private val observers = mutableListOf<NSObjectProtocol>()

    fun acquire(owner: Any, recording: Boolean, stop: () -> Unit) {
        observeInterruptions()
        owners[owner] = Owner(recording, stop)
        try { configure() } catch (e: Exception) { owners.remove(owner); runCatching { configure() }; throw e }
    }

    fun release(owner: Any) {
        if (owners.remove(owner) != null) runCatching { configure() }
    }

    private fun configure() {
        val session = AVAudioSession.sharedInstance()
        if (owners.isEmpty()) {
            checkAppleAudio("Deactivate audio") { session.setActive(false, it) }
            return
        }
        val recording = owners.values.any { it.recording }
        val category = if (recording) AVAudioSessionCategoryPlayAndRecord else AVAudioSessionCategoryPlayback
        if (session.category != category) checkAppleAudio("Configure audio") {
            session.setCategory(category, withOptions = if (recording)
                AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth else 0uL, error = it)
        }
        checkAppleAudio("Activate audio") { session.setActive(true, it) }
    }

    private fun observeInterruptions() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { n ->
            // InterruptionType.began = 1. Do not resume recording without the user's action.
            if ((n?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.longLongValue == 1L) stopAll()
        }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, NSOperationQueue.mainQueue) { stopAll() }
    }

    private fun stopAll() {
        val callbacks = owners.values.map { it.stop }
        NSOperationQueue.mainQueue.addOperationWithBlock { callbacks.forEach { it() } }
    }

    fun watch(engine: AVAudioEngine, stop: () -> Unit): NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, NSOperationQueue.mainQueue) {
            // Teardown inside the engine's notification handler can deadlock. Defer another queue turn.
            NSOperationQueue.mainQueue.addOperationWithBlock { stop() }
        }

    fun unwatch(observer: NSObjectProtocol?) { observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) } }
}
