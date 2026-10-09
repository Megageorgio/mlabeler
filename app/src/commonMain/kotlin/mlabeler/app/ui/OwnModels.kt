package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.LanguageNames
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.toolkit.ToolkitClient

private val title = L("Your own models", "Свои модели")
private val hint = L(
    "A model you trained or downloaded, without publishing it anywhere. Pick its checkpoint (.pt, .ckpt): the config, phoneme list and language list next to it are taken too. A folder or an archive works as well. The file has to be on the computer where the toolkit runs.",
    "Модель, которую вы обучили или скачали, — без публикации. Выберите её чекпоинт (.pt, .ckpt): конфиг, список фонем и список языков рядом с ним также будут добавлены. Поддерживаются также папка или архив. Файл должен находиться на компьютере, где работает тулкит.",
)
private val pathT = L("Checkpoint, folder or archive", "Чекпоинт, папка или архив")
private val fileBtn = L("File…", "Файл…")
private val folderBtn = L("Folder…", "Папка…")
private val kindT = L("What it does", "Что делает")
private val kindWfl = L("Recognises phonemes without text (WFL-ASR)", "Распознаёт фонемы без текста (WFL-ASR)")
private val kindSofa = L("Aligns to text (SOFA)", "Выравнивает по тексту (SOFA)")
private val kindHfa = L("Aligns to text (HubertFA)", "Выравнивает по тексту (HubertFA)")
private val kindRefiner = L("Refines boundaries (mRefinerModel)", "Уточняет границы (mRefinerModel)")
private val langT = L("Language", "Язык")
private val langHint = L("code, e.g. ru", "код, например ru")
private val nameT = L("Name in the list", "Название в списке")
private val addBtn = L("Add the model", "Добавить модель")
private val adding = L("Adding… (the checkpoint is copied)", "Добавление… (чекпоинт копируется)")
private val added = L("Added: {0}. It is in the Autolabel window under its language.", "Добавлено: {0}. Модель доступна в окне авторазметки в разделе своего языка.")
private val addedRefiner = L("Added: {0}. It is in Tools → Refine boundaries.", "Добавлено: {0}. Модель доступна в «Инструменты → Уточнить границы».")
private val yours = L("Added before", "Добавленные раньше")
private val removeBtn = L("Remove", "Удалить")
private val notReady = L("The toolkit has to be running to add models.", "Чтобы добавлять модели, тулкит должен быть запущен.")

@Composable
fun OwnModelsSection(app: AppState) {
    val c = T.c
    val tk = app.toolkit
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf("wfl_asr") }
    var lang by remember { mutableStateOf("ru") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var mine by remember { mutableStateOf<List<ToolkitClient.InstalledModel>>(emptyList()) }
    val ready = tk.status == mlabeler.app.toolkit.ToolkitManager.Status.Ready
    LaunchedEffect(ready, tk.modelsVersion) {
        if (ready) mine = runCatching { tk.client().installedModels().filter { it.source == "import" } }.getOrDefault(emptyList())
    }
    Fold(title()) {
        Text(hint(), color = c.muted, fontSize = 12.sp)
        Text(pathT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(path, { path = it.trim().trim('"') }, Modifier.weight(1f), placeholder = "D:\\models\\model.pt")
            if (Platform.hasNativeFolderPicker) {
                Btn(fileBtn()) {
                    scope.launch {
                        val p = withContext(Dispatchers.Default) { Platform.pickFileNative(pathT(), listOf("pt", "pth", "ckpt", "safetensors", "onnx", "zip", "rar", "7z")) }
                        if (p != null) path = p
                    }
                }
                Btn(folderBtn()) {
                    scope.launch {
                        val p = withContext(Dispatchers.Default) { Platform.pickFolderNative(pathT()) }
                        if (p != null) path = p
                    }
                }
            }
        }
        Text(kindT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(kindWfl(), engine == "wfl_asr") { engine = "wfl_asr" }
            Chip(kindSofa(), engine == "sofa") { engine = "sofa" }
            Chip(kindHfa(), engine == "hubertfa") { engine = "hubertfa" }
            Chip(kindRefiner(), engine == "refiner") { engine = "refiner" }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(langT() + (LanguageNames.of(lang, "").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Field(lang, { lang = it.trim().lowercase() }, Modifier.fillMaxWidth(), placeholder = langHint())
            }
            Column(Modifier.weight(2f)) {
                Text(nameT(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Field(name, { name = it }, Modifier.fillMaxWidth(), placeholder = path.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.'))
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(addBtn(), primary = true, enabled = path.isNotBlank() && !busy) {
                busy = true
                result = null
                scope.launch {
                    try {
                        if (!tk.ensure()) error(notReady())
                        val file = path.substringAfterLast('\\').substringAfterLast('/')
                        val stem = file.substringBeforeLast('.')
                        // a checkpoint of a training run is named by its folder too (runs/ru/model_step5000.pt → ru-model_step5000)
                        val parent = path.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/')
                        val id = name.ifBlank { if (parent.isNotEmpty()) "$parent-$stem" else stem }
                            .lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
                        val ids = tk.client().importModel(engine, path, id, name.ifBlank { null } ?: "$parent/$stem".trimStart('/'),
                            lang.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() })
                        result = true to (if (engine == "refiner") addedRefiner else added).format(ids.joinToString())
                        tk.modelsVersion++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        result = false to (e.message ?: e.toString())
                    } finally {
                        busy = false
                    }
                }
            }
            if (busy) Text(adding(), color = c.muted, fontSize = 12.sp)
        }
        result?.let { (ok, text) -> Text(text, color = if (ok) c.ok else c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (!ready) Text(notReady(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (mine.isNotEmpty()) {
            Text(yours(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Column(Modifier.fillMaxWidth().background(c.panelAlt).padding(8.dp)) {
                for (m in mine) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name.ifBlank { m.id }, color = c.text, fontSize = 13.sp)
                        Text(m.id + " · " + m.engine + " · " + m.languages.joinToString { LanguageNames.of(it) }, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Btn(removeBtn()) {
                        scope.launch {
                            runCatching { tk.client().removeModel(m.id) }
                            tk.modelsVersion++
                        }
                    }
                }
            }
        }
    }
}
