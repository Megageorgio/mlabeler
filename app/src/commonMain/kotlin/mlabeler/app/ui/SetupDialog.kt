package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.Lang
import mlabeler.app.state.AppState
import mlabeler.app.state.Setups
import mlabeler.app.theme.T

private val welcome = L("Welcome to mLabeler", "Добро пожаловать в mLabeler")
private val how = L("How much do you want to see at first? You can change it any time: View → Interface, or in the settings.",
    "Сколько всего показать сначала? Это можно поменять когда угодно: Вид → Интерфейс или в настройках.")
private val simpleTitle = L("Simple", "Простой")
private val simpleText = L(
    "Big signed buttons for the main things: files, undo, play, cut and join, autolabel. Everything else is in the menus.",
    "Крупные подписанные кнопки для главного: файлы, отмена, воспроизведение, разрезать и склеить, авторазметка. Остальное — в меню.",
)
private val fullTitle = L("Everything", "Всё сразу")
private val fullText = L(
    "All tool groups as compact icons, the details panel and the pitch curve. For those who know what they're doing.",
    "Все группы инструментов компактными значками, панель подробностей и кривая высоты. Для тех, кто уже знает, что к чему.",
)

/** Shown once, on the first start. */
@Composable
fun SetupDialog(app: AppState) {
    val c = T.c
    Overlay({ app.update { Setups.simple(it) } }, 720) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((code, name) in Lang.available) Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
            }
            Text(welcome(), color = c.text, fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
            Text(how(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                val narrow = maxWidth < 520.dp
                val cards: @Composable (Modifier) -> Unit = { m ->
                    SetupCard(simpleTitle(), simpleText(), m) { app.update { Setups.simple(it) } }
                    SetupCard(fullTitle(), fullText(), m) { app.update { Setups.everything(it) } }
                }
                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cards(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius * 2)
    Column(
        modifier.clip(shape).background(c.panelAlt).border(c.borderWidth, c.border, shape).clickable(onClick = onClick).padding(18.dp),
    ) {
        Text(title, color = c.accent, fontSize = 18.sp)
        Text(text, color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
