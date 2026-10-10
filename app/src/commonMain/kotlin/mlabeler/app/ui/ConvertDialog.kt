package mlabeler.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.convertLabels
import mlabeler.app.state.convertTargets
import mlabeler.app.state.Mode
import mlabeler.app.state.filesToConvert
import mlabeler.app.state.niaoFiles
import mlabeler.app.state.niaoFromOto
import mlabeler.app.state.niaoHasOto
import mlabeler.app.state.niaoToOto
import mlabeler.app.state.otoToLab
import mlabeler.app.theme.T

private val titleT = L("Convert label files", "Перевод файлов разметки в другой формат")
private val toT = L("Into", "В формат")
private val countT = L("Files to convert: {0}", "Файлов для перевода: {0}")
private val byFormatT = L("now: {0}", "сейчас: {0}")
private val aboutT = L(
    "Each recording keeps one label file: the new one is written next to it and the old one goes to .mlabeler/backup. Rows of a transcriptions.csv stay where they are.",
    "У каждой записи остаётся один файл разметки: новый пишется рядом с ней, а старый переносится в .mlabeler/backup. Строки transcriptions.csv остаются на месте.",
)
private val defaultT = L("Use this format for new labels of the folder too", "Использовать этот формат и для новой разметки папки")
private val runT = L("Convert", "Перевести")
private val otoTitleT = L("oto.ini", "oto.ini")
private val fromOtoMissingT = L("oto.ini → .inf where there are none", "oto.ini → .inf там, где их нет")
private val fromOtoAllT = L("oto.ini → .inf for all", "oto.ini → .inf для всех")
private val toOtoT = L(".inf → oto.ini", ".inf → oto.ini")
private val otoHintT = L("NiaoNiao: the offset is the start, the preutterance and the fixed part end where the vowel starts, the cutoff is the end; the decay, which oto.ini lacks, is placed from the loudness. Recordings of several syllables (VCV, long takes) or named in kana are cut into one recording per syllable in a new folder <folder>_niaoniao.",
    "NiaoNiao: смещение — начало, преутерация и фиксированная часть заканчиваются там, где начинается гласная, отсечка — конец; затухание, которого в oto.ini нет, ставится по громкости. Записи из нескольких слогов (VCV, длинные дубли) или с именами каной разрезаются на отдельные записи по слогу в новой папке <папка>_niaoniao.")
private val toLabT = L("oto.ini → .lab", "oto.ini → .lab")
private val labHintT = L(".lab: the consonant of each syllable from the overlap to the preutterance, the vowel up to the cutoff or the next consonant, SP between them. The old label files go to .mlabeler/backup.",
    ".lab: согласная каждого слога от перекрытия до преутерации, гласная до отсечки или до следующей согласной, между ними SP. Старая разметка уходит в .mlabeler/backup.")

@Composable
fun ConvertDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showConvert = false; ed.requestFocus() }
    var target by remember { mutableStateOf(ed.workspace.state.defaultFormat.takeIf { it in convertTargets } ?: mlabeler.core.format.LabelFormat.TextGrid) }
    var makeDefault by remember { mutableStateOf(true) }
    val files = remember(target, ed.items) { ed.filesToConvert(target) }
    val otoMode = ed.mode == Mode.Oto
    // a direction is offered only when there is something to convert from
    val wavs = remember(ed.items) { ed.niaoFiles() }
    val hasOto = remember(wavs) { ed.niaoHasOto(wavs) }
    val infs = wavs.count { it.labelFormat == mlabeler.core.format.LabelFormat.Inf }
    Overlay({ close() }, 560) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            if (!otoMode) Btn(runT(), primary = true, enabled = files.isNotEmpty()) {
                ed.convertLabels(files, target, makeDefault)
                close()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            if (!otoMode) {
            SectionTitle(toT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in convertTargets) Chip(f.title, f == target) { target = f }
            }
            Text(countT.format(files.size), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            val byFormat = files.groupingBy { it.labelFormat!!.title }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
            if (byFormat.isNotEmpty()) Text(byFormatT.format(byFormat), color = c.muted, fontSize = 12.sp)
            Text(aboutT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { makeDefault = !makeDefault }, verticalAlignment = Alignment.CenterVertically) {
                Text(defaultT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Toggle(makeDefault, { makeDefault = it })
            }
            }
            if (hasOto || infs > 0) {
                SectionTitle(otoTitleT())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasOto) Btn(fromOtoMissingT(), enabled = infs < wavs.size) { ed.niaoFromOto(wavs, keep = true); close() }
                    if (hasOto) Btn(fromOtoAllT()) { ed.niaoFromOto(wavs, keep = false); close() }
                    if (infs > 0) Btn(toOtoT()) { ed.niaoToOto(wavs); close() }
                    if (hasOto) Btn(toLabT()) { ed.otoToLab(wavs); close() }
                }
                Text(otoHintT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                if (hasOto) Text(labHintT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
