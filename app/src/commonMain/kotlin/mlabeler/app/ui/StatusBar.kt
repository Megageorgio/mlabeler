package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.app.state.Selection
import mlabeler.app.state.StatusSettings
import mlabeler.app.state.WorkTimer
import mlabeler.app.theme.T
import mlabeler.core.model.IntervalTier

/** What the status bar can show, by id, with its name in the settings. */
object StatusItems {
    val names: Map<String, L> = linkedMapOf(
        "entry" to L("Phoneme number (167 / 1040)", "Номер фонемы (167 / 1040)"),
        "file" to L("File number (3 / 12)", "Номер файла (3 / 12)"),
        "done" to L("Files marked done (7 / 12)", "Файлы, отмеченные готовыми (7 / 12)"),
        "worktime" to L("Time spent labelling: the folder and this file", "Время разметки: папка и этот файл"),
        "phoneme" to L("Selected phoneme and its length", "Выбранная фонема и её длина"),
        "cursor" to L("Time under the mouse", "Время под мышью"),
        "range" to L("Selected part", "Выделенный фрагмент"),
        "work" to L("Work in progress (analysis, toolkit)", "Текущая работа (анализ, тулкит)"),
        "problems" to L("Number of warnings", "Число предупреждений"),
        "help" to L("Help (F1)", "Справка (F1)"),
        "zoom" to L("Scale (seconds on screen)", "Масштаб (секунд на экране)"),
    )
    val all: List<String> get() = names.keys.toList()
    val helpT = L("Help", "Справка")
    val zoomT = L("Scale", "Масштаб")
    val fileT = L("File", "Файл")
    val doneT = L("Done", "Готово")
    val timeT = L("Time", "Время")
    val fileTimeT = L("file", "файл")
}

@Composable
fun StatusBar(app: AppState, ed: EditorState) {
    val c = T.c
    val st = app.settings.status
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(c.panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (id in st.left) StatusItem(app, ed, id, st)
        Spacer(Modifier.weight(1f))
        for (id in st.right) StatusItem(app, ed, id, st)
    }
}

