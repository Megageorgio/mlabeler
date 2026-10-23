@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.*
import mlabeler.app.state.AppState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskInvalid

/** Finish pending atomic saves within the system's background grace period, without blocking UIKit. */
@Composable
internal fun AppleLifecycle(app: AppState) {
    DisposableEffect(app) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var saving: Job? = null
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            if (saving?.isActive != true) {
                val application = UIApplication.sharedApplication
                var task = UIBackgroundTaskInvalid
                task = application.beginBackgroundTaskWithName("Save project") { saving?.cancel() }
                saving = scope.launch {
                    try { app.preserveSession() }
                    finally {
                        if (task != UIBackgroundTaskInvalid) application.endBackgroundTask(task)
                    }
                }
            }
        }
        onDispose {
            center.removeObserver(observer)
            // Keep a save already in flight alive until it releases its UIKit background task.
            val pending = saving
            if (pending?.isActive == true) pending.invokeOnCompletion { scope.cancel() } else scope.cancel()
        }
    }
}
