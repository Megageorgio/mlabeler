package mlabeler.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T

/** One tip: a less obvious feature or setting, what it does and what it is good for. {command-id} shows its key. */
private class Tip(val title: L, val text: L)

private val tips = listOf(
    Tip(L("Every command in one list", "Все команды в одном списке"),
        L("{commands} opens the list of all commands with search. Type a part of the name and press Enter: it is quicker than looking through menus, and every command shows its key next to it, so the list also teaches the shortcuts.",
            "{commands} открывает список всех команд с поиском. Наберите часть названия и нажмите Enter: так быстрее, чем искать в меню, а рядом с каждой командой видна её клавиша, поэтому по списку можно запомнить и сочетания клавиш.")),
    Tip(L("Search in the settings", "Поиск по настройкам"),
        L("The field at the top of the settings finds any option by its name or by its description. Every page has a button that returns its defaults, so any option can be tried without risk.",
            "Поле вверху настроек находит любой параметр по названию или по описанию. На каждой странице есть кнопка, которая возвращает значения по умолчанию, поэтому любой параметр можно попробовать без риска.")),
    Tip(L("Type the phonemes first, then cut", "Сначала впишите фонемы, затем разрезайте"),
        L("In the details panel, type the phonemes of the recording into “Phonemes in advance”, separated by spaces. Each new boundary then names its part with the next phoneme by itself. “Spread over the selection” places all of them evenly over the selected range at once, and only the boundaries are left to move.",
            "В панели свойств впишите фонемы записи в поле «Фонемы заранее» через пробел. Тогда каждая новая граница сама называет свою часть следующей фонемой. «Расставить по выделенному» сразу равномерно распределяет их по выделенному отрезку, и остаётся только скорректировать границы.")),
    Tip(L("Ripple and linked boundaries", "Сдвиг следом и связанные границы"),
        L("With Ripple ({ripple}) moving a boundary moves everything after it; the same happens for one drag when Shift is held. Linked ({linked}) moves the boundaries at the same time on the other lanes too; Alt keeps them in place for one drag. Useful when a word or a whole phrase has to shift without breaking its phonemes.",
            "При «Сдвиге следом» ({ripple}) вместе с границей двигается всё, что после неё; то же самое на одно перетаскивание даёт зажатый Shift. «Связь» ({linked}) двигает и границы в то же время на других полосах; Alt на одно перетаскивание оставляет их на месте. Удобно, когда слово или целую фразу нужно сдвинуть, не разрушив фонемы.")),
    Tip(L("Work with a whole range", "Работа с целым отрезком"),
        L("Drag over the waveform to select a range. With nothing else selected, {delete} removes every boundary inside it, and {nudge-left} / {nudge-right} move them all together. You can also press inside the range and drag its boundaries with the mouse at once.",
            "Проведите по волне, чтобы выделить отрезок. Если больше ничего не выбрано, {delete} убирает все границы внутри него, а {nudge-left} / {nudge-right} двигают их вместе. Также можно нажать внутри отрезка и перетащить все его границы мышью сразу.")),
    Tip(L("Check the worst places first", "Сначала самые сомнительные места"),
        L("{review-next} jumps to the next place worth a look, worst first: errors, then warnings, then the phonemes the aligner was least sure of. After autolabel this is the quickest way through a folder: you look only where something is likely wrong.",
            "{review-next} переходит к следующему месту, на которое стоит посмотреть, начиная с худших: ошибки, затем предупреждения, затем фонемы, в которых выравниватель был меньше всего уверен. После авторазметки это самый быстрый способ пройти папку: смотрите только туда, где вероятна ошибка.")),
    Tip(L("Checks you can tune", "Проверки можно настроить"),
        L("Settings → Checks sets the limits: the shortest part, the longest phoneme, pause and phrase, the set of allowed phonemes (also taken from a dictionary) and DiffSinger checks. “A pause at the start and the end of every recording” makes a missing SP (or another pause you name) an error. 0 turns a limit off.",
            "В Настройки → Проверки задаются пределы: самая короткая часть, самая длинная фонема, пауза и фраза, набор разрешённых фонем (можно взять из словаря) и проверки для DiffSinger. «Пауза в начале и в конце каждой записи» делает ошибкой отсутствие SP (или другой названной паузы). 0 выключает предел.")),
    Tip(L("Your own checks in JavaScript", "Свои проверки на JavaScript"),
        L("A small script can mark any problem the built-in checks don't know about: a phoneme that must not follow another one, a word that must start with a pause and so on. Put it into the checks folder of the program (for every folder) or into .mlabeler/checks inside a folder. Settings → Checks shows both places and creates an example to start from.",
            "Небольшой скрипт может отмечать любую проблему, которую не знают встроенные проверки: фонему, которая не должна идти после другой, слово, которое должно начинаться с паузы, и так далее. Поместите его в папку проверок программы (для всех папок) или в .mlabeler/checks внутри папки. В Настройки → Проверки видны обе папки и можно создать пример для начала.")),
    Tip(L("Compare with other labels", "Сравнение с другой разметкой"),
        L("“Compare” in the details panel shows the labels of the same files from another folder, with the differences coloured and counted. Useful for checking an autolabel model against hand labels, or two people's work against each other.",
            "«Сравнение» в панели свойств показывает разметку тех же файлов из другой папки, расхождения выделены цветом и посчитаны. Удобно, чтобы проверить модель авторазметки по ручной разметке или сверить работу двух человек.")),
    Tip(L("Everything in one picture", "Всё на одной картинке"),
        L("{overlay} lays the waveform, spectrogram and pitch over each other, so the screen holds a taller picture. {names-on-audio} writes the phoneme names on the picture itself; where exactly in each part is set with the dot pad in Settings → View.",
            "{overlay} накладывает волну, спектрограмму и высоту тона друг на друга, и картинка становится выше. {names-on-audio} подписывает фонемы прямо на картинке; где именно в каждой части, задаётся точками в Настройки → Вид.")),
    Tip(L("The pitch lane is a piano roll", "Полоса высоты — это пианоролл"),
        L("Show the pitch in its own lane: it gets keys and notes. Drag a note up or down (Alt: in cents, Shift: only notes of the key), Ctrl+click cuts a note, a right click on the border of two notes joins them, inside a note it puts back the sung pitch. Alt+wheel moves the view, Ctrl+Alt+wheel zooms it.",
            "Покажите высоту тона отдельной полосой: у неё появятся клавиши и ноты. Перетаскивайте ноту вверх или вниз (с Alt — по центам, с Shift — только по нотам тональности), Ctrl+щелчок разрезает ноту, щелчок правой кнопкой на стыке двух нот объединяет их, а внутри ноты возвращает спетую высоту. Alt+колесо двигает вид, Ctrl+Alt+колесо меняет масштаб.")),
    Tip(L("Fix the pitch where the analysis is wrong", "Поправьте высоту там, где анализ ошибся"),
        L("The pencil ({f0-pencil}) draws the pitch by hand; the right button erases the drawing. The drawn pitch is kept for the file, used for notes and written into the f0 of a .ds. {resynth-world} and {resynth-nsf} let you hear the selection sung with it, quickly or with the DiffSinger vocoder.",
            "Карандаш ({f0-pencil}) рисует высоту вручную, правая кнопка стирает нарисованное. Нарисованная высота хранится для файла, используется для нот и записывается в f0 файла .ds. {resynth-world} и {resynth-nsf} дают послушать выделенное с этой высотой: быстро или через вокодер DiffSinger.")),
    Tip(L("Notes for DiffSinger in two commands", "Ноты для DiffSinger за две команды"),
        L("{group-phonemes} decides which phonemes belong to one note using the dictionary of the language; {notes-from-groups} then makes a note per group with the pitch taken from the recording. Notes can be saved as MIDI ({midi-export}) and loaded from a MIDI file next to the recording ({midi-import}).",
            "{group-phonemes} решает по словарю языка, какие фонемы относятся к одной ноте; затем {notes-from-groups} делает по ноте на группу с высотой из записи. Ноты можно сохранить в MIDI ({midi-export}) и загрузить из MIDI рядом с записью ({midi-import}).")),
    Tip(L("Is there enough to train on?", "Хватит ли данных для обучения?"),
        L("{dataset-summary} counts the length, every phoneme and the notes over the whole folder, shows rare phonemes and compares them with a dictionary. A click on a phoneme finds the files that have it, so you know what is missing before training, not after.",
            "{dataset-summary} считает длительность, каждую фонему и ноты по всей папке, показывает редкие фонемы и сравнивает набор со словарём. Щелчок по фонеме находит файлы с ней, так что недостающее видно до обучения, а не после.")),
    Tip(L("Check the recordings themselves", "Проверьте сами записи"),
        L("{sound-check} looks through the folder for clipping, too quiet takes, noise, long silence, another sample rate and stereo files. These problems are easy to miss by ear and spoil both labelling and training.",
            "{sound-check} ищет по папке перегрузку, слишком тихие дубли, шум, длинную тишину, другую частоту дискретизации и стерео. Такие проблемы легко пропустить на слух, а они мешают и разметке, и обучению.")),
    Tip(L("Edit the sound without breaking the labels", "Правка звука без нарушения разметки"),
        L("{sound-mode} locks the boundaries, so a drag selects any part of the sound, and the panel on the right holds every tool that changes the recording. {cut-audio} moves the labels after the cut back; every change is undone with {undo}, and the first version of the file stays in .mlabeler/backup.",
            "{sound-mode} закрепляет границы: перетаскивание выделяет любой сегмент звука, а панель справа собирает все инструменты, меняющие запись. {cut-audio} сдвигает разметку после вырезанного назад; любое изменение отменяется {undo}, а первая версия файла остаётся в .mlabeler/backup.")),
    Tip(L("Cut a long recording into segments", "Нарезка длинной записи на сегменты"),
        L("{segments} marks segments on their own lane: by silence, by the pauses of the labels or from the selection. Each named segment is saved as its own WAV with its labels; a “/” in a name puts it into a subfolder.",
            "{segments} размечает сегменты на отдельной полосе: по тишине, по паузам разметки или по выделенному. Каждый названный сегмент сохраняется отдельным WAV со своей разметкой; «/» в названии помещает его в подпапку.")),
    Tip(L("Clicks and noise", "Щелчки и шум"),
        L("{cleanup} finds and repairs clicks and lowers the noise. Select a pause first: the noise is learned from it, and the rest of the recording is cleaned by that sample. The original can always be restored.",
            "{cleanup} находит и исправляет щелчки и снижает шум. Сначала выделите паузу: по ней программа узнаёт, как звучит шум, и очищает по этому образцу всю запись. Исходную запись всегда можно восстановить.")),
    Tip(L("A dataset ready for DiffSinger", "Готовый датасет для DiffSinger"),
        L("{export-diffsinger} writes the wav files and transcriptions.csv. Long recordings can be cut at pauses with a maximum length, so the segments stay within what DiffSinger trains well on (about 15 s).",
            "{export-diffsinger} записывает wav-файлы и transcriptions.csv. Длинные записи можно разрезать по паузам с ограничением длины, чтобы сегменты оставались в пределах, на которых DiffSinger лучше всего обучается (около 15 с).")),
    Tip(L("Autolabel a whole folder", "Авторазметка всей папки"),
        L("In the autolabel window pick “All files of the folder”: the files are labelled one by one and each is saved. The text of a file can come from a .txt with the same name; lyrics can also be loaded from .lrc, .srt or .lab, or recognised by Whisper first.",
            "В окне авторазметки выберите «Все файлы папки»: файлы размечаются по очереди, и каждый сохраняется. Текст файла может браться из .txt с тем же именем; текст можно также загрузить из .lrc, .srt или .lab или сначала распознать через Whisper.")),
    Tip(L("Known phonemes are placed, not recognised", "Известные фонемы расставляются без распознавания"),
        L("Enter the known phonemes in the autolabel window: the model then only places them instead of recognising them, which is much more reliable. “Recognition settings” hold the confidence threshold, the decoder and the silence options.",
            "Впишите известные фонемы в окне авторазметки: тогда модель только расставляет их, а не распознаёт, и это гораздо надёжнее. В «Настройках распознавания» — порог уверенности, декодер и параметры тишины.")),
    Tip(L("Your own models", "Свои модели"),
        L("A model you trained or downloaded is added with “Add your own model…” in the autolabel window or in Settings → Autolabel: a checkpoint, a folder or an archive. Its config and phoneme list are picked up from next to it.",
            "Обученную или скачанную модель можно добавить кнопкой «Добавить свою модель…» в окне авторазметки или в Настройки → Авторазметка: чекпоинт, папку или архив. Файл конфигурации и список фонем рядом с ним будут добавлены автоматически.")),
    Tip(L("Autolabel on a phone", "Авторазметка на телефоне"),
        L("The toolkit runs on a computer, but a phone or tablet in the same network can use it. On the computer turn on “Let phones connect” in Settings → Autolabel, then type the shown address and token on the phone.",
            "Тулкит работает на компьютере, но им может пользоваться телефон или планшет в той же сети. На компьютере включите «Разрешить подключение с телефона» в Настройки → Авторазметка, затем введите на телефоне показанные адрес и токен.")),
    Tip(L("oto from the file names", "oto по именам файлов"),
        L("{auto-oto} makes entries from the names of the samples (kana, romaji, Cyrillic, or runs like “babab”) and the recordings: CV, VCV, CVVC and Russian CVC. The tempo is detected automatically; enter it only if the result looks shifted. “Keep, add missing aliases” keeps your finished entries unchanged.",
            "{auto-oto} делает записи по именам сэмплов (кана, ромадзи, кириллица или цепочки вроде «babab») и по самим записям: CV, VCV, CVVC и русский CVC. Темп определяется автоматически; вводите его, только если результат выглядит сдвинутым. «Оставить, добавить недостающие» не изменяет уже готовые записи.")),
    Tip(L("Quick keys for oto", "Быстрые клавиши для oto"),
        L("In the oto mode Q W E R T put the offset, overlap, preutterance, consonant and cutoff at the cursor, ↑ ↓ go through the entries. Dragging the preutterance moves all the markers together; {oto-lock} switches this off when only the preutterance should move.",
            "В режиме oto клавиши Q W E R T ставят offset, overlap, preutterance, consonant и cutoff на курсор, ↑ ↓ переключают записи. Перетаскивание preutterance двигает все маркеры вместе; {oto-lock} выключает это, если нужно сдвинуть только preutterance.")),
    Tip(L("Record from a reclist", "Запись по реклисту"),
        L("{record} shows the line to sing, records into the folder and moves on. R starts and stops, Space plays the take, ↑ ↓ change the line. A metronome, count-in and a guide WAV help to keep the tempo, which also makes automatic oto more exact.",
            "{record} показывает строку для пения, записывает в папку и переходит дальше. R начинает и останавливает запись, Space проигрывает дубль, ↑ ↓ меняют строку. Метроном, отсчёт и опорный WAV помогают держать темп, а ровный темп делает и автоматическое oto точнее.")),
    Tip(L("Plugins on quick keys", "Плагины на быстрых клавишах"),
        L("Plugins ({plugins}) are small scripts that change the labels: rename, shift, fill and more. Up to four of them can be assigned to {plugin-slot-1}–{plugin-slot-4}, so a routine fix takes a single key.",
            "Плагины ({plugins}) — небольшие скрипты, которые меняют разметку: переименовать, сдвинуть, заполнить и другое. До четырёх из них можно назначить на {plugin-slot-1}–{plugin-slot-4}, и частая правка будет выполняться одной клавишей.")),
    Tip(L("Save your own environment", "Сохраните свою рабочую среду")
        , L("An environment is a ready set of panels, lanes, toolbar buttons and bars. Arrange the screen the way you like, save it as your own environment in Settings → Interface and share it as a file. Theme, language and keys are not part of it and stay as they are.",
            "Рабочая среда — это готовый набор панелей, полос, кнопок и строк. Настройте экран по своему усмотрению, сохраните его своей средой в Настройки → Интерфейс и поделитесь файлом. Тема, язык и клавиши в среду не входят и остаются как есть.")),
    Tip(L("Configurable mouse buttons", "Кнопки мыши можно настроить"),
        L("Settings → Mouse sets what the right and middle buttons, the double click, the wheel and clicks with Ctrl or Alt do. The mouse tools (cursor, scissors, hand, play) are optional; when they are off, the left button is always the cursor.",
            "В Настройки → Мышь задаётся, что делают правая и средняя кнопки, двойной щелчок, колесо и щелчки с Ctrl или Alt. Инструменты мыши (курсор, ножницы, рука, проигрывание) необязательны: если их выключить, левая кнопка всегда работает как курсор.")),
    Tip(L("Rename across the whole folder", "Переименование по всей папке"),
        L("{batch-rename} renames labels by a pattern in every file at once, for example one phoneme set into another. Every change can be undone.",
            "{batch-rename} переименовывает метки по шаблону сразу во всех файлах, например переводит один набор фонем в другой. Любое изменение можно отменить.")),
    Tip(L("Keep track of what is done", "Отмечайте сделанное"),
        L("{done} marks a file as finished, {star} adds a star. The file list can show only unfinished, starred or unlabelled files, and the status bar counts the finished ones, so progress in a large folder is easy to follow.",
            "{done} отмечает файл как готовый, {star} ставит звезду. Список файлов может показывать только неготовые, отмеченные или неразмеченные файлы, а строка состояния считает готовые, так что в большой папке легко отслеживать ход работы.")),
    Tip(L("Labels are backed up", "Разметка копируется"),
        L("Before a label file is overwritten for the first time in a session, its old version is copied to .mlabeler/backup inside the folder. Together with undo this means an edit can always be taken back.",
            "Перед первой перезаписью файла разметки за сеанс его старая версия копируется в .mlabeler/backup внутри папки. Вместе с отменой это значит, что любую правку можно вернуть.")),
    Tip(L("Automatic saving", "Автоматическое сохранение"),
        L("Labels are saved when you go to another file; this can be turned off in Settings → General, then changes wait until you save. The same page has autosave every N seconds, for long sessions in one file.",
            "Разметка сохраняется при переходе к другому файлу; в Настройки → Общие это можно выключить, тогда изменения ждут явного сохранения. Там же есть автосохранение каждые N секунд — для долгой работы в одном файле.")),
    Tip(L("Many label formats", "Много форматов разметки"),
        L("The program reads and writes .lab, Praat .TextGrid, Audacity labels, DiffSinger .ds and transcriptions.csv and UTAU oto.ini, and imports vLabeler projects (.lbp). A file is saved back in the format it came in.",
            "Программа читает и записывает .lab, .TextGrid из Praat, метки Audacity, .ds и transcriptions.csv DiffSinger и oto.ini UTAU, а также импортирует проекты vLabeler (.lbp). Файл сохраняется обратно в том формате, в котором был.")),
)

