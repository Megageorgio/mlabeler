package mlabeler.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Lang {
    var current by mutableStateOf("en")
    val available = listOf("en" to "English", "ru" to "Русский")
}

/** A UI string in every bundled language. Reading it inside a composable follows language changes. */
class L(private val en: String, private val ru: String) {
    operator fun invoke(): String = if (Lang.current == "ru") ru else en
    fun format(vararg args: Any?): String {
        var s = invoke()
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

/** Glossary: workspace = folder, item = file, tier, interval, boundary. Plain words, no exclamations. */
object S {
    val appName = L("mLabeler", "mLabeler")

    // start
    val openFolder = L("Open folder", "Открыть папку")
    val openFolderHint = L("Pick any recording in the folder: the whole folder opens, labels next to the recordings are found automatically.",
        "Выберите любую запись в папке: откроется вся папка, разметка рядом с записями найдётся сама.")
    val recent = L("Recent", "Недавние")
    val noRecent = L("Folders you open appear here.", "Здесь появятся открытые папки.")
    val removeFromList = L("Remove from list", "Убрать из списка")
    val systemDialog = L("System dialog…", "Системный диалог…")
    val chooseThisFolder = L("Open this folder", "Открыть эту папку")
    val up = L("Up", "Наверх")
    val audioFiles = L("{0} audio files", "аудиофайлов: {0}")
    val folderEmpty = L("No folders here", "Здесь нет папок")
    val allowFiles = L("Allow access to files", "Разрешить доступ к файлам")
    val allowFilesHint = L("mLabeler reads recordings and writes labels in the folders you open. Android asks for access to all files for that.",
        "mLabeler читает записи и сохраняет разметку в открытых папках. Для этого Android просит доступ ко всем файлам.")

    // files
    val files = L("Files", "Файлы")
    val search = L("Search", "Поиск")
    val all = L("All", "Все")
    val notDone = L("Not done", "Не готовые")
    val starred = L("Starred", "Отмеченные")
    val noLabels = L("No labels", "Без разметки")
    val doneCount = L("{0} of {1} done", "готово {0} из {1}")
    val noFiles = L("No audio files in this folder.", "В этой папке нет аудиофайлов.")
    val nothingFound = L("Nothing found", "Ничего не найдено")

    // inspector
    val inspector = L("Details", "Свойства")
    val file = L("File", "Файл")
    val labels = L("Labels", "Разметка")
    val notSavedYet = L("not saved yet", "ещё не сохранена")
    val done = L("Done", "Готово")
    val star = L("Star", "Отметка")
    val tag = L("Tag", "Метка")
    val interval = L("Interval", "Интервал")
    val boundary = L("Boundary", "Граница")
    val text = L("Text", "Текст")
    val start = L("Start", "Начало")
    val end = L("End", "Конец")
    val length = L("Length", "Длина")
    val time = L("Time", "Время")
    val confidence = L("Confidence", "Уверенность")
    val nothingSelected = L("Click an interval or a boundary.", "Выберите интервал или границу.")
    val tiers = L("Tiers", "Слои")
    val addTier = L("Add tier", "Добавить слой")
    val copyBounds = L("Copy boundaries", "Копия границ")
    val renameTier = L("Rename", "Переименовать")
    val deleteTier = L("Delete", "Удалить")
    val moveUp = L("Move up", "Выше")
    val moveDown = L("Move down", "Ниже")
    val problems = L("Problems", "Проблемы")
    val noProblems = L("No problems found.", "Проблем не найдено.")
    val probShort = L("Too short", "Слишком короткий")
    val probEmpty = L("Empty", "Пустой")
    val probUnknown = L("Unknown phoneme", "Неизвестная фонема")
    val probConfidence = L("Low confidence", "Низкая уверенность")
    val probEdge = L("No pause at the edge", "Нет паузы на краю")

    // editor
    val play = L("Play", "Воспроизвести")
    val stop = L("Stop", "Стоп")
    val loop = L("Loop", "Повтор")
    val ripple = L("Ripple", "Сдвиг следом")
    val rippleHint = L("Moving a boundary moves everything after it. Hold Shift to switch while dragging.",
        "Граница двигает всё, что после неё. Shift при перетаскивании — наоборот.")
    val linked = L("Linked", "Связь")
    val linkedHint = L("Boundaries at the same time in other tiers move together. Hold Alt to switch while dragging.",
        "Границы в том же месте других слоёв двигаются вместе. Alt при перетаскивании — наоборот.")
    val undo = L("Undo", "Отменить")
    val redo = L("Redo", "Повторить")
    val save = L("Save", "Сохранить")
    val split = L("Split", "Разрезать")
    val merge = L("Merge", "Объединить")
    val removeBoundary = L("Remove boundary", "Удалить границу")
    val rename = L("Rename", "Переименовать")
    val nudgeLeft = L("Nudge left", "Сдвинуть влево")
    val nudgeRight = L("Nudge right", "Сдвинуть вправо")
    val prevFile = L("Previous file", "Предыдущий файл")
    val nextFile = L("Next file", "Следующий файл")
    val prevInterval = L("Previous interval", "Предыдущий интервал")
    val nextInterval = L("Next interval", "Следующий интервал")
    val zoomIn = L("Zoom in", "Приблизить")
    val zoomOut = L("Zoom out", "Отдалить")
    val zoomFit = L("Whole file", "Весь файл")
    val zoomSelection = L("Zoom to selection", "К выделению")
    val setLeft = L("Set left boundary here", "Левая граница сюда")
    val setRight = L("Set right boundary here", "Правая граница сюда")
    val toggleDone = L("Mark as done", "Отметить готовым")
    val toggleStar = L("Star", "Поставить отметку")
    val editTag = L("Edit tag", "Изменить метку")
    val commands = L("Commands", "Команды")
    val typeCommand = L("Type a command", "Введите команду")
    val settings = L("Settings", "Настройки")
    val closeFolder = L("Close folder", "Закрыть папку")
    val showInFolder = L("Show in folder", "Показать в папке")
    val toggleFiles = L("Show files", "Показать файлы")
    val toggleInspector = L("Show details", "Показать свойства")
    val waveform = L("Waveform", "Волна")
    val spectrogram = L("Spectrogram", "Спектрограмма")
    val more = L("More", "Ещё")
    val back = L("Back", "Назад")
    val close = L("Close", "Закрыть")
    val cancel = L("Cancel", "Отмена")
    val ok = L("OK", "ОК")
    val loading = L("Loading…", "Загрузка…")
    val analysing = L("Building spectrogram…", "Строится спектрограмма…")
    val saved = L("Saved {0}", "Сохранено: {0}")
    val savedAs = L("Saved as {0}", "Сохранено как {0}")
    val unsaved = L("Unsaved changes", "Есть несохранённые изменения")
    val cannotOpen = L("Cannot open {0}: {1}", "Не удалось открыть {0}: {1}")
    val cannotSave = L("Cannot save: {0}", "Не удалось сохранить: {0}")
    val cannotPlay = L("Playback failed: {0}", "Не удалось воспроизвести: {0}")
    val unsupportedAudio = L("This audio format is not supported here. Convert it to WAV.",
        "Этот формат здесь не поддерживается. Сконвертируйте в WAV.")
    val labelsUnreadable = L("Labels could not be read: {0}. Saving will replace them.",
        "Разметку не удалось прочитать: {0}. При сохранении она будет заменена.")
    val newTierName = L("tier {0}", "слой {0}")

    // settings
    val language = L("Language", "Язык")
    val theme = L("Theme", "Тема")
    val themeModernDark = L("Dark", "Тёмная")
    val themeModernLight = L("Light", "Светлая")
    val themeRetro = L("Retro", "Ретро")
    val themeContrast = L("High contrast", "Контрастная")
    val interfaceScale = L("Interface size", "Размер интерфейса")
    val editing = L("Editing", "Редактирование")
    val nudgeStep = L("Nudge step, ms", "Шаг сдвига, мс")
    val minInterval = L("Shortest interval, ms", "Минимальный интервал, мс")
    val saveOnSwitch = L("Save when switching files", "Сохранять при переходе к другому файлу")
    val newFilesFormat = L("Format for new labels", "Формат новой разметки")
    val view = L("View", "Вид")
    val colors = L("Colours", "Цвета")
    val brightness = L("Brightness", "Яркость")
    val contrast = L("Contrast", "Контраст")
    val maxFrequency = L("Highest frequency, Hz", "Верхняя частота, Гц")
    val shortcuts = L("Shortcuts", "Сочетания клавиш")
    val checks = L("Checks", "Проверки")
    val shortThreshold = L("Warn about intervals shorter than, ms", "Предупреждать об интервалах короче, мс")
    val phonemeSet = L("Phoneme set (space separated, empty = any)", "Набор фонем (через пробел, пусто — любые)")
    val general = L("General", "Общие")
    val spectrogramSection = L("Spectrogram", "Спектрограмма")
    val about = L("About", "О программе")
    val aboutText = L("Editor for singing voice labels.", "Редактор разметки певческого голоса.")
}
