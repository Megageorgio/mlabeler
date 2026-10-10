package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mlabeler.core.io.ItemState
import mlabeler.core.io.Workspace
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Counts the time spent labelling the way drawing programs do: only while the user is doing something. A key, a
 * click, the wheel, a mouse move over the focused window and every second of playback are activity; the time between
 * two of them counts only when they are at most [WorkTimeSettings.idleSeconds] apart, so a break or work in another
 * window is left out. The recorder and karaoke don't count. Kept per folder and per recording in the folder's
 * workspace.json (written every half minute and when the folder closes).
 */
class WorkTimer(private val app: AppState, scope: CoroutineScope) {
    private val clock = TimeSource.Monotonic
    private var last: TimeSource.Monotonic.ValueTimeMark? = null
    /** The folder the time not yet written belongs to. */
    private var ws: Workspace? = null
    private var folderPending = 0L
    private val itemPending = mutableMapOf<String, Long>()
    private var lastFlush = clock.markNow()

    /** Time in the open folder and in its open recording, ms; refreshed about once a second. */
    var folderMs by mutableLongStateOf(0L)
        private set
    var itemMs by mutableLongStateOf(0L)
        private set
    /** The user did something within the idle limit (the time is running). */
    var running by mutableStateOf(false)
        private set
    /** Time counted since the program started, ms. */
    var sessionMs by mutableLongStateOf(0L)
        private set

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                // listening is work too
                if (app.editor?.playing == true) activity()
                refresh()
                if (lastFlush.elapsedNow() >= 30.seconds) flush()
            }
        }
    }

    private val idleMs get() = app.settings.workTime.idleSeconds.coerceAtLeast(5) * 1000L

    private fun counting() = app.settings.workTime.enabled && app.recorder == null && app.karaoke == null

    /** The user did something (called for every input of the window). */
    fun activity() {
        val ed = app.editor
        if (ed == null || !counting()) {
            last = null
            return
        }
        if (ed.workspace !== ws) {
            flush()
            ws = ed.workspace
            last = null
        }
        val now = clock.markNow()
        val prev = last
        last = now
        if (prev == null) return
        val gap = (now - prev).inWholeMilliseconds
        if (gap <= 0 || gap > idleMs) return
        folderPending += gap
        sessionMs += gap
        ed.item?.id?.let { itemPending[it] = (itemPending[it] ?: 0L) + gap }
    }

    private fun refresh() {
        val ed = app.editor
        if (ed == null) {
            folderMs = 0; itemMs = 0; running = false
            return
        }
        val mine = ed.workspace === ws
        val id = ed.item?.id
        folderMs = ed.workspace.state.workMs + if (mine) folderPending else 0L
        itemMs = if (id == null) 0L else ed.workspace.itemState(id).workMs + if (mine) itemPending[id] ?: 0L else 0L
        running = counting() && mine && last?.let { it.elapsedNow().inWholeMilliseconds <= idleMs } == true
    }

    /** Writes the time counted so far into the folder's workspace.json. */
    fun flush() {
        lastFlush = clock.markNow()
        val w = ws ?: return
        if (folderPending == 0L && itemPending.isEmpty()) return
        val f = folderPending
        val items = itemPending.toMap()
        folderPending = 0
        itemPending.clear()
        w.updateState { s ->
            s.copy(
                workMs = s.workMs + f,
                items = s.items + items.mapValues { (id, ms) -> (s.items[id] ?: ItemState()).let { it.copy(workMs = it.workMs + ms) } },
            )
        }
    }

    /** Forgets the time of the open folder and its recordings. */
    fun reset() {
        val ed = app.editor ?: return
        if (ed.workspace === ws) {
            folderPending = 0
            itemPending.clear()
        }
        ed.workspace.updateState { s -> s.copy(workMs = 0, items = s.items.mapValues { (_, st) -> st.copy(workMs = 0) }) }
        refresh()
    }

    companion object {
        /** 1:05:09 or 5:09 for [ms]. */
        fun format(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s / 60) % 60
            val sec = (s % 60).toString().padStart(2, '0')
            return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
        }
    }
}