private val tipOfDay = L("Tip of the day", "Совет дня")
private val showAtStart = L("Show tips at start", "Показывать советы при запуске")
private val prevTip = L("Previous", "Назад")
private val nextTip = L("Next tip", "Следующий совет")

val tipsCount: Int get() = tips.size

/** A tip about a less obvious feature, at start (unless turned off) or from the Help menu. */
@Composable
fun TipsDialog(app: AppState) {
    val c = T.c
    var index by remember { mutableIntStateOf(app.settings.tipNext.mod(tips.size)) }
    fun close() {
        app.showTips = false
        // the next start shows the tip after the last one seen
        app.update { it.copy(tipNext = (index + 1) % tips.size) }
        app.editor?.requestFocus?.invoke()
    }
    Overlay({ close() }, 560) {
        DialogContent(footer = {
            Btn(prevTip()) { index = (index - 1).mod(tips.size) }
            Btn(nextTip()) { index = (index + 1) % tips.size }
            Btn(S.close(), primary = true) { close() }
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tipOfDay(), color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${index + 1} / ${tips.size}", color = c.muted, fontSize = 12.sp)
            }
            val tip = tips[index]
            Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(top = 8.dp)) {
                Text(tip.title(), color = c.text, fontSize = 17.sp)
                Spacer(Modifier.padding(top = 8.dp))
                Text(fillKeys(tip.text()), color = c.text, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(app.settings.tipsAtStart, { v -> app.update { it.copy(tipsAtStart = v) } })
                Text(showAtStart(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
