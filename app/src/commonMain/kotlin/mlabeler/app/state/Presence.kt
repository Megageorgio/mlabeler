package mlabeler.app.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mlabeler.app.PresenceInfo
import mlabeler.app.RichPresence
import mlabeler.app.i18n.L
import mlabeler.core.io.Paths

private val labellingT = L("Labelling", "Разметка")
private val labellingInT = L("Labelling {0}", "Разметка: {0}")
private val otoInT = L("Setting oto.ini: {0}", "Настройка oto.ini: {0}")
private val otoT = L("Setting oto.ini", "Настройка oto.ini")
private val recordingT = L("Recording", "Запись")
private val recordingInT = L("Recording {0}", "Запись: {0}")
private val karaokeT = L("Singing along", "Пение под музыку")
private val startT = L("Choosing a folder", "Выбор папки")
private val fileOfT = L("{0} · {1} of {2}", "{0} · {1} из {2}")
private val numberOfT = L("Recording {0} of {1}", "Запись {0} из {1}")

/** The two lines the Discord profile shows for what is open now; null when it is turned off. */
private fun AppState.presenceLines(): Pair<String, String>? {
    val d = settings.discord
    if (!d.enabled) return null
    val names = d.showNames
    karaoke?.let { return karaokeT() to "" }
    recorder?.let { r -> return (if (names) recordingInT.format(Paths.name(r.folder)) else recordingT()) to "" }
    val ed = editor ?: return startT() to ""
    val folder = Paths.name(ed.workspace.root)
    val oto = ed.mode == Mode.Oto
    val details = when {
        oto && names -> otoInT.format(folder)
        oto -> otoT()
        names -> labellingInT.format(folder)
        else -> labellingT()
    }
    val item = ed.item ?: return details to ""
    val n = ed.index + 1
    val state = if (names) fileOfT.format(item.name, n, ed.items.size) else numberOfT.format(n, ed.items.size)
    return details to state
}

/**
 * Keeps the Discord profile up to date with what is open (on computers, where [RichPresence] works). The time shown
 * runs from when the first line last changed: the time in this folder, the recorder, karaoke.
 */
internal fun AppState.followPresence(scope: CoroutineScope) {
    if (!RichPresence.supported) return
    scope.launch {
        var details: String? = null
        var since = 0L
        snapshotFlow { presenceLines() }.distinctUntilChanged().collect { lines ->
            if (lines == null) {
                details = null
                RichPresence.show(null)
                return@collect
            }
            if (lines.first != details) {
                details = lines.first
                since = kotlin.time.Clock.System.now().epochSeconds
            }
            RichPresence.show(PresenceInfo(lines.first, lines.second, since))
        }
    }
}