@Composable
private fun StatusItem(app: AppState, ed: EditorState, id: String, st: StatusSettings) {
    val c = T.c
    when (id) {
        "cursor" -> {
            val cur = ed.playhead ?: ed.cursor
            StatusText(cur?.let { formatTime(it) } ?: "–", Modifier.width(80.dp))
        }
        "range" -> ed.range?.let { (a, b) -> StatusText("${formatTime(a)} – ${formatTime(b)}  (${formatMs(b - a)})") }
        "entry" -> if (ed.mode == Mode.Labels) {
            val doc = ed.doc
            val sel = (ed.selection as? Selection.Interval)?.ref
            val k = sel?.tier ?: ed.guideTier
            val tier = doc?.tiers?.getOrNull(k) as? IntervalTier
            if (tier != null && tier.size > 0) {
                val n = sel?.index?.plus(1)
                val pct = if (n != null && st.percent) " (${n * 100 / tier.size}%)" else ""
                StatusText("${n ?: "–"} / ${tier.size}$pct")
            }
        } else {
            ed.oto.let { o ->
                val all = o.entries.size
                val i = o.selected ?: -1
                if (all > 0) StatusText("${if (i >= 0) i + 1 else "–"} / $all" + if (i >= 0 && st.percent) " (${(i + 1) * 100 / all}%)" else "")
            }
        }
        "file" -> if (ed.items.isNotEmpty()) {
            val n = ed.index + 1
            StatusText(StatusItems.fileT() + " ${if (n > 0) n else "–"} / ${ed.items.size}" + if (n > 0 && st.percent) " (${n * 100 / ed.items.size}%)" else "")
        }
        "done" -> if (ed.items.isNotEmpty()) {
            val n = ed.items.count { ed.marks(it).done }
            StatusText(StatusItems.doneT() + " $n / ${ed.items.size}" + if (st.percent) " (${n * 100 / ed.items.size}%)" else "")
        }
        "phoneme" -> (ed.selection as? Selection.Interval)?.ref?.let { r ->
            (ed.doc?.tiers?.getOrNull(r.tier) as? IntervalTier)?.takeIf { r.index < it.size }?.let { t ->
                StatusText("${t.texts[r.index].ifEmpty { "∅" }} · ${formatMs(t.durationOf(r.index))}")
            }
        }
        "work" -> {
            val spec = ed.spectrogram
            // the progress is the observed value; the picture's own counter changes without telling anyone
            if (ed.audio != null && (spec == null || ed.spectrogramProgress < spec.frames)) StatusText(S.analysing())
            ed.toolkitBusy?.let { b ->
                val numbers = ed.toolkitDetail?.numbers()?.let { " · $it" }.orEmpty()
                val pct = ed.toolkitProgress?.takeIf { it > 0 }?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
                StatusText(S.toolkit() + ": " + b + numbers + pct, color = c.accent)
                Text("×", color = c.muted, fontSize = 14.sp, modifier = Modifier.clickable { ed.cancelToolkit() })
            }
        }
        "worktime" -> if (app.settings.workTime.enabled) {
            val t = app.workTimer
            // brighter while the time runs, faint during a break
            StatusText(StatusItems.timeT() + " " + WorkTimer.format(t.folderMs) + if (ed.item != null) " (" + StatusItems.fileTimeT() + " " + WorkTimer.format(t.itemMs) + ")" else "",
                color = if (t.running) c.text else c.muted, modifier = Modifier.clickable { app.showSummary = true })
        }
        "problems" -> if (ed.problems.isNotEmpty()) StatusText("⚠ ${ed.problems.size}", color = c.warn)
        "help" -> Text(StatusItems.helpT() + " · F1", color = c.muted, fontSize = 12.sp, modifier = Modifier.clickable { app.showHelp = true })
        "zoom" -> {
            val v = ed.visibleDuration
            val n = if (v < 10) ((v * 100).toLong() / 100.0).toString() else v.toLong().toString()
            StatusText(StatusItems.zoomT() + ": " + n + " " + S.secondsShort())
        }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier, color: Color = T.c.muted) {
    Text(text, color = color, fontSize = 12.sp, maxLines = 1, modifier = modifier)
}

private val leftT = L("Left", "Слева")
private val rightT = L("Right", "Справа")
private val hiddenT = L("Hidden", "Скрыто")
private val percentT = L("Percent next to the numbers", "Проценты рядом с номерами")
private val statusHint = L("What is shown at the bottom of the window, on which side and in which order.", "Что показывается внизу окна, с какой стороны и в каком порядке.")

/** Settings of the status bar: every item can be on the left, on the right or hidden, and moved within its side. */
@Composable
fun StatusBarSettings(app: AppState, switchRow: @Composable (String, Boolean, (Boolean) -> Unit) -> Unit) {
    val c = T.c
    val st = app.settings.status
    Text(statusHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
    switchRow(percentT(), st.percent) { v -> app.update { it.copy(status = it.status.copy(percent = v)) } }
    val hidden = StatusItems.all.filter { it !in st.left && it !in st.right }
    for ((side, list) in listOf("left" to st.left, "right" to st.right, "" to hidden)) {
        for ((i, id) in list.withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(StatusItems.names[id]?.invoke() ?: id, color = if (side.isEmpty()) c.muted else c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip(leftT(), side == "left") { app.update { it.copy(status = it.status.place(id, "left")) } }
                    Chip(rightT(), side == "right") { app.update { it.copy(status = it.status.place(id, "right")) } }
                    Chip(hiddenT(), side.isEmpty()) { app.update { it.copy(status = it.status.place(id, "")) } }
                }
                IconBtn(Icons.up, S.moveUp(), enabled = side.isNotEmpty() && i > 0, size = 28.dp) { app.update { it.copy(status = it.status.move(id, -1)) } }
                IconBtn(Icons.down, S.moveDown(), enabled = side.isNotEmpty() && i < list.size - 1, size = 28.dp) { app.update { it.copy(status = it.status.move(id, 1)) } }
            }
        }
    }
}
