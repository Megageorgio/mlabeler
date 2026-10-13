package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.check.ModelScore
import mlabeler.core.check.PhonemeScore
import mlabeler.core.ds.Dataset
import mlabeler.core.io.Item
import mlabeler.core.io.Paths
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc

/**
 * Several phoneme recognisers (no text: WFL-ASR, MCFL, the built-in quick model) run on the same audio of one
 * language, each scored against the phonemes of the labels, which are taken as the truth: phoneme error rate,
 * boundary errors, speed. Nothing in the labels changes; a model's result on the open file can be shown as a
 * comparison tier.
 */
class ModelComparison(private val ed: EditorState) {
    data class Candidate(val id: String, val name: String, val engine: String, val author: String = "")

    /** One model's result over everything compared. */
    data class Row(val model: Candidate, val score: PhonemeScore, val seconds: Double, val audioSeconds: Double, val files: Int, val failed: List<String>) {
        /** How many times faster than real time the model labelled (including the toolkit's overheads). */
        val speed: Double get() = if (seconds > 0) audioSeconds / seconds else 0.0
    }

    enum class Scope { Selection, File, Folder }

    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var fraction by mutableStateOf(0.0)
        private set
    val rows = mutableStateListOf<Row>()
    /** Models ticked in the window (kept while the folder is open). */
    val chosen = mutableStateListOf<String>()
    var language by mutableStateOf("")
    /** The open file's results per model (only when it was compared), for [show]. */
    private val openResults = mutableMapOf<String, Pair<LabelDoc, Pair<Double, Double>>>()
    var openFile: String? by mutableStateOf(null)
        private set
    private var job: Job? = null

    fun canShow(modelId: String) = openFile == ed.item?.id && modelId in openResults

    /** The model's phonemes on the open file as a comparison tier under the labels. */
    fun show(m: Candidate) {
        val it = ed.item ?: return
        val (doc, range) = openResults[m.id] ?: return
        ed.modelResults[it.id] = ed.modelResults[it.id].orEmpty().filter { r -> r.name != m.name } + EditorState.Reference(m.name, "", doc, range)
        ed.loadReferences()
    }

    fun stop() {
        job?.cancel()
    }

    fun start(language: String, models: List<Candidate>, scope: Scope, selected: Pair<Double, Double>?) {
        if (running || models.isEmpty()) return
        val open = ed.item
        val files: List<Item> = when (scope) {
            Scope.Folder -> ed.items.filter { it.labelPath != null || it.id == open?.id }
            else -> listOfNotNull(open)
        }
        if (files.isEmpty()) return
        rows.clear()
        openResults.clear()
        openFile = null
        running = true
        fraction = 0.0
        ed.describeWork("Comparing models", models.joinToString { it.name })
        job = ed.workScope.launch {
            val scores = models.associate { it.id to PhonemeScore.EMPTY }.toMutableMap()
            val seconds = models.associate { it.id to 0.0 }.toMutableMap()
            val audioSec = models.associate { it.id to 0.0 }.toMutableMap()
            val done = models.associate { it.id to 0 }.toMutableMap()
            val failed = models.associate { it.id to mutableListOf<String>() }
            val toolkitModels = models.filter { it.id != BUILTIN }
            val client = if (toolkitModels.isEmpty()) null else ed.app.toolkit.client()
            var serverJob: String? = null
            fun publish() {
                rows.clear()
                rows.addAll(models.map { m -> Row(m, scores[m.id]!!, seconds[m.id]!!, audioSec[m.id]!!, done[m.id]!!, failed[m.id]!!) }
                    .sortedWith(compareBy({ it.files == 0 }, { it.score.per }, { it.score.meanMs })))
            }
            try {
                if (client != null) {
                    progress = mlabeler.app.toolkit.ToolkitManager.starting()
                    if (!ed.app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(ed.app.toolkit.statusText())
                }
                for ((n, f) in files.withIndex()) {
                    progress = fileT.format(n + 1, files.size, f.name)
                    val isOpen = f.id == open?.id
                    val a: Audio = if (isOpen && ed.audio != null) ed.audio!! else withContext(Dispatchers.Default) {
                        val bytes = ed.workspace.fs.read(f.audioPath)
                        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
                    }
                    val labels = if (isOpen) ed.doc else runCatching { withContext(Dispatchers.Default) { ed.workspace.readLabels(f, a.duration) } }.getOrNull()
                    val ref = labels?.let { d -> d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier }
                    if (ref == null || ref.texts.none { it.isNotBlank() && it.trim() !in ModelScore.PAUSES }) continue
                    val (from, to) = if (scope == Scope.Selection && selected != null) selected else 0.0 to a.duration
                    val part = withContext(Dispatchers.Default) { Dataset.cutAudio(a, from, to, 0) }
                    var fileId: String? = null
                    for ((k, m) in models.withIndex()) {
                        progress = fileT.format(n + 1, files.size, f.name) + " · " + m.name
                        fraction = (n + k.toDouble() / models.size) / files.size
                        val t0 = ed.now()
                        try {
                            val hyp: IntervalTier = if (m.id == BUILTIN) {
                                val model = QuickLabel.model() ?: error(QuickLabel.noModel())
                                val segs = withContext(Dispatchers.Default) { model.label(part, language, threads = Platform.cores.coerceIn(1, 8)) }
                                IntervalTier.fromIntervals("phones", segs.map { Triple(from + it.start, from + it.end, it.label) }, from + part.duration, null)
                            } else {
                                val c = client!!
                                if (fileId == null) fileId = c.upload(Paths.stem(f.audioPath) + "_compare.wav", withContext(Dispatchers.Default) { Wav.encode16(part) })
                                val jobId = c.segment(fileId!!, m.id, language, emptyList(), ed.settings.toolkit.wfl, null)
                                serverJob = jobId
                                val result = c.await(jobId) { _, _ -> }
                                serverJob = null
                                val d = mlabeler.app.toolkit.ToolkitClient.labelOf(result, from, part.duration)
                                (d.tiers.firstOrNull { (it as? IntervalTier)?.name == "phones" } ?: d.tiers.getOrNull(d.phonemeTierIndex())) as? IntervalTier
                                    ?: error("no phonemes in the result")
                            }
                            seconds[m.id] = seconds[m.id]!! + (ed.now() - t0) / 1000.0
                            audioSec[m.id] = audioSec[m.id]!! + part.duration
                            scores[m.id] = scores[m.id]!! + ModelScore.score(ref, hyp, from, to)
                            done[m.id] = done[m.id]!! + 1
                            if (isOpen) {
                                openResults[m.id] = LabelDoc(listOf(hyp)) to (from to to)
                                openFile = f.id
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed[m.id]!!.add(f.name + ": " + (e.message ?: e.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                        }
                        publish()
                    }
                }
                publish()
                progress = if (rows.none { it.files > 0 }) nothingT() else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { runCatching { client?.cancel(id) } } }
                progress = stoppedT()
                throw e
            } catch (e: Exception) {
                progress = e.message ?: e.toString()
            } finally {
                running = false
                fraction = 0.0
            }
        }
    }

    companion object {
        /** The model that comes with mLabeler (quick labelling). */
        const val BUILTIN = "builtin:mcfl"
        private val fileT = L("File {0} of {1}: {2}", "Файл {0} из {1}: {2}")
        private val stoppedT = L("Stopped: the table has what was done", "Остановлено: в таблице то, что успели")
        private val nothingT = L("Nothing compared: no file has phonemes to compare with", "Нечего сравнивать: ни в одном файле нет фонем-эталона")
    }
}
