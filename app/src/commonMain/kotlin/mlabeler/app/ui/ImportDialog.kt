package mlabeler.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import mlabeler.app.theme.T
import mlabeler.core.format.HtkLab
import mlabeler.core.format.OtoIni
import mlabeler.core.format.VLabelerProject
import mlabeler.core.io.ItemMarks
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace
import mlabeler.core.io.encodeText

val importTitle = L("Import a vLabeler project", "Импорт проекта vLabeler")
private val hint = L("Projects (.lbp) found in this folder and the one above it. Labels and oto.ini are written next to the recordings (old files go to .mlabeler/backup), done/star/tag notes are kept.",
    "Проекты (.lbp) в этой папке и в папке выше. Разметка и oto.ini записываются рядом с записями (старые файлы — в .mlabeler/backup), отметки «готово», «звезда» и метки сохраняются.")
private val noneFound = L("No .lbp files here. Put the project file into this folder.", "Здесь нет файлов .lbp. Поместите файл проекта в эту папку.")
private val imported = L("Imported: {0} files", "Импортировано файлов: {0}")
private val importBtn = L("Import", "Импортировать")

@Composable
fun ImportDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    fun close() { app.showImport = false; ed.requestFocus() }
    val found = remember {
        listOf(ed.workspace.root, Paths.parent(ed.workspace.root)).distinct().flatMap { d ->
            runCatching { PlatformFs.list(d) }.getOrDefault(emptyList()).filter { Paths.ext(it) == "lbp" }
        }
    }
    var chosen by remember { mutableStateOf(found.firstOrNull()) }
    Overlay({ close() }, 600) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            Btn(importBtn(), primary = true, enabled = chosen != null) {
                val path = chosen ?: return@Btn
                try {
                    val n = importProject(app, path)
                    app.message(imported.format(n))
                } catch (e: Exception) {
                    app.message(e.message ?: e.toString(), error = true)
                }
                close()
            }
        }) {
            Text(importTitle(), color = c.text, fontSize = 17.sp)
            Text(hint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (found.isEmpty()) Text(noneFound(), color = c.muted, fontSize = 13.sp)
            for (f in found) {
                Row(Modifier.fillMaxWidth().clickable { chosen = f }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(Paths.name(f), chosen == f) { chosen = f }
                    Text("  " + Paths.parent(f), color = c.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun backup(ws: Workspace, path: String) {
    if (!PlatformFs.exists(path)) return
    val name = ws.relative(path).replace('/', '_').replace('\\', '_') + "." + Workspace.timestamp()
    runCatching { PlatformFs.copy(path, Paths.join(Paths.join(ws.metaDir, "backup"), name)) }
}

private fun importProject(app: AppState, path: String): Int {
    val ed = app.editor ?: return 0
    val ws = ed.workspace
    val p = VLabelerProject.read(mlabeler.core.io.decodeGuess(PlatformFs.read(path), "UTF-8").first)
    var count = 0
    for ((dir, entries) in p.oto) {
        val target = Paths.join(dir, "oto.ini")
        backup(ws, target)
        PlatformFs.write(target, encodeText(OtoIni.write(entries), "Shift_JIS"))
        count += entries.map { it.sample }.distinct().size
    }
    for ((dir, docs) in p.labels) for ((sample, doc) in docs) {
        val target = Paths.join(dir, Paths.stem(sample) + ".lab")
        backup(ws, target)
        PlatformFs.write(target, HtkLab.write(doc).encodeToByteArray())
        count++
    }
    // notes: per oto entry, or per file for labels (done when every entry was done)
    ws.updateState { st ->
        val items = st.items.toMutableMap()
        for ((key, mk) in p.marks) {
            val (dir, sample, name) = key.split('|', limit = 3)
            val rel = ws.relative(dir)
            if (p.oto.isNotEmpty()) {
                val k = "oto:$rel/$sample|$name"
                items[k] = (items[k] ?: mlabeler.core.io.ItemState()).copy(marks = mk)
            }
        }
        if (p.labels.isNotEmpty()) {
            val byFile = p.marks.entries.groupBy { it.key.substringBeforeLast('|') }
            for ((fileKey, list) in byFile) {
                val (dir, sample) = fileKey.split('|', limit = 2)
                val id = ws.relative(Paths.join(dir, sample))
                val all = list.map { it.value }
                items[id] = (items[id] ?: mlabeler.core.io.ItemState()).copy(
                    marks = ItemMarks(done = all.all { it.done }, star = all.any { it.star }, tag = all.firstOrNull { it.tag.isNotEmpty() }?.tag ?: ""),
                )
            }
        }
        st.copy(items = items)
    }
    ed.oto.invalidate()
    ed.rescan()
    ed.bumpMarks()
    return count
}
