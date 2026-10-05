package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T

private val working = L("Autolabel is running", "Идёт авторазметка")
private val waitHint = L("Editing is paused until it finishes, so the result lands where it belongs.",
    "Правка на паузе, пока она не закончится, — чтобы результат лёг куда нужно.")
private val stopT = L("Stop", "Остановить")
private val modelResult = L("Model result", "Результат модели")
private val accept = L("Accept", "Принять")
private val acceptHint = L("Put it into your labels (can be undone with Ctrl+Z)", "Записать в вашу разметку (отменяется Ctrl+Z)")
private val discard = L("Remove", "Убрать")

/** Covers the editor while the toolkit works on this file. */
@Composable
fun AutolabelBusy(ed: EditorState) {
    val stage = ed.toolkitBusy ?: return
    val c = T.c
    Box(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(c.radius * 2)
        Column(
            Modifier.widthIn(max = 420.dp).padding(16.dp).clip(shape).background(c.panel).border(c.borderWidth, c.border, shape).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text(working(), color = c.text, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
            }
            Text(stage, color = c.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            val p = ed.toolkitProgress
            if (p != null && p > 0) {
                LinearProgressIndicator(
                    progress = { p.toFloat().coerceIn(0f, 1f) }, color = c.accent, trackColor = c.border,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp),
                )
            }
            Text(waitHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                Btn(stopT()) { ed.cancelToolkit() }
            }
        }
    }
}

/** One line per model result of this file: accept it into the labels or remove it. */
@Composable
fun ModelResultsBar(ed: EditorState) {
    val results = ed.modelReferences
    if (results.isEmpty()) return
    val c = T.c
    Column(Modifier.fillMaxWidth().background(c.panelAlt)) {
        for (r in results) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(modelResult() + ": ", color = c.muted, fontSize = 13.sp)
                Text(r.name, color = c.text, fontSize = 13.sp, maxLines = 1)
                r.range?.let { (a, b) -> Text("${formatTime(a)} – ${formatTime(b)}", color = c.muted, fontSize = 12.sp) }
                Tip(acceptHint()) { Btn(accept(), primary = true, icon = Icons.check) { ed.acceptModelResult(r) } }
                Btn(discard(), icon = Icons.close) { ed.dropModelResult(r) }
            }
        }
    }
}
