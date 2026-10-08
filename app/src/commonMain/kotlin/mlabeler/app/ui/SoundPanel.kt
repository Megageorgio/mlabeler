package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T

internal object SoundTitles {
    val title = L("Sound editing", "Правка звука")
    val about = L("Labels are locked: dragging selects parts of the sound, nothing moves the boundaries. Changes of the recording are undone with Ctrl+Z.",
        "Метки закреплены: перетаскивание выделяет куски звука, границы не сдвигаются. Изменения записи отменяются через Ctrl+Z.")
    val showLabels = L("Show the boundaries over the sound", "Показывать границы поверх звука")
    val style = L("Boundaries in this mode", "Границы в этом режиме")
    val dash = L("Dashed", "Пунктир")
    val dot = L("Dotted", "Точки")
    val solid = L("Line", "Линия")
    val phrases = L("Mark the parts between pauses", "Отмечать куски между паузами")
    val leave = L("Back to labelling", "Вернуться к разметке")
    val locked = L("Labels are locked while editing the sound", "Пока правится звук, метки закреплены")
}

/** The panel of sound editing mode: how labels look in it, and every tool that changes the recording. */
@Composable
fun SoundPanel(app: AppState, ed: EditorState, modifier: Modifier) {
    val c = T.c
    val l = app.settings.layout
    Column(modifier.background(c.panel).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SoundTitles.title(), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Tip(SoundTitles.about()) { Text("?", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp)) }
            IconBtn(Icons.close, SoundTitles.leave(), size = 28.dp) { ed.soundMode = false }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(SoundTitles.showLabels(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Toggle(l.soundShowLabels, { v -> app.update { it.copy(layout = it.layout.copy(soundShowLabels = v)) } })
        }
        if (l.soundShowLabels) {
            Text(SoundTitles.style(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, t) in listOf("dash" to SoundTitles.dash, "dot" to SoundTitles.dot, "solid" to SoundTitles.solid)) {
                    Chip(t(), l.soundLabelStyle == k) { app.update { it.copy(layout = it.layout.copy(soundLabelStyle = k)) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(SoundTitles.phrases(), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Toggle(l.soundPhrases, { v -> app.update { it.copy(layout = it.layout.copy(soundPhrases = v)) } })
        }
        CleanupTools(app, ed, compact = true)
    }
}
