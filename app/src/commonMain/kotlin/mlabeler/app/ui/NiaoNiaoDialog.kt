package mlabeler.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.niaoAutoMarks
import mlabeler.app.state.niaoFiles
import mlabeler.app.state.niaoMeasure
import mlabeler.app.state.niaoPack
import mlabeler.app.state.unpackNiaoBank
import mlabeler.app.theme.T
import mlabeler.core.format.LabelFormat
import mlabeler.core.io.Paths

private val titleT = L("NiaoNiao voicebank", "Банк NiaoNiao")
private val aboutT = L(
    "Each recording (44.1 kHz WAV) has an .inf with four marks, edited like boundaries on the \"niaoniao\" tier: the start, the end of the consonant, the start of the decay and the end. The pitch and the levels are measured when the bank is packed.",
    "У каждой записи (WAV 44,1 кГц) есть .inf с четырьмя метками, они правятся как границы на слое «niaoniao»: начало, конец согласной, начало затухания и конец. Высота и громкость измеряются при упаковке банка.",
)
private val filesT = L("Recordings: {0}, with marks: {1}", "Записей: {0}, с метками: {1}")
private val autoTitleT = L("Marks", "Метки")
private val autoMissingT = L("Place the missing marks", "Расставить недостающие метки")
private val autoAllT = L("Place all marks again", "Расставить все метки заново")
private val autoHintT = L("From the loudness: the sound where it is louder than 30 dB below its loudest part, the vowel where it reaches half of it. Check the consonants by ear.",
    "По громкости: звук там, где он громче, чем на 30 дБ ниже самого громкого места, гласная — где громкость доходит до половины. Согласные проверьте на слух.")
private val measureBtnT = L("Measure the pitch and levels again", "Измерить высоту и громкость заново")
private val packTitleT = L("Pack", "Упаковка")
private val outT = L("Folder of the bank", "Папка банка")
private val versionT = L("Version", "Версия")
private val measureT = L("Measure the pitch and levels while packing", "Измерять высоту и громкость при упаковке")
private val packBtnT = L("Pack into voice.d and inf.d", "Упаковать в voice.d и inf.d")
private val extrasT = L("readme.txt, charactor.txt (the first line is the name, colons are half-width) and head.png (100×100, becomes head.d) are taken from the folder of the recordings or the one above.",
    "readme.txt, charactor.txt (первая строка — имя, двоеточия полуширинные) и head.png (100×100, станет head.d) берутся из папки записей или папки выше.")
private val unpackTitleT = L("Unpack a bank", "Распаковка банка")
private val unpackHintT = L("A folder with voice.d and inf.d becomes a source folder: a .wav and an .inf per sound, opened here.",
    "Папка с voice.d и inf.d превращается в исходники: .wav и .inf на каждый звук, они откроются здесь.")
private val unpackBtnT = L("Choose the bank folder…", "Выбрать папку банка…")
private val unpackedT = L("Unpacked {0} sounds into {1}", "Распаковано звуков: {0} в {1}")
private val noBankT = L("No voice.d and inf.d in this folder", "В этой папке нет voice.d и inf.d")

@Composable
fun NiaoNiaoDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val scope = rememberCoroutineScope()
    fun close() { app.showNiao = false; ed.requestFocus() }
    val files = remember(ed.items) { ed.niaoFiles() }
    val marked = files.count { it.labelFormat == LabelFormat.Inf }
    val dir = files.firstOrNull()?.let { Paths.parent(it.audioPath) } ?: ed.workspace.root
    var out by remember { mutableStateOf(Paths.join(Paths.parent(dir), Paths.name(dir) + "_bank")) }
    var version by remember { mutableStateOf("1") }
    var measure by remember { mutableStateOf(true) }
    Overlay({ close() }, 620) {
        DialogContent(footer = { Btn(S.close()) { close() } }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text(filesT.format(files.size, marked), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

            SectionTitle(autoTitleT())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(autoMissingT(), enabled = marked < files.size) { ed.niaoAutoMarks(files, keep = true); close() }
                Btn(autoAllT(), enabled = files.isNotEmpty()) { ed.niaoAutoMarks(files, keep = false); close() }
                Btn(measureBtnT(), enabled = marked > 0) { ed.niaoMeasure(files); close() }
            }
            Text(autoHintT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

            SectionTitle(packTitleT())
            Text(outT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(out, { out = it }, Modifier.weight(1f))
                Btn("…") { app.pickFolder(outT()) { out = it } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(versionT(), color = c.text, fontSize = 13.sp)
                Field(version, { v -> version = v.filter { it.isDigit() }.take(3) }, Modifier.width(70.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { measure = !measure }, verticalAlignment = Alignment.CenterVertically) {
                Text(measureT(), color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Toggle(measure, { measure = it })
            }
            Text(extrasT(), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp)) {
                Btn(packBtnT(), primary = true, enabled = marked > 0 && out.isNotBlank()) {
                    ed.niaoPack(files, out.trim(), measure, version.toIntOrNull() ?: 1); close()
                }
            }

            SectionTitle(unpackTitleT())
            Text(unpackHintT(), color = c.muted, fontSize = 11.sp)
            Row(Modifier.padding(top = 6.dp)) {
                Btn(unpackBtnT()) {
                    app.pickFolder(unpackTitleT()) { bank ->
                        if (!mlabeler.core.io.PlatformFs.exists(Paths.join(bank, "voice.d")) || !mlabeler.core.io.PlatformFs.exists(Paths.join(bank, "inf.d"))) {
                            app.message(noBankT(), error = true); return@pickFolder
                        }
                        val target = Paths.join(Paths.parent(bank), Paths.name(bank) + "_src")
                        scope.launch {
                            try {
                                val n = unpackNiaoBank(bank, target)
                                app.message(unpackedT.format(n, target))
                                close()
                                app.openFolder(target)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                app.message(e.message ?: e.toString(), error = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
