package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.Item
import mlabeler.core.io.Paths

private val titleT = L("Texts of the files", "Тексты файлов")
private val aboutT = L(
    "What is sung in each file, from the .txt next to it. Check and correct the texts before aligning; an empty one can be recognised with Whisper. Saving writes the .txt files.",
    "Что поётся в каждом файле — из .txt рядом с ним. Проверьте и поправьте тексты перед выравниванием; пустой можно распознать через Whisper. При сохранении записываются .txt.",
)
private val recogniseT = L("Recognise", "Распознать")
private val recogniseEmptyT = L("Recognise the empty ones ({0})", "Распознать пустые ({0})")
private val stopT = L("Stop", "Остановить")
private val listeningT = L("Recognising {0}…", "Распознаётся {0}…")
private val emptyT = L("no text", "нет текста")
private val savedT = L("Texts saved: {0}", "Сохранено текстов: {0}")

private class TextRow(val item: Item, val original: String, text: String) {
    var text by mutableStateOf(text)
    var busy by mutableStateOf(false)
}

/**
 * The texts of [files] (the .txt next to each) to check before a batch alignment: edited by hand or recognised with
 * Whisper ([language]), written back on Save.
 */
@Composable
fun TextsDialog(ed: EditorState, files: List<Item>, language: String?, onClose: () -> Unit) {
    val app = ed.app
    val c = T.c
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TextRow>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var busyName by remember { mutableStateOf<String?>(null) }
    fun txtPath(f: Item) = Paths.join(Paths.parent(f.audioPath), Paths.stem(f.audioPath) + ".txt")
    LaunchedEffect(files) {
        val loaded = withContext(Dispatchers.Default) {
            files.map { f ->
                val t = runCatching { ed.workspace.fs.read(txtPath(f)).decodeToString() }.getOrNull().orEmpty().trim()
                TextRow(f, t, t)
            }
        }
        rows.clear(); rows.addAll(loaded)
    }
    suspend fun readAudio(f: Item): Audio = withContext(Dispatchers.Default) {
        val bytes = ed.workspace.fs.read(f.audioPath)
        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
    }
    suspend fun recognise(r: TextRow) {
        r.busy = true
        busyName = r.item.name
        val client = app.toolkit.client()
        var serverJob: String? = null
        try {
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            val a = readAudio(r.item)
            val id = client.upload(Paths.stem(r.item.audioPath) + ".wav", withContext(Dispatchers.Default) { Wav.encode16(a) })
            val j = client.transcribe(id, language, null, app.settings.toolkit.whisperModel)
            serverJob = j
            val res = client.await(j) { _, _ -> }
            serverJob = null
            val item = res["items"]?.jsonArray?.firstOrNull()?.jsonObject
            if ((item?.get("ok") as? JsonPrimitive)?.content == "false") throw mlabeler.app.toolkit.ToolkitException((item["error"] as? JsonPrimitive)?.content ?: "failed")
            r.text = (item?.get("text") as? JsonPrimitive)?.content?.trim().orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            serverJob?.let { withContext(kotlinx.coroutines.NonCancellable) { client.cancel(it) } }
            throw e
        } catch (e: Exception) {
            app.message(r.item.name + ": " + (e.message ?: e.toString()), error = true)
        } finally {
            r.busy = false
            busyName = null
        }
    }
    fun run(which: List<TextRow>) {
        job?.cancel()
        job = scope.launch { for (r in which) recognise(r) }.also { it.invokeOnCompletion { job = null } }
    }
    Overlay({ job?.cancel(); onClose() }, 760) {
        DialogContent(footer = {
            val empty = rows.filter { it.text.isBlank() }
            if (job != null) Btn(stopT()) { job?.cancel() }
            else Btn(recogniseEmptyT.format(empty.size), enabled = empty.isNotEmpty()) { run(empty) }
            Btn(S.cancel()) { job?.cancel(); onClose() }
            Btn(S.save(), primary = true, enabled = job == null) {
                var n = 0
                for (r in rows) {
                    if (r.text.trim() == r.original || r.text.isBlank()) continue
                    runCatching { ed.workspace.fs.write(txtPath(r.item), (r.text.trim() + "\n").encodeToByteArray()) }
                        .onSuccess { n++ }.onFailure { app.message(r.item.name + ": " + (it.message ?: it.toString()), error = true) }
                }
                app.message(savedT.format(n))
                onClose()
            }
        }) {
            Text(titleT(), color = c.text, fontSize = 17.sp)
            Text(aboutT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            busyName?.let { Text(listeningT.format(it), color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Column(Modifier.padding(top = 10.dp).heightIn(max = 460.dp).scrollWithHint()) {
                for (r in rows) {
                    Row(Modifier.fillMaxWidth().background(if (r.busy) c.accent.copy(alpha = 0.12f) else c.panel).padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.item.name, color = c.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(150.dp))
                        Btn("▶") { scope.launch { runCatching { ed.playBuffer(readAudio(r.item)) } } }
                        Field(r.text, { r.text = it }, Modifier.weight(1f), placeholder = emptyT())
                        Btn(recogniseT(), enabled = job == null && !r.busy) { run(listOf(r)) }
                    }
                }
            }
        }
    }
}
