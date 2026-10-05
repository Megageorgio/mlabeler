package mlabeler.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.Mode
import mlabeler.app.theme.T

private val helpTitle = L("How it works", "Как с этим работать")
private val basics = L("Basics", "Основное")
private val labelsTitle = L("Phonemes and words", "Фонемы и слова")
private val otoTitle = L("oto.ini", "oto.ini")
private val moreTitle = L("More", "Ещё")

private val basicsMouse = listOf(
    L("Files are on the left. The next one opens with PgDn, the previous with PgUp.", "Файлы слева. Следующий — PgDn, предыдущий — PgUp."),
    L("Wheel scrolls, Ctrl+wheel zooms. F shows the whole file.", "Колесо прокручивает, Ctrl+колесо — масштаб. F — весь файл."),
    L("Space plays the selected interval or the selection. Y slows playback down.", "Пробел играет выбранный интервал или выделение. Y — медленнее."),
    L("Changes are saved with Ctrl+S, and also when you switch files.", "Сохранение — Ctrl+S, а также при переходе к другому файлу."),
    L("Ctrl+Z undoes, Ctrl+K lists every command.", "Ctrl+Z — отмена, Ctrl+K — список всех команд."),
)
private val basicsTouch = listOf(
    L("The folder button at the top opens the list of files.", "Кнопка с папкой сверху открывает список файлов."),
    L("One finger scrolls, two fingers zoom. Tap a label to select it, tap near a boundary to grab it.",
        "Один палец листает, два — масштаб. Нажмите на метку, чтобы выбрать её; рядом с границей — чтобы взять границу."),
    L("The buttons at the bottom play, step, nudge, split and merge.", "Кнопки снизу: играть, шагать, сдвигать, резать и объединять."),
)
private val labelsHelp = listOf(
    L("Drag a boundary to move it. With Shift everything after it moves too, with Alt the same boundary in other tiers stays.",
        "Тяните границу, чтобы сдвинуть. С Shift сдвигается всё, что после неё; с Alt граница в других слоях остаётся на месте."),
    L("Double click a label to rename it. S splits at the cursor and asks for the new name, M merges with the next one.",
        "Двойной клик по метке — переименовать. S режет под курсором и сразу спрашивает имя, M объединяет со следующей."),
    L("Q and W put the left or right boundary of the interval at the cursor. , and . nudge the selected boundary.",
        "Q и W ставят левую или правую границу интервала под курсор. , и . сдвигают выбранную границу."),
    L("The Entries tab lists every label of the file or of the whole folder, with counts.",
        "Вкладка «Записи» показывает все метки файла или всей папки, с подсчётом."),
    L("Drag over the waveform to select a part, then Ctrl+Shift+A autolabels just that part.",
        "Протяните по волне, чтобы выделить кусок, затем Ctrl+Shift+A разметит только его."),
)
private val otoHelp = listOf(
    L("Blue: offset and cutoff. Green: overlap. Red: preutterance. Pink: the consonant part.",
        "Синие — offset и cutoff. Зелёная — overlap. Красная — preutterance. Розовая область — согласная."),
    L("Dragging preutterance moves all markers; hold Shift to move it alone.", "Preutterance тащит все маркеры; с Shift — только себя."),
    L("Q W E R T put offset, overlap, preutterance, consonant and cutoff at the cursor.",
        "Q W E R T ставят offset, overlap, preutterance, consonant и cutoff под курсор."),
    L("↑ ↓ go through the entries. N adds one, Ctrl+D copies, Del removes.", "↑ ↓ — по записям. N — новая, Ctrl+D — копия, Del — удалить."),
)
private val moreHelp = listOf(
    L("The folder name under the file name opens the folder settings: what is labelled there and where labels are.",
        "Название папки под именем файла открывает её настройки: что здесь размечается и где лежит разметка."),
    L("Details on the right: Compare shows labels of the same files from another folder, with the differences coloured.",
        "Справа в свойствах «Сравнение» показывает разметку тех же файлов из другой папки и подсвечивает различия."),
    L("Settings: themes, interface size, pitch and loudness, keys.", "Настройки: темы, размер интерфейса, высота тона и громкость, клавиши."),
)

@Composable
fun HelpDialog(app: AppState) {
    val c = T.c
    val mode = app.editor?.mode ?: Mode.Labels
    Overlay({ app.showHelp = false; app.editor?.requestFocus?.invoke() }, 640) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row {
                Text(helpTitle(), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.close, S.close()) { app.showHelp = false }
            }
            @Composable
            fun block(title: String, lines: List<L>) {
                SectionTitle(title)
                for (l in lines) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("·", color = c.accent, fontSize = 14.sp, modifier = Modifier.width(14.dp))
                    Text(l(), color = c.text, fontSize = 13.sp)
                }
            }
            block(basics(), if (Platform.isMobile) basicsTouch else basicsMouse)
            if (mode == Mode.Oto) block(otoTitle(), otoHelp) else block(labelsTitle(), labelsHelp)
            block(moreTitle(), moreHelp)
        }
    }
}
