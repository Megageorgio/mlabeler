package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.toolkit.ToolkitClient
import mlabeler.app.toolkit.ToolkitManager

private object StorageTitles {
    val title = L("Disk space", "Место на диске")
    val hint = L("What the toolkit keeps on this computer. A removed model or engine is downloaded again when a job needs it.",
        "Что тулкит хранит на этом компьютере. Удалённая модель или движок скачаются снова, когда понадобятся.")
    val total = L("In all: {0}", "Всего: {0}")
    val models = L("Models", "Модели")
    val engines = L("Engines (Python environments)", "Движки (окружения Python)")
    val leftovers = L("Leftovers of jobs: {0}", "Остатки задач: {0}")
    val leftoversHint = L("Uploaded recordings, results and unfinished downloads. The toolkit removes those older than {0} days by itself when it starts.",
        "Загруженные записи, результаты и незавершённые скачивания. Тулкит сам удаляет те, что старше {0} дн., при запуске.")
    val engineDownloads = L("Downloaded by the engines themselves (Whisper and others): {0}", "Скачано самими движками (Whisper и другие): {0}")
    val clean = L("Clean up", "Очистить")
    val cleaned = L("Freed {0}", "Освобождено {0}")
    val remove = L("Remove", "Удалить")
    val sure = L("Click again to remove", "Нажмите ещё раз")
    val refresh = L("Count again", "Пересчитать")
    val counting = L("Counting…", "Подсчёт…")
    val notRunning = L("The toolkit has to be running to show this.", "Чтобы это показать, тулкит должен быть запущен.")
    val older = L("This toolkit can't count its disk space yet; update it.", "Эта версия тулкита не поддерживает подсчёт места на диске; обновите тулкит.")
}

/** Sizes of the toolkit's models, engines and leftovers, with buttons to remove them. */
@Composable
fun ToolkitStorageSection(app: AppState) {
    val c = T.c
    val tk = app.toolkit
    val scope = rememberCoroutineScope()
    val ready = tk.status == ToolkitManager.Status.Ready
    var storage by remember { mutableStateOf<ToolkitClient.Storage?>(null) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(ready, version, tk.modelsVersion) {
        if (!ready) return@LaunchedEffect
        loading = true
        try {
            val client = tk.client()
            val s = client.storage()
            unsupported = s == null
            storage = s
            names = runCatching { client.installedModels().associate { it.id to it.name } }.getOrDefault(emptyMap())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            note = false to (e.message ?: e.toString())
        } finally {
            loading = false
        }
    }
    // removing asks for a second click on the same button; [now] skips that
    fun act(key: String, now: Boolean = false, block: suspend () -> String?) {
        if (!now && confirm != key) { confirm = key; return }
        confirm = null
        scope.launch {
            try {
                block()?.let { note = true to it }
                tk.modelsVersion++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                note = false to (e.message ?: e.toString())
            }
            version++
        }
    }
    Fold(StorageTitles.title()) {
        Text(StorageTitles.hint(), color = c.muted, fontSize = 12.sp)
        when {
            !ready -> Text(StorageTitles.notRunning(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            unsupported -> Text(StorageTitles.older(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            storage == null -> Text(StorageTitles.counting(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        val s = storage
        if (ready && s != null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(StorageTitles.total.format(byteSize(s.total)), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (loading) Text(StorageTitles.counting(), color = c.muted, fontSize = 12.sp)
                else Btn(StorageTitles.refresh()) { version++ }
            }
            Text(s.home, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(StorageTitles.leftovers.format(byteSize(s.leftovers)), color = c.text, fontSize = 13.sp)
                    s.keepDays?.takeIf { it > 0 }?.let { Text(StorageTitles.leftoversHint.format(it), color = c.muted, fontSize = 11.sp) }
                }
                Btn(StorageTitles.clean(), enabled = s.leftovers > 0) {
                    act("cleanup", now = true) { StorageTitles.cleaned.format(byteSize(tk.client().cleanupStorage())) }
                }
            }
            if (s.engineDownloads > 0) Text(StorageTitles.engineDownloads.format(byteSize(s.engineDownloads)), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            if (s.models.isNotEmpty()) SizeList(StorageTitles.models(), s.models.map { (id, b) -> Triple("model:$id", names[id]?.takeIf { it.isNotBlank() } ?: id, b) }, confirm) { key ->
                act(key) { tk.client().removeModel(key.removePrefix("model:")); null }
            }
            if (s.engines.isNotEmpty()) SizeList(StorageTitles.engines(), s.engines.map { (id, b) -> Triple("engine:$id", id, b) }, confirm) { key ->
                act(key) { tk.client().removeEngine(key.removePrefix("engine:")); null }
            }
        }
        note?.let { (ok, text) -> Text(text, color = if (ok) c.ok else c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** A heading and one row per entry: its name, size and a remove button that asks for a second click. */
@Composable
private fun SizeList(title: String, rows: List<Triple<String, String, Long>>, confirm: String?, onRemove: (String) -> Unit) {
    val c = T.c
    Text(title, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    val biggest = rows.maxOfOrNull { it.third }?.coerceAtLeast(1L) ?: 1L
    for ((key, name, bytes) in rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // a bar for comparing sizes at a glance
            Box(Modifier.padding(horizontal = 4.dp).width(64.dp).height(4.dp).background(c.border)) {
                Box(Modifier.fillMaxWidth((bytes.toFloat() / biggest).coerceIn(0.02f, 1f)).height(4.dp).background(c.accent))
            }
            Text(byteSize(bytes), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
            Btn(if (confirm == key) StorageTitles.sure() else StorageTitles.remove()) { onRemove(key) }
        }
    }
}

private val units = L("B KB MB GB TB", "Б КБ МБ ГБ ТБ")

/** "1.4 GB", "350 MB". */
internal fun byteSize(bytes: Long): String {
    val names = units().split(' ')
    var v = bytes.toDouble()
    var k = 0
    while (v >= 1024 && k < names.lastIndex) { v /= 1024; k++ }
    val n = if (v >= 100 || k == 0) v.toLong().toString() else ((v * 10).toLong() / 10.0).toString()
    return "$n ${names[k]}"
}
