package mlabeler.app.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mlabeler.app.PresenceInfo
import mlabeler.app.RichPresence
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier

/** What the Discord profile shows (always in English, so friends of any language can read it) and the clock's kind. */
private data class PresenceLines(val details: String, val state: String, val time: String)

private fun AppState.presenceLines(): PresenceLines? {
    val d = settings.discord
    if (!d.enabled) return null
    val time = d.time
    karaoke?.let { return PresenceLines("Singing along", "", time) }
    recorder?.let { r -> return PresenceLines(if (d.folder) "Recording ${Paths.name(r.folder)}" else "Recording", "", time) }
    val ed = editor ?: return PresenceLines("Choosing a folder", "", time)
    val folder = Paths.name(ed.workspace.root)
    val oto = ed.mode == Mode.Oto
    val verb = if (d.mode && oto) "Setting oto.ini" else "Labelling"
    val details = if (d.folder) (if (d.mode && oto) "$verb in $folder" else "$verb $folder") else verb

    val busy = ed.toolkitBusy
    if (d.work && busy != null) {
        val parts = listOfNotNull(
            ed.workKind ?: "Working",
            ed.workModel?.takeIf { d.model },
            ed.toolkitProgress?.takeIf { it > 0 }?.let { "${(it * 100).toInt()}%" },
        )
        return PresenceLines(details, parts.joinToString(" · "), time)
    }
    val item = ed.item
    val parts = buildList {
        if (item != null && d.file) add(item.name)
        if (d.phoneme && !oto) (ed.selection as? Selection.Interval)?.ref?.let { r ->
            (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.takeIf { r.index < it.size }?.let { add("[${it.texts[r.index].ifEmpty { "∅" }}]") }
        }
        if (d.phoneme && oto) ed.oto.selected?.let { i -> ed.oto.entries.getOrNull(i)?.alias?.takeIf { it.isNotEmpty() }?.let { add("[$it]") } }
        if (item != null && d.position) add("${ed.index + 1} of ${ed.items.size}")
        if (d.done && ed.items.isNotEmpty()) add("${ed.items.count { ed.marks(it).done }}/${ed.items.size} done")
    }
    return PresenceLines(details, parts.joinToString(" · "), time)
}

/** The two lines the Discord profile gets now (for the settings); null when turned off. */
fun presencePreview(app: AppState): Pair<String, String>? = app.presenceLines()?.let { it.details to it.state }

/**
 * Keeps the Discord profile up to date with what is open (on computers, where [RichPresence] works). The clock runs
 * from when the first line last changed ("folder"), from the program's start, or shows the folder's labelling time.
 */
internal fun AppState.followPresence(scope: CoroutineScope) {
    if (!RichPresence.supported) return
    val started = kotlin.time.Clock.System.now().epochSeconds
    scope.launch {
        var details: String? = null
        var since = started
        snapshotFlow { presenceLines() }.distinctUntilChanged().collect { lines ->
            if (lines == null) {
                details = null
                RichPresence.show(null)
                return@collect
            }
            val now = kotlin.time.Clock.System.now().epochSeconds
            if (lines.details != details) {
                details = lines.details
                since = now
            }
            val start = when (lines.time) {
                "off" -> null
                "start" -> started
                "work" -> now - workTimer.folderMs / 1000
                else -> since
            }
            RichPresence.show(PresenceInfo(lines.details, lines.state, start))
        }
    }
}
