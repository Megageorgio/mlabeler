package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.saveResynth
import mlabeler.app.theme.T

private val titleT = L("Save the sound with the drawn pitch", "Сохранить звук с нарисованной высотой")
private val aboutT = L(
    "The whole recording is sung again by the toolkit with the pitch as it is now: the pitch of the recording with what was drawn and marked unvoiced.",
    "Тулкит заново поёт всю запись с высотой, какая она сейчас: высота записи с нарисованным и отмеченным как глухое.",
)
private val howT = L("How", "Как")
private val worldT = L("Quick (WORLD)", "Быстро (WORLD)")
private val nsfT = L("DiffSinger vocoder (NSF-HiFiGAN)", "Вокодер DiffSinger (NSF-HiFiGAN)")
private val nsfNoteT = L("NSF-HiFiGAN by OpenVPI: CC BY-NC-SA 4.0, non-commercial use only; downloaded on first use.",
    "NSF-HiFiGAN от OpenVPI: CC BY-NC-SA 4.0, только некоммерческое использование; загружается при первом использовании.")
private val whereT = L("Where", "Куда")
private val replaceT = L("In place of the recording (Ctrl+Z undoes it)", "Вместо записи (отмена — Ctrl+Z)")
private val copyT = L("A copy next to it (name_f0.wav)", "Копией рядом (имя_f0.wav)")
private val runT = L("Save", "Сохранить")

@Composable
fun ResynthDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showResynth = false; ed.requestFocus() }
    var method by remember { mutableStateOf("world") }
    var copy by remember { mutableStateOf(false) }
    Overlay({ close() }, 560) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(runT(), primary = true, enabled = ed.pitch != null) { ed.saveResynth(method, copy); close() }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            SectionTitle(howT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(worldT(), method == "world") { method = "world" }
                Chip(nsfT(), method == "nsf") { method = "nsf" }
            }
            if (method == "nsf") Text(nsfNoteT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            SectionTitle(whereT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(replaceT(), !copy) { copy = false }
                Chip(copyT(), copy) { copy = true }
            }
        }
    }
}
