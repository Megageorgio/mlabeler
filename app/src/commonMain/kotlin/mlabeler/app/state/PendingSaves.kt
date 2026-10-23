package mlabeler.app.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serial writes retain their captured take on failure. A later flush retries before allowing the app to close. */
internal class PendingSaves(private val scope: CoroutineScope, private val onError: (Exception) -> Unit) {
    private val pending = ArrayDeque<suspend () -> Unit>()
    private var worker: Job? = null

    fun submit(write: suspend () -> Unit) {
        pending.addLast(write)
        start()
    }

    private fun start() {
        if (worker?.isActive == true || pending.isEmpty()) return
        worker = scope.launch {
            while (pending.isNotEmpty()) {
                try { pending.first().invoke() }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onError(e)
                    break
                }
                pending.removeFirst()
            }
        }
    }

    suspend fun flush(): Boolean {
        start()
        worker?.join()
        return pending.isEmpty()
    }
}
