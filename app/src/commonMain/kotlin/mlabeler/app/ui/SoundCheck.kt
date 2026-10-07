package mlabeler.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.theme.T
import mlabeler.core.audio.SoundCheck
import mlabeler.core.audio.Wav
import kotlin.math.roundToInt

private val title = L("Check the recordings", "Проверка звука")
private val about = L("Looks for what spoils training: clipping, too quiet takes, noise in the pauses, an offset of the zero line, long silence at the ends, a different sample rate, stereo. Nothing is changed.",
    "Ищет то, что портит обучение: перегруз, слишком тихие дубли, шум в паузах, смещение нуля, длинную тишину по краям, другую частоту дискретизации, стерео. Ничего не меняет.")
private val checking = L("Checking {0} of {1}…", "Проверка: {0} из {1}…")
private val allClean = L("Nothing found in {0} recordings", "В {0} записях ничего не найдено")
private val found = L("Found something in {0} of {1} recordings. Click to open the place.", "Есть замечания в {0} из {1} записей. Нажмите, чтобы открыть место.")
private val clipping = L("clipping ×{0}", "перегруз ×{0}")
private val quiet = L("quiet: peak {0} dB", "тихо: пик {0} дБ")
private val noisy = L("noise in pauses {0} dB", "шум в паузах {0} дБ")
private val dc = L("zero line offset", "смещение нуля")
private val silenceStart = L("silence at the start {0}", "тишина в начале {0}")
private val silenceEnd = L("silence at the end {0}", "тишина в конце {0}")
private val otherRate = L("{0} Hz (most are {1} Hz)", "{0} Гц (у большинства {1} Гц)")
private val lowRate = L("{0} Hz: low for singing", "{0} Гц: мало для пения")
private val stereo = L("stereo", "стерео")
private val unreadable = L("can't be read: {0}", "не читается: {0}")
private val again = L("Check again", "Проверить снова")

private class Row1(val index: Int, val name: String, val rate: Int, val channels: Int, val duration: Double, val report: SoundCheck.Report?, val error: String?)

/** Checks every recording of the folder for problems of the sound itself and lists them by file. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundCheckDialog(app: AppState, ed: EditorState) {
    val c = T.c
    fun close() { app.showSoundCheck = false; ed.requestFocus() }
    var rows by remember { mutableStateOf<List<Row1>?>(null) }
    var progress by remember { mutableStateOf(0) }
    var run by remember { mutableStateOf(0) }
    val items = ed.items
    LaunchedEffect(run) {
        rows = null
        rows = withContext(Dispatchers.Default) {
            items.mapIndexed { i, item ->
                progress = i + 1
                runCatching {
                    val bytes = ed.workspace.fs.read(item.audioPath)
                    val a = if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(item.audioPath) ?: error(S.unsupportedAudio())
                    Row1(i, item.name, a.sampleRate, a.channels, a.duration, SoundCheck.analyze(a), null)
                }.getOrElse { Row1(i, item.name, 0, 0, 0.0, null, it.message ?: it.toString()) }
            }
        }
    }
    Overlay({ close() }, 720) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title(), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.close, S.close()) { close() }
            }
            Text(about(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            val list = rows
            if (list == null) {
                Text(checking.format(progress, items.size), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                return@Column
            }
            val usual = list.filter { it.rate > 0 }.groupingBy { it.rate }.eachCount().maxByOrNull { it.value }?.key ?: 0
            fun notes(r: Row1): List<Pair<String, Double>> = buildList {
                if (r.error != null) { add(unreadable.format(r.error) to 0.0); return@buildList }
                if (r.rate != usual && usual > 0) add(otherRate.format(r.rate, usual) to 0.0)
                else if (r.rate in 1 until 44100) add(lowRate.format(r.rate) to 0.0)
                if (r.channels > 1) add(stereo() to 0.0)
                for (f in r.report?.findings.orEmpty()) add(
                    when (f.kind) {
                        SoundCheck.Kind.Clipping -> clipping.format(f.value.roundToInt())
                        SoundCheck.Kind.Quiet -> quiet.format(f.value.roundToInt())
                        SoundCheck.Kind.Noisy -> noisy.format(f.value.roundToInt())
                        SoundCheck.Kind.DcOffset -> dc()
                        SoundCheck.Kind.SilenceAtStart -> silenceStart.format(formatTime(f.value, precise = false))
                        SoundCheck.Kind.SilenceAtEnd -> silenceEnd.format(formatTime(f.value, precise = false))
                    } to f.at,
                )
            }
            val withNotes = list.map { it to notes(it) }.filter { it.second.isNotEmpty() }
            Text(if (withNotes.isEmpty()) allClean.format(list.size) else found.format(withNotes.size, list.size),
                color = if (withNotes.isEmpty()) c.ok else c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            for ((r, ns) in withNotes) {
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(r.name, color = c.text, fontSize = 13.sp, modifier = Modifier.clickable { ed.open(r.index); close() })
                    FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((text, at) in ns) Chip(text, false) {
                            if (r.duration > 0) ed.openRange(r.index, (at - 0.25).coerceAtLeast(0.0), (at + 0.75).coerceAtMost(r.duration)) else ed.open(r.index)
                            close()
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp)) { Btn(again()) { run++ } }
        }
    }
}
