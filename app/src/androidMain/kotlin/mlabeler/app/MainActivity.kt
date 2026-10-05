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
        AndroidContext.askMic = { cb -> micCallback = cb; micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
        enableEdgeToEdge()
        setContent { App() }
    }
}
