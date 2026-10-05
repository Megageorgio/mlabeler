package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
    "Ставится в папку пользователя через uv: Python и сам тулкит. Модели и движки скачаются потом, при первом использовании.",
)
private val phoneHint = L(
    "On a phone or tablet the toolkit runs on a computer. On the computer: open mLabeler, Settings → Autolabel, turn on \"Let phones connect\" — then type the address and token shown there into the fields below.",
    "На телефоне или планшете тулкит работает на компьютере. На компьютере: mLabeler → Настройки → Авторазметка → включите «Разрешить подключение с телефона» и введите сюда показанные там адрес и токен.",
)
private val showLog = L("Log", "Журнал")

/** Status of the toolkit with the buttons that fix it (install, start, retry). */
@Composable
fun ToolkitStatus(app: AppState, checkOnShow: Boolean = true) {
    val c = T.c
    val tk = app.toolkit
    val scope = rememberCoroutineScope()
    // keep the status fresh while it's on screen (the toolkit may start, finish installing or stop meanwhile)
    LaunchedEffect(Unit) {
        if (!checkOnShow) return@LaunchedEffect
        while (true) {
            if (tk.status != Status.Starting && tk.status != Status.Installing) tk.check()
            kotlinx.coroutines.delay(if (tk.status == Status.Ready) 10_000 else 3_000)
        }
    }
    var logOpen by remember { mutableStateOf(false) }
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
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                tk.status == Status.Missing && LocalToolkit.supported -> Btn(installBtn(), primary = true) { tk.install() }
                tk.status == Status.Failed && LocalToolkit.supported && LocalToolkit.findMvt(app.settings.toolkit.mvtPath) == null ->
                    Btn(installBtn(), primary = true) { tk.install() }
                (tk.status == Status.Off || tk.status == Status.Failed) && tk.canRunHere -> Btn(startBtn(), primary = true) { scope.launch { tk.start() } }
            }
            if (tk.status == Status.Ready && tk.ownProcess) Btn(stopBtn()) { tk.stop() }
            if (tk.status != Status.Starting && tk.status != Status.Installing) Btn(retryBtn()) { scope.launch { tk.check() } }
            if (tk.log.isNotEmpty()) Chip(showLog(), logOpen) { logOpen = !logOpen }
        }
        if (tk.status == Status.Missing && LocalToolkit.supported) Text(installHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (!LocalToolkit.supported && tk.status != Status.Ready && tk.status != Status.Checking) {
            Text(phoneHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
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
    var langs by remember(task) { mutableStateOf<List<ToolkitLanguage>?>(null) }
    var error by remember(task) { mutableStateOf<String?>(null) }
    val ready = app.toolkit.status == Status.Ready
    LaunchedEffect(task, enabled, ready) {
        if (!enabled || langs != null) return@LaunchedEffect
        if (!ready) {
            if (!app.toolkit.ensure()) return@LaunchedEffect
        }
        try {
            langs = app.toolkit.client().languages(task)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }
    return langs to error
}
