package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.app.theme.T
import mlabeler.core.format.Ust
import mlabeler.core.io.Paths
import mlabeler.core.io.decodeGuess

private val titleT = L("UTAU sequence (.ust)", "Партия UTAU (.ust)")
private val fileT = L("Sequence", "Партия")
private val noneT = L("No .ust files in the folder", "В папке нет файлов .ust")
private val otherT = L("Another file…", "Другой файл…")
private val notesAboutT = L(
    "Its notes (with the pitch) and lyrics become the notes and the words of the open recording, starting at its beginning; rests become SP. The phonemes can then be made from the words (Tools → Words and phonemes).",
    "Её ноты (с высотой) и слова становятся нотами и словами открытой записи с её начала; паузы становятся SP. Фонемы затем можно получить из слов (Инструменты → Слова и фонемы).",
)
private val notesBtnT = L("Notes and words from it", "Ноты и слова из неё")
private val markAboutT = L("The oto entries whose alias is a lyric of the sequence get the mark: what the song needs from the voicebank.",
    "Записи oto, чей псевдоним встречается в тексте партии, получают отметку — то, что песне нужно от банка.")
private val markHowT = L("Mark them", "Как отметить")
private val starT = L("Star", "Звезда")
private val doneT = L("Done", "Готово")
private val tagT = L("Tag \"ust\"", "Тег «ust»")
private val markBtnT = L("Mark the entries", "Отметить записи")
private val markedT = L("Marked {0} entries; {1} lyrics have no entry: {2}", "Отмечено записей: {0}; псевдонимов без записи: {1}: {2}")
private val notesDoneT = L("Notes and words taken from {0}: {1} notes", "Ноты и слова взяты из {0}: нот {1}")

@Composable
fun UstDialog(app: AppState, ed: EditorState) {
    val c = T.c
    val scope = rememberCoroutineScope()
    fun close() { app.showUst = false; ed.requestFocus() }
    val oto = ed.mode == Mode.Oto
    val folder = ed.item?.let { Paths.parent(it.audioPath) } ?: ed.workspace.root
    val files = remember(folder) {
        runCatching { ed.workspace.fs.list(folder) }.getOrDefault(emptyList()).filter { Paths.ext(it).equals("ust", true) }.sorted()
    }
    var picked by remember {
        mutableStateOf(ed.item?.let { i -> files.firstOrNull { Paths.stem(it).equals(Paths.stem(i.audioPath), true) } } ?: files.firstOrNull())
    }
    var mark by remember { mutableStateOf("star") }
    fun read(path: String) = decodeGuess(ed.workspace.fs.read(path), "Shift_JIS").first
    Overlay({ close() }, 560) {
        DialogContent(footer = {
            Btn(S.cancel()) { close() }
            val p = picked
            if (oto) Btn(markBtnT(), primary = true, enabled = p != null) {
                if (p == null) return@Btn
                runCatching {
                    val aliases = Ust.aliases(read(p))
                    val (n, missing) = ed.oto.markUsed(aliases, mark)
                    app.message(markedT.format(n, missing.size, missing.take(20).joinToString(" ")))
                }.onFailure { app.message(it.message ?: it.toString(), error = true) }
                close()
            } else Btn(notesBtnT(), primary = true, enabled = p != null && ed.audio != null) {
                if (p == null) return@Btn
                runCatching {
                    val (notes, words) = Ust.tiers(read(p), 0.0, ed.duration)
                    ed.updateDocShowingChanges { d ->
                        var out = d
                        val nk = out.tiers.indexOfFirst { it is mlabeler.core.model.NoteTier }
                        out = if (nk >= 0) out.replace(nk, notes) else out.copy(tiers = out.tiers + notes)
                        val wk = out.wordTierIndex()
                        out = if (wk >= 0) out.replace(wk, words.copy(name = (out.tiers[wk] as mlabeler.core.model.IntervalTier).name))
                            else out.copy(tiers = listOf(words) + out.tiers)
                        mlabeler.core.edit.Edits.fitToDuration(out, ed.duration)
                    }
                    app.message(notesDoneT.format(Paths.name(p), notes.notes.count { it.pitch != null }))
                }.onFailure { app.message(it.message ?: it.toString(), error = true) }
                close()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(if (oto) markAboutT() else notesAboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            SectionTitle(fileT())
            if (files.isEmpty() && picked == null) Text(noneT(), color = c.muted, fontSize = 13.sp)
            Column(Modifier.heightIn(max = 200.dp).scrollWithHint()) {
                for (f in (files + listOfNotNull(picked)).distinct()) {
                    Text(Paths.name(f), color = c.text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()
                        .background(if (f == picked) c.accent.copy(alpha = 0.14f) else c.panel).clickable { picked = f }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (mlabeler.app.Platform.hasNativeFolderPicker) Text(otherT(), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable {
                scope.launch {
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mlabeler.app.Platform.pickFileNative(titleT(), listOf("ust"), folder) }
                    if (p != null) picked = p
                }
            })
            if (oto) {
                SectionTitle(markHowT())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(starT(), mark == "star") { mark = "star" }
                    Chip(doneT(), mark == "done") { mark = "done" }
                    Chip(tagT(), mark == "tag") { mark = "tag" }
                }
            }
        }
    }
}
