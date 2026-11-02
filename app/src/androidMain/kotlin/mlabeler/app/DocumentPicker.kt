package mlabeler.app

import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch

/** Bridges the core's blocking picker API to the activity-result API, always off the UI thread. */
internal object DocumentPicker {
    var launch: ((Intent) -> Unit)? = null
    private var completion: ((Intent?) -> Unit)? = null

    @Synchronized fun complete(data: Intent?) {
        val callback = completion
        completion = null
        callback?.invoke(data)
    }

    fun pick(intent: Intent): Intent? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "The document picker must run off the UI thread" }
        val activity = AndroidContext.activity ?: return null
        val done = CountDownLatch(1)
        var result: Intent? = null
        synchronized(this) {
            if (completion != null) return null
            completion = { result = it; done.countDown() }
        }
        activity.runOnUiThread {
            val start = launch
            if (activity.isFinishing || activity.isDestroyed || start == null) complete(null)
            else try { start(intent) } catch (e: Exception) { complete(null) }
        }
        done.await()
        return result
    }
}
