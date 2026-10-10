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
    val channelHint = L("Stable: tested releases. Beta: new features earlier, mostly finished. Alpha: the newest development builds, may be less stable. Beta and alpha also receive a stable release if it is newer.",
        "Стабильные — проверенные выпуски. Бета — новые функции раньше, в основном доработанные. Альфа — новейшие сборки в разработке, возможна меньшая стабильность. Бета и альфа также получают стабильный выпуск, если он новее.")
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
    val autoInstall = L("Download and install new versions by itself", "Скачивать и устанавливать новые версии самостоятельно")
    val autoInstallHint = L("The new version is downloaded in the background and put in place when the program closes, or at once with Restart and update. The settings, models and the toolkit stay as they are.",
        "Новая версия скачивается в фоне и устанавливается при закрытии программы или сразу по кнопке «Перезапустить и обновить». Настройки, модели и тулкит остаются как есть.")
    val autoInstallHintPhone = L("The new version is downloaded in the background; Install opens the system installer, which asks to confirm.",
        "Новая версия скачивается в фоне; кнопка «Установить» открывает системную установку, которая попросит подтверждение.")
    val downloadInstall = L("Download and install", "Скачать и установить")
    val downloading = L("Downloading {0}…", "Скачивание {0}…")
    val restartUpdate = L("Restart and update", "Перезапустить и обновить")
    val install = L("Install", "Установить")
    val onClose = L("When I close it", "При закрытии")
    val readyTitle = L("A new version is ready", "Новая версия готова")
    val readyText = L("Version {0} is downloaded (installed: {1}). It is put in place when mLabeler closes, or now with Restart and update; your work is saved first.",
        "Версия {0} скачана (установлена {1}). Она будет установлена при закрытии mLabeler или сразу по кнопке «Перезапустить и обновить»; работа перед этим сохранится.")
    val readyTextPhone = L("Version {0} is downloaded (installed: {1}). Install opens the system installer.",
        "Версия {0} скачана (установлена {1}). Кнопка «Установить» откроет системную установку.")
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
    Fold(UpdateTitles.section()) {
        Text(UpdateTitles.channel(), color = c.text, fontSize = 13.sp)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (ch in listOf("stable", "beta", "alpha")) Chip(UpdateTitles.channelName(ch), s.channel == ch) {
                app.update { it.copy(updates = it.updates.copy(channel = ch)) }
                u.checkNow()
            }
        }
        Text(UpdateTitles.channelHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        switchRow(UpdateTitles.checkOnStart(), s.checkOnStart) { v -> app.update { it.copy(updates = it.updates.copy(checkOnStart = v)) } }
        if (mlabeler.app.SelfUpdate.supported) {
            switchRow(UpdateTitles.autoInstall(), s.autoInstall) { v -> app.update { it.copy(updates = it.updates.copy(autoInstall = v)) } }
            Text((if (mlabeler.app.SelfUpdate.installsOnClose) UpdateTitles.autoInstallHint else UpdateTitles.autoInstallHintPhone)(), color = c.muted, fontSize = 12.sp)
        }
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (u.checking) UpdateTitles.checking() else UpdateTitles.checkNow(), enabled = !u.checking) { u.checkNow() }
            val r = u.available
            if (r != null) {
                Text(UpdateTitles.available.format(label(r)), color = c.accent, fontSize = 13.sp)
                UpdateButton(app, r) { runCatching { uri.openUri(r.download ?: r.page) } }
            } else if (u.lastResult.isNotEmpty()) Text(u.lastResult, color = c.muted, fontSize = 12.sp)
        }
        u.progress?.let { p -> UpdateProgress(p, u.available?.let { label(it) } ?: "") }
        if (u.installError.isNotEmpty()) Text(u.installError, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Download, install or restart: what fits the state of [r] here. */
@Composable
private fun UpdateButton(app: AppState, r: Release, openPage: () -> Unit) {
    val u = app.updater
    when {
        u.progress != null -> {}
        u.ready == r.tag -> Btn(if (mlabeler.app.SelfUpdate.installsOnClose) UpdateTitles.restartUpdate() else UpdateTitles.install(), primary = true) { u.installNow() }
        u.canInstall(r) -> Btn(UpdateTitles.downloadInstall(), primary = true) { u.download(r) }
        else -> Btn(UpdateTitles.download(), primary = true) { openPage() }
    }
}

@Composable
private fun UpdateProgress(p: Float, what: String) {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(UpdateTitles.downloading.format(what) + "  " + (p * 100).toInt() + "%", color = c.muted, fontSize = 12.sp)
        androidx.compose.material3.LinearProgressIndicator(
            progress = { p }, color = c.accent, trackColor = c.border.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Asked at start when a newer version was found (already downloaded where the program installs it itself). */
@Composable
fun UpdateOfferDialog(app: AppState) {
    val r = app.updater.offer ?: return
    val u = app.updater
    val c = T.c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    fun close() { app.updater.offer = null }
    val ready = u.ready == r.tag
    val onClose = mlabeler.app.SelfUpdate.installsOnClose
    Overlay({ close() }, 480) {
        Column(Modifier.padding(18.dp)) {
            Text(if (ready) UpdateTitles.readyTitle() else UpdateTitles.offerTitle(), color = c.text, fontSize = 17.sp)
            val name = label(r) + " (" + UpdateTitles.channelName(r.version.channel) + ")"
            Text(
                when {
                    ready && onClose -> UpdateTitles.readyText.format(name, mlabeler.app.AppInfo.VERSION)
                    ready -> UpdateTitles.readyTextPhone.format(name, mlabeler.app.AppInfo.VERSION)
                    else -> UpdateTitles.offerText.format(name, mlabeler.app.AppInfo.VERSION)
                },
                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            u.progress?.let { p -> UpdateProgress(p, label(r)) }
            if (u.installError.isNotEmpty()) Text(u.installError, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(UpdateTitles.skip()) { app.update { it.copy(updates = it.updates.copy(skipped = r.tag)) }; close() }
                Btn(if (ready && onClose) UpdateTitles.onClose() else UpdateTitles.later()) { close() }
                Btn(UpdateTitles.whatsNew()) { runCatching { uri.openUri(r.page) } }
                UpdateButton(app, r) { runCatching { uri.openUri(r.download ?: r.page) }; close() }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(app.settings.updates.checkOnStart, { v -> app.update { it.copy(updates = it.updates.copy(checkOnStart = v)) } })
                Text(UpdateTitles.checkOnStart(), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
