package mlabeler.app.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.builtinAsset
import mlabeler.app.i18n.L
import mlabeler.core.ds.Dataset
import mlabeler.core.mcfl.Mcfl
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/**
 * Quick labelling: phonemes of the selected part (or of the whole file) by the MCFL model that comes with the
 * program, in the language chosen next to the button. No toolkit, no text: it runs right here, on a computer or a
 * phone, and the result goes straight into the labels (Ctrl+Z undoes it).
 */
object QuickLabel {
    const val MODEL = "MCFL_multi13_s_v0.0.1"
    /** The release files of the model (github.com/Megageorgio/MCFL, release Multi13_v0.0.1). */
    private const val DIR = "mcfl/$MODEL"

    /** The model's languages. */
    val codes = listOf("de", "el", "en", "es", "fr", "it", "ja", "ko", "nan", "pt", "ru", "yue", "zh")

    /** The languages with their names in the language of the interface, by name. */
    fun languages(): List<Pair<String, String>> = codes.map { it to mlabeler.app.i18n.LanguageNames.of(it) }.sortedBy { it.second.lowercase() }

    /** The chosen language: the saved one, else that of the interface if the model knows it, else English. */
    fun language(s: AppSettings): String {
        return s.quick.language.takeIf { it in codes }
            ?: s.language.ifEmpty { Platform.systemLanguage }.takeIf { it in codes } ?: "en"
    }

    private val lock = Mutex()
    private var model: Mcfl? = null

    /** The model, read once (a few MB); null when this build has none. */
    suspend fun model(): Mcfl? = lock.withLock {
        model ?: withContext(Dispatchers.Default) {
            val config = builtinAsset("$DIR/model.json") ?: return@withContext null
            val weights = builtinAsset("$DIR/model.safetensors") ?: return@withContext null
            Mcfl(config.decodeToString(), weights)
        }.also { model = it }
    }

    val working = L("Quick labelling", "Быстрая разметка")
    val done = L("Quick labelling finished ({0}). Ctrl+Z undoes it.", "Быстрая разметка готова ({0}). Отмена — Ctrl+Z.")
    val noModel = L("This build has no quick labelling model", "В этой сборке нет модели быстрой разметки")
}

/**
 * Labels the selected part (the range, else the selected interval, else the whole file) with the built-in MCFL
 * model in [language]; the phonemes replace that part of the phoneme tier.
 */
fun EditorState.quickLabel(language: String) {
    val a = audio ?: return
    val (from, to) = range ?: selectedSpan() ?: (0.0 to duration)
    if (to - from < 0.05) return
    describeWork("Quick labelling", QuickLabel.MODEL)
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        try {
            beginToolkitWork(QuickLabel.working())
            val m = QuickLabel.model() ?: throw IllegalStateException(QuickLabel.noModel())
            val part = withContext(Dispatchers.Default) { Dataset.cutAudio(a, from, to, 0) }
            val segs = withContext(Dispatchers.Default) {
                m.label(part, language, threads = Platform.cores.coerceIn(1, 8)) { p -> toolkitProgress = p }
            }
            val tier = IntervalTier.fromIntervals("phones", segs.map { Triple(from + it.start, from + it.end, it.label) }, from + part.duration, null)
            placeLabels(LabelDoc(listOf(tier)), from, to)
            app.message(QuickLabel.done.format(language))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}
