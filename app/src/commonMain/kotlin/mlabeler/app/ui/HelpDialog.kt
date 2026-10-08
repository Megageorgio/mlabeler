package mlabeler.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
private val helpIntro = L("Pick a topic, then open the question you need. Keys in brackets can be changed in Settings → Shortcuts.",
    "Выберите тему и раскройте нужный вопрос. Клавиши в скобках можно поменять в Настройки → Сочетания клавиш.")

/** One question with its answer lines. Lines may hold {command-id}, replaced with the command's current key. */
private class Item(val q: L, val lines: List<L>, val touch: Boolean? = null)
private class Topic(val title: L, val items: List<Item>)

private fun it(q: L, vararg lines: L, touch: Boolean? = null) = Item(q, lines.toList(), touch)

private val topics = listOf(
    Topic(L("Start", "Начало"), listOf(
        it(L("What is this program for?", "Для чего эта программа?"),
            L("It places and names the parts of a recording: phonemes, words, notes. Such labels are needed to train singing and speech voices (DiffSinger, UTAU and others).",
                "Она размечает запись: где какая фонема, слово или нота. Такая разметка нужна для обучения певческих и речевых голосов (DiffSinger, UTAU и другие)."),
            L("A label file sits next to each recording (.lab, .TextGrid and other formats). The program reads them, lets them be fixed and saves them back.",
                "Рядом с каждой записью лежит файл разметки (.lab, .TextGrid и другие форматы). Программа их читает, даёт исправить и сохраняет обратно.")),
        it(L("How do I open my recordings?", "Как открыть свои записи?"),
            L("Open a folder with .wav files ({open}). Sub-folders are included. Files can also be dropped onto the window.",
                "Откройте папку с файлами .wav ({open}). Вложенные папки тоже попадут в список. Файлы можно просто перетащить в окно."),
            L("On the first opening the program asks what is labelled in the folder and where the label files are. This can be changed later ({workspace}).",
                "При первом открытии программа спросит, что размечается в папке и где лежит разметка. Это можно поменять позже ({workspace})."),
            L("Dropping files onto the file list copies them into the folder; dropping onto the main area just opens them.",
                "Файлы, брошенные на список файлов, копируются в папку; брошенные в основную область — просто открываются.")),
        it(L("What is on the screen?", "Что где на экране?"),
            L("Left: the list of files and the Entries tab with all labels. Middle: the waveform, the spectrogram and the label lanes. Right: details of the selected part, checks and comparison.",
                "Слева — список файлов и вкладка «Записи» со всеми метками. В середине — волна, спектрограмма и полосы разметки. Справа — свойства выбранного, проверки и сравнение."),
            L("Panels open and close with {files} and {inspector}. The buttons on top are grouped; which groups are shown is set in Settings → Interface.",
                "Панели открываются и закрываются {files} и {inspector}. Кнопки сверху разбиты на группы; какие видны — настраивается в Настройки → Интерфейс.")),
        it(L("How do I move around?", "Как перемещаться?"),
            L("Wheel scrolls through time, Ctrl+wheel zooms. {zoom-fit} shows the whole file, {zoom-selection} zooms to the selection.",
                "Колесо прокручивает по времени, Ctrl+колесо меняет масштаб. {zoom-fit} — весь файл, {zoom-selection} — приблизить выделенное."),
            L("{next-file} and {prev-file} open the next and previous file. ← → jump between boundaries, Tab between parts, ↑ ↓ between lanes.",
                "{next-file} и {prev-file} — следующий и предыдущий файл. ← → — по границам, Tab — по частям, ↑ ↓ — по полосам."),
            touch = false),
        it(L("How does it work with fingers?", "Как работать пальцами?"),
            L("One finger scrolls, two fingers zoom. Tap a part to select it, tap near a boundary to grab it.",
                "Один палец листает, два — масштаб. Нажмите на часть, чтобы выбрать её; рядом с границей — чтобы взять границу."),
            L("The folder button opens the file list. The buttons at the bottom play, step, nudge, split and merge.",
                "Кнопка с папкой открывает список файлов. Кнопки снизу: играть, шагать, сдвигать, резать и объединять."),
            L("If the buttons are too small or too big, use the interface size button or Settings → View.",
                "Если кнопки слишком мелкие или крупные — кнопка масштаба интерфейса или Настройки → Вид."),
            touch = true),
        it(L("How do I save? Can I undo?", "Как сохранить? Можно ли отменить?"),
            L("{save} saves. Labels are also saved when another file is opened (this can be turned off in Settings → General).",
                "{save} сохраняет. Разметка также сохраняется при переходе к другому файлу (отключается в Настройки → Общие)."),
            L("{undo} undoes, {redo} redoes. Every action can be undone.", "{undo} — отмена, {redo} — возврат. Отменить можно любое действие.")),
        it(L("How do I find a command?", "Как найти нужную команду?"),
            L("{commands} opens the list of all commands with search. Type a part of the name and press Enter.",
                "{commands} открывает список всех команд с поиском. Наберите часть названия и нажмите Enter."),
            L("Every command from this help can be found there, with its key.", "Там есть все команды из этой справки вместе с клавишами.")),
    )),
    Topic(L("Labeling", "Разметка"), listOf(
        it(L("What are boundaries and parts?", "Что такое границы и части?"),
            L("Each lane is cut by boundaries into parts. Each part has a name: a phoneme, a word or a pause (often SP, AP or empty).",
                "Каждая полоса разрезана границами на части. У каждой части есть имя: фонема, слово или пауза (часто SP, AP или пусто)."),
            L("A click on a part selects it, a click near a boundary selects the boundary. {deselect} clears the selection.",
                "Клик по части выбирает её, клик рядом с границей — границу. {deselect} снимает выделение.")),
        it(L("How do I move a boundary?", "Как сдвинуть границу?"),
            L("Drag it. With Shift everything after it moves too; with Alt the same boundary on other lanes stays in place.",
                "Перетащите её. С Shift сдвигается всё, что после неё; с Alt граница на других полосах остаётся на месте."),
            L("{nudge-left} and {nudge-right} nudge the selected boundary a little, with Shift ten times more.",
                "{nudge-left} и {nudge-right} чуть сдвигают выбранную границу, с Shift — в десять раз сильнее."),
            L("{set-left} and {set-right} put the left or right boundary of the selected part at the cursor.",
                "{set-left} и {set-right} ставят левую или правую границу выбранной части под курсор.")),
        it(L("How do I add, remove and rename?", "Как добавить, удалить и переименовать?"),
            L("{split} splits the part at the cursor and asks for the new name. {merge} merges the part with the next one. {delete} removes the selected boundary.",
                "{split} режет часть под курсором и сразу спрашивает имя. {merge} объединяет часть со следующей. {delete} удаляет выбранную границу."),
            L("Double click or {rename} renames. After renaming, Space plays the part right away.",
                "Двойной клик или {rename} — переименовать. После переименования пробел сразу проигрывает часть."),
            L("A group of files can be renamed by a pattern ({batch-rename}).", "Метки можно переименовать по шаблону сразу во многих файлах ({batch-rename}).")),
        it(L("How do I work with a selected range?", "Как работать с выделенным куском?"),
            L("Drag over the waveform to select a range. Space plays it, {loop} repeats it.",
                "Протяните по волне, чтобы выделить кусок. Пробел играет его, {loop} — повтор."),
            L("With nothing else selected, {delete} removes all boundaries inside the range, and {nudge-left} / {nudge-right} move them together.",
                "Если больше ничего не выбрано, {delete} удаляет все границы внутри куска, а {nudge-left} / {nudge-right} сдвигают их вместе."),
            L("The boundaries inside the range can also be dragged together with the mouse: press inside the range and drag.",
                "Границы внутри куска можно и перетащить вместе мышью: нажмите внутри куска и тяните.")),
        it(L("Can I type the phonemes first?", "Можно ли сначала вписать фонемы?"),
            L("Yes. In the details panel, type the phonemes into “Phonemes in advance”, separated by spaces. Each new boundary then names its part with the next phoneme.",
                "Да. В панели свойств впишите фонемы в поле «Фонемы наперёд» через пробел. Каждая новая граница будет называть часть следующей фонемой."),
            L("“Spread over the selection” places all of them evenly over the selected range at once; then only the boundaries need moving.",
                "«Расставить по выделенному» сразу раскладывает их ровно по выделенному куску — останется только подвинуть границы.")),
        it(L("What are the mouse tools?", "Что за инструменты мыши?"),
            L("They change what the left button does: cursor (select and drag), scissors (a click adds a boundary), hand (drag scrolls), play (a click plays a part). Keys {tool-cursor}–{tool-play}.",
                "Они меняют действие левой кнопки: курсор (выбрать и тянуть), ножницы (клик ставит границу), рука (тянуть — прокрутка), проигрывание (клик играет часть). Клавиши {tool-cursor}–{tool-play}."),
            L("They are optional: Settings → Mouse. When they are off, the left button is always the cursor. Right and middle buttons, double click and clicks with Ctrl or Alt have their own actions there.",
                "Они необязательны: Настройки → Мышь. Если выключить, левая кнопка всегда работает как курсор. У правой и средней кнопок, двойного клика и кликов с Ctrl или Alt там же свои действия.")),
        it(L("What do the colours and marks mean?", "Что значат цвета и отметки?"),
            L("A part coloured as a warning has a problem found by the checks (see the Checks topic). Small triangles at the top and bottom of a lane show a part too short to see at this zoom.",
                "Часть, подсвеченная предупреждающим цветом, — проблема, найденная проверками (см. тему «Проверки»). Маленькие треугольники сверху и снизу полосы показывают часть, слишком короткую для этого масштаба."),
            L("{done} marks the file as finished, {star} adds a star. Both are shown in the file list, which can show only unfinished, starred or unlabelled files.",
                "{done} отмечает файл как готовый, {star} ставит звёздочку. Обе отметки видны в списке файлов; список может показать только неготовые, отмеченные или неразмеченные файлы.")),
        it(L("What do Ripple and Linked mean?", "Что значат «Сдвиг следом» и «Связь»?"),
            L("Ripple ({ripple}): moving a boundary moves everything after it. Linked ({linked}): boundaries at the same time on other lanes move together.",
                "«Сдвиг следом» ({ripple}): при сдвиге границы сдвигается всё, что после неё. «Связь» ({linked}): границы в то же время на других полосах двигаются вместе.")),
    )),
    Topic(L("Sound and view", "Звук и вид"), listOf(
        it(L("How do I listen?", "Как слушать?"),
            L("Space plays the selected part or range, Shift+Space plays from the cursor. {speed} switches the speed; {loop} repeats.",
                "Пробел играет выбранную часть или кусок, Shift+пробел — от курсора. {speed} меняет скорость, {loop} — повтор."),
            L("Volume, following the playhead and where it stays on screen: Settings → Editing → Playback.",
                "Громкость, следование за курсором воспроизведения и его место на экране: Настройки → Редактирование → Воспроизведение.")),
        it(L("What can be shown?", "Что можно показать?"),
            L("Waveform, spectrogram, pitch ({pitch}) and loudness. They can be laid over each other in one picture ({overlay}).",
                "Волну, спектрограмму, высоту тона ({pitch}) и громкость. Их можно наложить друг на друга в одну картинку ({overlay})."),
            L("Phoneme names can be drawn on the picture too; where exactly in each part is set with the dot pad in Settings → View.",
                "Имена фонем можно рисовать и на картинке; где именно внутри части — задаётся точкой на площадке в Настройки → Вид."),
            L("Label text size: {labels-bigger} and {labels-smaller}. Spectrogram colours and detail: Settings → Spectrogram.",
                "Размер текста меток: {labels-bigger} и {labels-smaller}. Цвета и детальность спектрограммы: Настройки → Спектрограмма.")),
        it(L("How do I compare with other labels?", "Как сравнить с другой разметкой?"),
            L("In the details panel, “Compare” shows the labels of the same files from another folder; differences are coloured.",
                "В панели свойств «Сравнение» показывает разметку тех же файлов из другой папки, различия подсвечены.")),
    )),
    Topic(L("Notes", "Ноты"), listOf(
        it(L("What are phoneme groups (ph_num)?", "Что такое группы фонем (ph_num)?"),
            L("DiffSinger needs to know which phonemes belong to one note. “Group phonemes by notes” builds the groups using the phoneme dictionary of the language.",
                "DiffSinger нужно знать, какие фонемы относятся к одной ноте. «Сгруппировать фонемы по нотам» строит группы по словарю фонем языка.")),
        it(L("How do I get notes?", "Как получить ноты?"),
            L("“Notes by groups and pitch” makes one note per group with the pitch taken from the recording. “Note pitch from the recording” only updates the pitch.",
                "«Ноты по группам и высоте» делает по ноте на группу, высоту берёт из записи. «Высота нот по записи» только обновляет высоту."),
            L("{note-up} and {note-down} move the selected note by a semitone. Notes can be saved to MIDI and loaded from a MIDI file next to the recording.",
                "{note-up} и {note-down} сдвигают выбранную ноту на полутон. Ноты можно сохранить в MIDI и загрузить из MIDI-файла рядом с записью.")),
        it(L("How do I edit notes and pitch on the piano roll?", "Как править ноты и высоту на пианоролле?"),
            L("Show the pitch in its own lane (View). It becomes a piano roll: keys on the left, one row per semitone, notes as bars. A click on the keys fits it to the singing; Alt+wheel moves it, Ctrl+Alt+wheel zooms.",
                "Покажите высоту тона отдельной полосой (Вид). Она станет пианороллом: клавиши слева, по ряду на полутон, ноты — полосками. Клик по клавишам подгоняет её под пение; Alt+колесо — сдвиг, Ctrl+Alt+колесо — масштаб."),
            L("Drag a note up or down to change its pitch (with Alt in cents, with Shift only the notes of the song's key), or drag its ends.",
                "Тяните ноту вверх или вниз, чтобы поменять высоту (с Alt — в центах, с Shift — только по нотам лада песни), или тяните её края."),
            L("Ctrl+click inside a note cuts it there (the second part becomes a slur). Right click on the border of two notes joins them; inside a note it puts back the pitch that was sung.",
                "Ctrl+щелчок внутри ноты режет её в этом месте (вторая часть становится слуром). Правый щелчок на границе двух нот склеивает их, внутри ноты — возвращает спетую высоту."),
            L("The pencil ({f0-pencil}) draws the pitch where the analysis is wrong; the right button erases the drawing; with Shift the line keeps to the notes of the key. The drawn pitch is kept for the file and used for notes from pitch.",
                "Карандаш ({f0-pencil}) рисует высоту там, где анализ ошибся; правая кнопка стирает нарисованное; с Shift линия держится нот лада. Нарисованная высота сохраняется для файла и используется для нот по высоте.")),
    )),
    Topic(L("Checks", "Проверки"), listOf(
        it(L("What do the checks find?", "Что находят проверки?"),
            L("Empty names, parts that are too short or too long, unknown phonemes, long pauses and long singing without a pause (DiffSinger works best with pieces up to about 15 s). Limits are set in Settings → Checks; 0 turns a check off.",
                "Пустые имена, слишком короткие или длинные части, незнакомые фонемы, длинные паузы и слишком долгое пение без паузы (для DiffSinger лучше куски до 15 с). Пороги — в Настройки → Проверки; 0 выключает проверку."),
            L("Found problems are listed in the details panel and counted in the status bar; a click on one goes to it.",
                "Найденное видно в панели свойств и в строке статуса; клик по проблеме переходит к ней."),
            L("For DiffSinger it also finds phonemes shorter than one frame, spaces inside a phoneme, two same pauses in a row and zero-length parts.",
                "Для DiffSinger ещё находит фонемы короче одного кадра, пробелы внутри фонемы, две одинаковые паузы подряд и части нулевой длины.")),
        it(L("How do I go through them quickly?", "Как быстро пройтись по ним?"),
            L("{review-next} selects the next place worth a look, worst first: errors, warnings, then the phonemes the aligner was least sure of. {review-prev} goes back.",
                "{review-next} выбирает следующее место для проверки, худшее первым: ошибки, предупреждения, потом фонемы, в которых выравниватель был меньше всего уверен. {review-prev} — назад.")),
        it(L("Is there enough to train on?", "Хватит ли данных для обучения?"),
            L("Tools → “Dataset summary” counts the length, every phoneme and the notes over the whole folder, shows rare phonemes and compares the phonemes with a dictionary. A click on a phoneme finds its files.",
                "Инструменты → «Сводка по датасету» считает длительность, каждую фонему и ноты по всей папке, показывает редкие фонемы и сравнивает фонемы со словарём. Клик по фонеме находит её файлы."),
            L("Tools → “Check the recordings” looks for clipping, quiet takes, noise, long silence, another sample rate and stereo.",
                "Инструменты → «Проверка звука» ищет перегруз, тихие дубли, шум, длинную тишину, другую частоту и стерео.")),
        it(L("Can I write my own check?", "Можно ли написать свою проверку?"),
            L("Yes, as a small JavaScript file. Put it into the checks folder of the program (for every folder) or into .mlabeler/checks inside a folder (for that folder only). Settings → Checks shows both folders and can create an example.",
                "Да, небольшим файлом на JavaScript. Положите его в папку проверок программы (для всех папок) или в .mlabeler/checks внутри папки (только для неё). В Настройки → Проверки указаны обе папки и можно создать пример.")),
    )),
    Topic(L("Autolabel", "Авторазметка"), listOf(
        it(L("What is needed?", "Что для этого нужно?"),
            L("Autolabel runs in mVocalToolkit, a separate helper program. Settings → Autolabel installs it, starts it and updates it.",
                "Авторазметка работает в mVocalToolkit — отдельной программе-помощнике. Настройки → Авторазметка устанавливает, запускает и обновляет её."),
            L("On a phone or tablet the helper runs on a computer in the same network: turn on “Let phones connect” on the computer and enter the shown address on the phone.",
                "На телефоне или планшете помощник работает на компьютере в той же сети: включите на компьютере «Разрешить подключение с телефона» и введите показанный адрес на телефоне.")),
        it(L("How do I run it?", "Как запустить?"),
            L("Select a range (or nothing for the whole file) and press {autolabel}. Pick a model and a language and start. Progress is shown in the window.",
                "Выделите кусок (или ничего — для всего файла) и нажмите {autolabel}. Выберите модель и язык и запустите. Ход работы виден в окне."),
            L("“Place a known text” (SOFA, HubertFA) needs the lyrics or phonemes: type them, load them from a file (lyrics, .lrc, .srt, .lab; the extra is removed) or let Whisper recognise the words first. “Recognise phonemes” (WFL) needs no text.",
                "«Расставить известный текст» (SOFA, HubertFA) нужен текст или фонемы: впишите, загрузите из файла (текст песни, .lrc, .srt, .lab; лишнее уберётся) или дайте Whisper сначала распознать слова. «Распознать фонемы» (WFL) текст не нужен."),
            L("“All files of the folder” labels them one by one and saves each; the text of a file can come from a .txt with the same name.",
                "«Все файлы папки» размечает их по очереди и сохраняет каждый; текст файла можно взять из .txt с тем же именем."),
            L("Known phonemes can be entered: the model then only places them instead of guessing. “Recognition settings” holds the confidence threshold, decoder and silence options.",
                "Если фонемы известны, их можно вписать — тогда модель только расставит их, а не будет угадывать. В «Настройках распознавания» — порог уверенности, декодер и параметры тишины.")),
        it(L("Where do models come from?", "Откуда брать модели?"),
            L("The autolabel window lists ready models; a model is downloaded the first time it is used.",
                "В окне авторазметки перечислены готовые модели; модель скачивается при первом использовании."),
            L("Your own model (a checkpoint, a folder or an archive) is added with “Add your own model…” or in Settings → Autolabel.",
                "Свою модель (чекпоинт, папку или архив) можно добавить ссылкой «Добавить свою модель…» или в Настройки → Авторазметка.")),
    )),
    Topic(L("Export and oto", "Экспорт и oto"), listOf(
        it(L("How do I make a DiffSinger dataset?", "Как сделать датасет для DiffSinger?"),
            L("“Export a DiffSinger dataset” writes the wav files and transcriptions.csv. Long recordings can be cut into segments at pauses, with a maximum length.",
                "«Экспорт датасета DiffSinger» сохраняет wav-файлы и transcriptions.csv. Длинные записи можно разрезать на куски по паузам, с ограничением длины.")),
        it(L("How do I edit oto.ini?", "Как править oto.ini?"),
            L("Folders with oto.ini open in the oto mode. Blue: offset and cutoff, green: overlap, red: preutterance, pink: the consonant part.",
                "Папки с oto.ini открываются в режиме oto. Синие — offset и cutoff, зелёная — overlap, красная — preutterance, розовая область — согласная."),
            L("Dragging preutterance moves all markers ({oto-lock} switches this). Q W E R T put offset, overlap, preutterance, consonant and cutoff at the cursor.",
                "Preutterance тащит за собой все маркеры ({oto-lock} переключает). Q W E R T ставят offset, overlap, preutterance, consonant и cutoff под курсор."),
            L("↑ ↓ go through the entries. {oto-add} adds one, {oto-duplicate} copies, {oto-delete} removes. “Automatic oto” makes entries from the file names and recordings.",
                "↑ ↓ — по записям. {oto-add} — новая, {oto-duplicate} — копия, {oto-delete} — удалить. «Автоматическое oto» создаёт записи по именам файлов и записям.")),
        it(L("Can I bring a vLabeler project?", "Можно ли перенести проект vLabeler?"),
            L("Yes: “Import a vLabeler project” reads an .lbp file.", "Да: «Импорт проекта vLabeler» читает файл .lbp.")),
    )),
    Topic(L("Tools", "Инструменты"), listOf(
        it(L("How do I record?", "Как записывать?"),
            L("“Record” opens the recorder: it shows the line to sing, records into the folder and can move to the next line. The list of lines is kept as reclist.txt.",
                "«Запись» открывает рекордер: он показывает строку, которую нужно спеть, пишет в папку и может сам перейти к следующей. Список строк хранится в reclist.txt."),
            L("R records and stops, Space plays the take, ↑ ↓ change the line. A metronome, count-in and a guide WAV are available.",
                "R — запись и стоп, пробел — прослушать дубль, ↑ ↓ — другая строка. Есть метроном, отсчёт и направляющий WAV.")),
        it(L("How do I clean a recording?", "Как почистить запись?"),
            L("{cleanup} finds and repairs clicks and lowers noise (the noise is taken from a selected pause). The original can be put back.",
                "{cleanup} находит и чинит щелчки и снижает шум (шум берётся из выделенной паузы). Исходник можно вернуть."),
            L("{mute} turns the selection (or the selected phoneme) into silence; {cut-audio} cuts it out of the file and moves the labels after it back. Both are undone with {undo}.",
                "{mute} превращает выделенное (или выбранную фонему) в тишину; {cut-audio} вырезает его из файла и сдвигает разметку после него назад. Оба отменяются через {undo}."),
            L("{reload-audio} reads the recording again after it was changed in another program.", "{reload-audio} перечитывает запись, если её поменяли в другой программе.")),
        it(L("What are plugins?", "Что такое плагины?"),
            L("Small scripts that change labels: rename, shift, fill and so on ({plugins}). Up to four of them can be put on quick keys {plugin-slot-1}–{plugin-slot-4}.",
                "Небольшие скрипты, которые меняют разметку: переименовать, сдвинуть, заполнить и так далее ({plugins}). До четырёх можно повесить на быстрые клавиши {plugin-slot-1}–{plugin-slot-4}.")),
    )),
    Topic(L("Settings", "Настройки"), listOf(
        it(L("What are environments?", "Что такое рабочие среды?"),
            L("A ready set of panels, lanes, toolbar buttons, bars and the mouse tools switch. Each card lists what it contains.",
                "Готовый набор панелей, полос, кнопок, строк меню и статуса и переключателя инструментов мыши. На каждой карточке перечислено, что в неё входит."),
            L("Theme, language, keys and labeling options are not part of an environment and stay as they are. Your own setup can be saved as an environment and shared as a file.",
                "Тема, язык, клавиши и настройки разметки в среду не входят и остаются как есть. Свою настройку можно сохранить как среду и передать файлом.")),
        it(L("How do I change the look?", "Как поменять внешний вид?"),
            L("Settings → Themes for colours, Settings → View for interface size and font.", "Настройки → Темы — цвета, Настройки → Вид — размер интерфейса и шрифт.")),
        it(L("How do I change keys and mouse buttons?", "Как поменять клавиши и кнопки мыши?"),
            L("Settings → Shortcuts: click a command and press the new key. Settings → Mouse: actions of the wheel, right and middle buttons, double click and clicks with Ctrl or Alt.",
                "Настройки → Сочетания клавиш: нажмите на команду и затем новую клавишу. Настройки → Мышь: действия колеса, правой и средней кнопок, двойного клика и кликов с Ctrl или Alt."),
            L("Every settings page has a button that returns its defaults.", "На каждой странице настроек есть кнопка возврата к исходным значениям.")),
    )),
)

