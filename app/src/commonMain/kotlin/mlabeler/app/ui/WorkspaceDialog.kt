package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.Mode
import mlabeler.app.theme.T
import mlabeler.core.format.LabelFormat

private val folderSettings = L("Folder settings", "Настройки папки")
private val whatLabelled = L("What is labelled here", "Что здесь размечается")
private val kindLabels = L("Phonemes and words", "Фонемы и слова")
private val kindLabelsHint = L("Tiers of intervals: .lab (HTK, NNSVS, DiffSinger), TextGrid, Audacity labels.",
    "Слои интервалов: .lab (HTK, NNSVS, DiffSinger), TextGrid, метки Audacity.")
private val kindOto = L("UTAU voicebank (oto.ini)", "Голосовой банк UTAU (oto.ini)")
private val kindOtoHint = L("Entries of oto.ini in every folder with recordings.", "Записи oto.ini в каждой папке с записями.")
private val labelFolders = L("Extra folders with labels (relative, one per line)", "Дополнительные папки с разметкой (относительно, по одной в строке)")
private val rescan = L("Apply and rescan", "Применить и пересканировать")

@Composable
fun kindTitle(m: Mode) = when (m) {
    Mode.Labels -> kindLabels()
    Mode.Oto -> "oto.ini"
}

@Composable
fun WorkspaceDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    fun close() { app.showWorkspace = false; ed.requestFocus() }
    Overlay({ close() }, 600) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(folderSettings(), color = c.text, fontSize = 17.sp)
            Text(ed.workspace.root, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            SectionTitle(whatLabelled())
            for ((m, title, hint) in listOf(Triple(Mode.Labels, kindLabels(), kindLabelsHint()), Triple(Mode.Oto, kindOto(), kindOtoHint()))) {
                val sel = ed.mode == m
                val shape = RoundedCornerShape(c.radius)
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
                        .border(if (sel) 2.dp else c.borderWidth, if (sel) c.accent else c.border, shape)
                        .background(if (sel) c.accent.copy(alpha = 0.08f) else c.panel)
                        .clickable { ed.setKind(m) }.padding(12.dp),
                ) {
                    Text(title, color = c.text, fontSize = 14.sp)
                    Text(hint, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (ed.mode == Mode.Labels) {
                SectionTitle(S.newFilesFormat())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in LabelFormat.entries.filter { it.standalone }) Chip(f.title, ed.workspace.state.defaultFormat == f) {
                        ed.workspace.updateState { it.copy(defaultFormat = f) }
                        ed.bumpMarks()
                    }
                }
                SectionTitle(labelFolders())
                var folders by remember { mutableStateOf(ed.workspace.state.labelFolders.joinToString("\n")) }
                androidx.compose.foundation.text.BasicTextField(
                    folders, { folders = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(c.radius)).background(c.bg)
                        .border(c.borderWidth, c.border, RoundedCornerShape(c.radius)).padding(10.dp),
                    minLines = 3,
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Btn(rescan()) {
                        ed.workspace.updateState { it.copy(labelFolders = folders.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }) }
                        ed.rescan()
                    }
                }
            }
        }
    }
}
