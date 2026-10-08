package mlabeler.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private var micCallback: ((Boolean) -> Unit)? = null
    private val micLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        micCallback?.invoke(ok)
        micCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContext.init(applicationContext)
        AndroidContext.activity = this
        // a crash leaves its report for the next start
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashHandler) Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
        // draw under the camera cutout too: the whole screen is used
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        AndroidContext.askMic = { cb -> micCallback = cb; micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onDestroy() {
        if (AndroidContext.activity === this) AndroidContext.activity = null
        super.onDestroy()
    }
}

private class CrashHandler(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        CrashLog.write(e.stackTraceToString())
        next?.uncaughtException(t, e)
    }
}