private val OTO_TOPIC = 6

/** Current key of a command, or an empty string. */
private fun keyOf(id: String): String = Commands.all.firstOrNull { it.id == id }?.keyLabel ?: ""

private val placeholder = Regex("\\{([a-z0-9-]+)\\}")

private fun fill(text: String): String = placeholder.replace(text) { m ->
    val k = keyOf(m.groupValues[1])
    if (k.isEmpty()) "" else k
}.replace("()", "").replace("( )", "").replace(Regex(" {2,}"), " ").replace(" .", ".").replace(" ,", ",")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpDialog(app: AppState) {
    val c = T.c
    val mobile = Platform.isMobile
    val startTopic = if (app.editor?.mode == Mode.Oto) OTO_TOPIC else 0
    var topic by remember { mutableStateOf(startTopic) }
    var open by remember { mutableStateOf(setOf<String>()) }
    Overlay({ app.showHelp = false; app.editor?.requestFocus?.invoke() }, 720) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row {
                Text(helpTitle(), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.close, S.close()) { app.showHelp = false }
            }
            Text(helpIntro(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, t) in topics.withIndex()) Chip(t.title(), i == topic) { topic = i }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((n, item) in topics[topic].items.withIndex()) {
                    if (item.touch != null && item.touch != mobile) continue
                    val key = "$topic/$n"
                    val expanded = key in open
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (expanded) open - key else open + key }.padding(vertical = 8.dp),
                    ) {
                        Text(if (expanded) "▾" else "▸", color = c.accent, fontSize = 14.sp, modifier = Modifier.width(18.dp))
                        Text(item.q(), color = c.text, fontSize = 14.sp)
                    }
                    if (expanded) Column(Modifier.padding(start = 18.dp, bottom = 6.dp)) {
                        for (l in item.lines) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("·", color = c.accent, fontSize = 14.sp, modifier = Modifier.width(14.dp))
                            Text(fill(l()), color = c.text, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
