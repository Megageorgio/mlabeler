package mlabeler.app.toolkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import mlabeler.app.i18n.L

/**
 * A toolkit job while it runs: how far (0..1), its step ("download", "align"...), the toolkit's own line about it
 * (English) and the numbers of the step (bytes of a download, files done), when the toolkit gives them.
 */
data class JobProgress(val fraction: Double, val stage: String, val message: String, val detail: JsonObject?) {
    private fun long(k: String) = (detail?.get(k) as? JsonPrimitive)?.longOrNull
    private fun double(k: String) = (detail?.get(k) as? JsonPrimitive)?.doubleOrNull

    val bytesDone: Long? get() = long("done")
    val bytesTotal: Long? get() = long("total")
    /** Bytes per second. */
    val speed: Double? get() = double("speed")
    /** Seconds left. */
    val eta: Long? get() = long("eta")
    val file: String? get() = (detail?.get("file") as? JsonPrimitive)?.content
    val fileIndex: Long? get() = long("file_index")
    val files: Long? get() = long("files")
    val itemsDone: Long? get() = long("items_done")
    val itemsTotal: Long? get() = long("items_total")

    /** The step in words of the interface language. */
    fun stageName(): String = when (stage) {
        "model" -> T.model()
        "download" -> T.download()
        "install" -> T.install()
        "transcribe" -> T.transcribe()
        "frontend" -> T.frontend()
        "align" -> T.align()
        "segment" -> T.segment()
        "refine" -> T.refine()
        "separate" -> T.separate()
        "pitch" -> T.pitch()
        "midi" -> T.midi()
        "resynth" -> T.resynth()
        "" -> mlabeler.app.i18n.S.toolkit()
        else -> stage.replaceFirstChar { it.uppercase() }
    }

    /**
     * The numbers of the step for a second line: "45.2 of 120 MB · 3.4 MB/s · 0:22 left", "File 3 of 10"; for an
     * engine being installed the installer's own last line. Null when there is nothing to add.
     */
    fun numbers(): String? {
        val done = bytesDone
        if (done != null) {
            val total = bytesTotal
            val parts = mutableListOf(if (total != null && total > 0) T.ofMb.format(mb(done), mb(total)) else T.mbOnly.format(mb(done)))
            speed?.takeIf { it > 1024 }?.let { parts += T.perSecond.format(mb(it.toLong())) }
            eta?.let { parts += T.left.format("${it / 60}:${(it % 60).toString().padStart(2, '0')}") }
            return parts.joinToString("  ·  ")
        }
        val n = itemsTotal
        if (n != null && n > 1) return T.filesOf.format(((itemsDone ?: 0) + 1).coerceAtMost(n), n)
        if (stage == "install" && message.isNotBlank()) return message.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
        return null
    }

    /** The file being downloaded: "model.zip (2 of 3)". */
    fun fileLine(): String? {
        val f = file ?: return null
        val count = files ?: 1
        return if (count > 1) "$f (${T.nOf.format(fileIndex ?: 1, count)})" else f
    }

    private fun mb(bytes: Long): String {
        val v = bytes / 1048576.0
        return if (v >= 100) v.toLong().toString() else ((v * 10).toLong() / 10.0).toString()
    }

    private object T {
        val model = L("Getting the model ready", "Подготовка модели")
        val download = L("Downloading the model", "Скачивание модели")
        val install = L("Installing the engine (first use only)", "Установка движка (только в первый раз)")
        val transcribe = L("Recognising the words", "Распознавание слов")
        val frontend = L("Turning the text into phonemes", "Перевод текста в фонемы")
        val align = L("Placing the phonemes", "Расстановка фонем")
        val segment = L("Recognising the phonemes", "Распознавание фонем")
        val refine = L("Refining the boundaries", "Уточнение границ")
        val separate = L("Separating the voice", "Отделение голоса")
        val pitch = L("Finding the pitch", "Определение высоты тона")
        val midi = L("Finding the notes", "Определение нот")
        val resynth = L("Singing it again", "Пересинтез")
        val ofMb = L("{0} of {1} MB", "{0} из {1} МБ")
        val mbOnly = L("{0} MB", "{0} МБ")
        val perSecond = L("{0} MB/s", "{0} МБ/с")
        val left = L("{0} left", "осталось {0}")
        val filesOf = L("File {0} of {1}", "Файл {0} из {1}")
        val nOf = L("{0} of {1}", "{0} из {1}")
    }
}
