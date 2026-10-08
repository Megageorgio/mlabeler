package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.state.AppState
import mlabeler.app.state.Release
import mlabeler.app.theme.T

internal object UpdateTitles {
    val section = L("Updates", "Обновления")
    val channel = L("Which versions to get", "Какие версии получать")
    val stable = L("Stable", "Стабильные")
    val beta = L("Beta", "Бета")
    val alpha = L("Alpha", "Альфа")
    val channelHint = L("Stable: tested releases. Beta: new features a little earlier, mostly ready. Alpha: the newest work, may have rough edges. Beta and alpha also get stable releases when those are newer.",
        "Стабильные — проверенные выпуски. Бета — новое чуть раньше, почти готово. Альфа — самое свежее, возможны шероховатости. Бета и альфа тоже получают стабильный выпуск, если он новее.")
    val checkOnStart = L("Check for a new version at start", "Проверять новую версию при запуске")
    val checkNow = L("Check now", "Проверить сейчас")
    val checking = L("Checking…", "Проверка…")
    val available = L("Available: {0}", "Доступна версия {0}")
    val installed = L("Installed: {0}", "Установлена версия {0}")
    val download = L("Download", "Скачать")
    val whatsNew = L("What's new", "Что нового")
    val later = L("Not now", "Не сейчас")
    val skip = L("Skip this version", "Пропустить эту версию")
    val offerTitle = L("A new version of mLabeler", "Новая версия mLabeler")
    val offerText = L("Version {0} is available (installed: {1}). The download opens in the browser; on a phone, open the downloaded file to install it.",
        "Доступна версия {0} (установлена {1}). Загрузка откроется в браузере; на телефоне откройте скачанный файл, чтобы установить его.")
    fun channelName(c: String) = when (c) { "alpha" -> alpha(); "beta" -> beta(); else -> stable() }
}

private fun label(r: Release) = r.version.let { v ->
    "${v.major}.${v.minor}.${v.patch}" + when (v.stage) { 0 -> "-alpha${v.n}"; 1 -> "-beta${v.n}"; else -> "" }
}

/** Settings → About: the channel, checking at start, a manual check and what is available. */
@Composable
fun UpdateSection(app: AppState, switchRow: @Composable (String, Boolean, (Boolean) -> Unit) -> Unit) {
    val c = T.c
    val u = app.updater
    val s = app.settings.updates
    SectionTitle(UpdateTitles.section())
    Text(UpdateTitles.channel(), color = c.text, fontSize = 13.sp)
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (ch in listOf("stable", "beta", "alpha")) Chip(UpdateTitles.channelName(ch), s.channel == ch) {
            app.update { it.copy(updates = it.updates.copy(channel = ch)) }
            u.checkNow()
        }
    }
    Text(UpdateTitles.channelHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    switchRow(UpdateTitles.checkOnStart(), s.checkOnStart) { v -> app.update { it.copy(updates = it.updates.copy(checkOnStart = v)) } }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (u.checking) UpdateTitles.checking() else UpdateTitles.checkNow(), enabled = !u.checking) { u.checkNow() }
        val r = u.available
        if (r != null) {
            Text(UpdateTitles.available.format(label(r)), color = c.accent, fontSize = 13.sp)
            Btn(UpdateTitles.download(), primary = true) { runCatching { uri.openUri(r.download ?: r.page) } }
        } else if (u.lastResult.isNotEmpty()) Text(u.lastResult, color = c.muted, fontSize = 12.sp)
    }
}

/** Asked at start when a newer version was found. */
@Composable
fun UpdateOfferDialog(app: AppState) {
    val r = app.updater.offer ?: return
    val c = T.c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    fun close() { app.updater.offer = null }
    Overlay({ close() }, 480) {
        Column(Modifier.padding(18.dp)) {
            Text(UpdateTitles.offerTitle(), color = c.text, fontSize = 17.sp)
            Text(UpdateTitles.offerText.format(label(r) + " (" + UpdateTitles.channelName(r.version.channel) + ")", mlabeler.app.AppInfo.VERSION),
                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(UpdateTitles.skip()) { app.update { it.copy(updates = it.updates.copy(skipped = r.tag)) }; close() }
                Btn(UpdateTitles.later()) { close() }
                Btn(UpdateTitles.whatsNew()) { runCatching { uri.openUri(r.page) } }
                Btn(UpdateTitles.download(), primary = true) { runCatching { uri.openUri(r.download ?: r.page) }; close() }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(app.settings.updates.checkOnStart, { v -> app.update { it.copy(updates = it.updates.copy(checkOnStart = v)) } })
                Text(UpdateTitles.checkOnStart(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
