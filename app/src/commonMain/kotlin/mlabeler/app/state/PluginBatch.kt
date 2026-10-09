package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import mlabeler.app.i18n.L
import mlabeler.app.plugins.Plugin
import mlabeler.app.plugins.Plugins
import mlabeler.core.io.Item
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/**
 * A labels plugin run over many files of the folder: first every file is tried and what would change is shown,
 * then the changes are written when asked (each file keeps a backup in .mlabeler/backup, the open one can be undone).
 */
class PluginBatch(val plugin: Plugin, private val params: Map<String, JsonElement>, val files: List<Item>) {
    /** What the plugin would make of one file: the new labels (null on an error), intervals changed, its report. */
    data class FileResult(val item: Item, val after: LabelDoc?, val duration: Double, val changed: Int, val note: String?)

    val results = mutableStateListOf<FileResult>()
    var checking by mutableStateOf(true)
        private set
    var applied by mutableStateOf<Int?>(null)
        private set
    private var job: Job? = null

    val changedFiles: List<FileResult> get() = results.filter { it.after != null && it.changed > 0 }
    val failedFiles: List<FileResult> get() = results.filter { it.after == null }

    /** Tries the plugin on every file without writing anything. */
    fun check(ed: EditorState, scope: CoroutineScope) {
        job = scope.launch {
            try {
                for (f in files) {
                    val before = ed.currentLabels(f)
                    if (before == null) { results += FileResult(f, null, 0.0, 0, T.noLabels()); continue }
                    val duration = if (f.id == ed.item?.id) ed.duration else before.end
                    results += try {
                        val base = ed.app.pluginContext(ed, false)
                        val ctx = mlabeler.app.plugins.PluginContext(base.folder, base.files, ed.marks(f), null,
                            if (f.id == ed.item?.id) ed.pitchCurve else null, base.language, base.platform, base.backup)
                        val r = Plugins.run(plugin, params, before, null, f.name, duration, ctx)
                        val after = r.doc?.let { mlabeler.core.edit.Edits.fitToDuration(it, duration) } ?: before
                        val note = listOfNotNull(r.report, r.logs.takeIf { it.isNotEmpty() }?.joinToString("; ")).joinToString(" · ").ifEmpty { null }
                        FileResult(f, after, duration, changes(before, after), note)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileResult(f, null, duration, 0, e.message ?: e.toString())
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    /** Writes the changed files: the open one through its history (undoable), the others with a backup. */
    fun apply(ed: EditorState) {
        if (checking || applied != null) return
        var n = 0
        for (r in changedFiles) {
            val after = r.after ?: continue
            runCatching {
                if (r.item.id == ed.item?.id) ed.updateDocShowingChanges { after }
                else ed.writeOtherLabels(r.item, after, r.duration)
                n++
            }
        }
        ed.labelsChangedOnDisk()
        applied = n
    }

    fun cancel() { job?.cancel() }

    private object T {
        val noLabels = L("no labels yet", "разметки ещё нет")
    }

    companion object {
        /** Intervals that differ between [a] and [b] (text or boundaries), counted over the interval tiers. */
        fun changes(a: LabelDoc, b: LabelDoc): Int {
            val ta = a.tiers.filterIsInstance<IntervalTier>()
            val tb = b.tiers.filterIsInstance<IntervalTier>()
            var n = kotlin.math.abs(ta.size - tb.size)
            for (k in 0 until minOf(ta.size, tb.size)) {
                val x = ta[k]
                val y = tb[k]
                if (x.name != y.name) n++
                val m = minOf(x.size, y.size)
                n += kotlin.math.abs(x.size - y.size)
                for (i in 0 until m) {
                    if (x.texts[i] != y.texts[i] || kotlin.math.abs(x.startOf(i) - y.startOf(i)) > 1e-6 || kotlin.math.abs(x.endOf(i) - y.endOf(i)) > 1e-6) n++
                }
            }
            return n
        }
    }
}
