@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.app.toolkit.LocalToolkit
import mlabeler.app.toolkit.ToolkitLanguage
import mlabeler.app.toolkit.ToolkitManager.Status

private val installBtn = L("Install mVocalToolkit", "Установить mVocalToolkit")
private val startBtn = L("Start", "Запустить")
private val stopBtn = L("Stop", "Остановить")
private val retryBtn = L("Check again", "Проверить снова")
private val installHint = L(
    "Installs into your user folder with uv: Python and the toolkit itself. Models and engines download later, when you first use them.",
    "Устанавливается в папку пользователя через uv: Python и сам тулкит. Модели и движки загружаются позже, при первом использовании.",
)
private val phoneHint = L(
    "On a phone or tablet the toolkit runs on a computer. On the computer: open mLabeler, Settings → Autolabel, turn on \"Let phones connect\" — then type the address and token shown there into the fields below.",
    "На телефоне или планшете тулкит работает на компьютере. На компьютере: mLabeler → Настройки → Авторазметка → включите «Разрешить подключение с телефона» и введите сюда показанные там адрес и токен.",
)
private val showLog = L("Log…", "Журнал…")
private val reinstallBtn = L("Reinstall", "Переустановить")
private val reinstallSure = L("Click again to reinstall", "Нажмите ещё раз")
private val reinstallHint = L(
    "The toolkit stops and is installed again (a few hundred MB). Downloaded models and engines stay.",
    "Тулкит остановится и установится заново (несколько сотен МБ). Скачанные модели и движки останутся.",
)

/** Status of the toolkit with the buttons that fix it (install, start, retry). */
@Composable
fun ToolkitStatus(app: AppState, checkOnShow: Boolean = true, reinstall: Boolean = false) {
    val c = T.c
    val tk = app.toolkit
    val scope = rememberCoroutineScope()
    // keep the status fresh while it's on screen (the toolkit may start, finish installing or stop meanwhile)
    LaunchedEffect(Unit) {
        if (!checkOnShow) return@LaunchedEffect
        while (true) {
            if (tk.status != Status.Starting && tk.status != Status.Installing) tk.check()
            // while it answers there is nothing to watch closely; every check is a request to the toolkit
            kotlinx.coroutines.delay(if (tk.status == Status.Ready) 60_000 else 3_000)
        }
    }
    var logOpen by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when (tk.status) {
                Status.Ready -> c.ok
                Status.Starting, Status.Installing, Status.Checking, Status.Unknown -> c.warn
                else -> c.danger
            }
            androidx.compose.foundation.layout.Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Text(tk.statusText(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        }
        if (tk.status == Status.Failed && tk.lastError.isNotBlank()) {
            Text(tk.lastError, color = c.danger, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 5, modifier = Modifier.padding(top = 4.dp))
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                tk.status == Status.Missing && LocalToolkit.supported -> Btn(installBtn(), primary = true) { tk.install() }
                tk.status == Status.Failed && LocalToolkit.supported && LocalToolkit.findMvt(app.settings.toolkit.mvtPath) == null ->
                    Btn(installBtn(), primary = true) { tk.install() }
                (tk.status == Status.Off || tk.status == Status.Failed) && tk.canRunHere -> Btn(startBtn(), primary = true) { scope.launch { tk.start() } }
            }
            if (tk.status == Status.Ready && tk.ownProcess) Btn(stopBtn()) { tk.stop() }
            if (tk.status != Status.Starting && tk.status != Status.Installing) Btn(retryBtn()) { scope.launch { tk.check() } }
            // a second click confirms: it downloads the toolkit again
            if (reinstall && tk.canRunHere && tk.status != Status.Missing && tk.status != Status.Starting && tk.status != Status.Installing &&
                LocalToolkit.findMvt(app.settings.toolkit.mvtPath) != null) {
                Btn(if (confirmReinstall) reinstallSure() else reinstallBtn()) {
                    if (confirmReinstall) { confirmReinstall = false; logOpen = true; tk.reinstall() } else confirmReinstall = true
                }
            }
            if (tk.log.isNotEmpty()) Chip(showLog(), logOpen) { logOpen = !logOpen }
        }
        if (confirmReinstall) Text(reinstallHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (tk.status == Status.Missing && LocalToolkit.supported) Text(installHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (!LocalToolkit.supported && tk.status != Status.Ready && tk.status != Status.Checking) {
            Text(phoneHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (tk.status == Status.Installing || tk.status == Status.Starting) InstallStats(tk)
        if (logOpen || tk.status == Status.Installing) {
            val state = rememberLazyListState()
            LaunchedEffect(tk.log.size) { if (tk.log.isNotEmpty()) state.scrollToItem(tk.log.size - 1) }
            LazyColumn(
                Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 160.dp).background(c.bg).padding(6.dp),
                state = state,
            ) {
                items(tk.log) { Text(it, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

/** Languages and models of a task, loaded after the toolkit answers (started here if needed). */
@Composable
fun rememberToolkitModels(app: AppState, task: String, enabled: Boolean = true): Pair<List<ToolkitLanguage>?, String?> {
    val version = app.toolkit.modelsVersion
    var langs by remember(task, version) { mutableStateOf<List<ToolkitLanguage>?>(null) }
    var error by remember(task, version) { mutableStateOf<String?>(null) }
    val ready = app.toolkit.status == Status.Ready
    LaunchedEffect(task, enabled, ready, version) {
        if (!enabled || langs != null) return@LaunchedEffect
        if (!ready) {
            if (!app.toolkit.ensure()) return@LaunchedEffect
        }
        try {
            langs = app.toolkit.client().languages(task)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }
    return langs to error
}

private val elapsedT = L("{0} so far", "прошло {0}")
private val pkgsT = L("packages downloaded: {0}", "скачано пакетов: {0}")
private val nowT = L("now: {0}", "сейчас: {0}")
private val linesT = L("log lines: {0}", "строк в журнале: {0}")

/** What the installation is doing, in numbers: time, packages downloaded, the package being fetched. */
@Composable
private fun InstallStats(tk: mlabeler.app.toolkit.ToolkitManager) {
    val c = T.c
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    val secs = ((now - tk.busySince) / 1000).coerceAtLeast(0)
    val log = tk.log.toList()
    // uv prints "Downloading torch (2.3GiB)" and " Downloaded torch"
    val downloaded = log.count { it.trimStart().startsWith("Downloaded ") }
    val current = log.lastOrNull { it.trimStart().startsWith("Downloading ") }?.trim()?.removePrefix("Downloading ")
    val parts = buildList {
        add(elapsedT.format("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"))
        if (downloaded > 0) add(pkgsT.format(downloaded))
        if (current != null && log.indexOfLast { it.trimStart().startsWith("Downloaded ") } < log.indexOfLast { it.trimStart().startsWith("Downloading ") }) add(nowT.format(current))
        add(linesT.format(log.size))
    }
    androidx.compose.material3.LinearProgressIndicator(color = c.accent, trackColor = c.border, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp))
    Text(parts.joinToString("  ·  "), color = c.text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    log.lastOrNull()?.let { Text(it, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}
