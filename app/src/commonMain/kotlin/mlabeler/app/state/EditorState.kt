package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import mlabeler.core.io.decodeGuess
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.AudioOut
import mlabeler.app.Platform
import mlabeler.app.i18n.S
import mlabeler.app.i18n.L
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.check.Checks
import mlabeler.core.check.Problem
import mlabeler.core.dsp.Peaks
import mlabeler.core.dsp.Spectrogram
import mlabeler.core.edit.BoundRef
import mlabeler.core.edit.Edits
import mlabeler.core.edit.History
import mlabeler.core.edit.IntervalRef
import mlabeler.core.edit.MoveOptions
import mlabeler.core.io.Item
import mlabeler.core.io.ItemMarks
import mlabeler.core.io.Paths
import mlabeler.core.io.AUDIO_EXTENSIONS
import mlabeler.core.io.ALL_AUDIO_EXTENSIONS
import mlabeler.core.io.Workspace
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.math.max
import kotlin.math.min

sealed interface Selection {
    data object None : Selection
    data class Interval(val ref: IntervalRef) : Selection
    data class Bound(val ref: BoundRef) : Selection
    /** A note of a notes tier. */
    data class Note(val tier: Int, val index: Int) : Selection
}

enum class FileFilter { All, NotDone, Starred, NoLabels }

enum class Mode { Labels, Oto }

private val dropAdded = L("Added to the folder {1}: {0}", "Добавлено в папку {1}: {0}")
private val dropNoAudio = L("Only recordings can be added here", "Сюда можно добавить только записи")
private val dropOtherAudio = L("Turn on other audio formats in Settings → General to add these", "Чтобы добавить такие файлы, включите другие форматы в Настройках → Общие")
private val removedInRange = L("Boundaries removed in the selected part: {0}", "Убрано границ в выделенном куске: {0}")
private val queueEmpty = L("Type the phonemes first", "Сначала впишите фонемы")
private val queueNoPlace = L("Select a part or a phoneme to fill", "Выделите кусок или фонему, которую заполнить")
private val grouped = L("Phonemes are grouped into notes (the words tier)", "Фонемы сгруппированы по нотам (слой words)")

class EditorState(
    val workspace: Workspace,
    val app: AppState,
    private val scope: CoroutineScope,
) {
    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    val item: Item? get() = items.getOrNull(index)

    var audio by mutableStateOf<Audio?>(null)
        private set
    var peaks by mutableStateOf<Peaks?>(null)
        private set
    var spectrogram by mutableStateOf<Spectrogram?>(null)
        private set
    /** Bumped while the spectrogram is being filled in. */
    var spectrogramProgress by mutableIntStateOf(0)
        private set
    var pitch by mutableStateOf<mlabeler.core.dsp.Curve?>(null)
        private set

    // ---------- f0 drawn by hand (piano roll) ----------

    /** Hand-drawn f0 over the analysed one (Hz, NaN = not changed; 0 = unvoiced), same frames as [pitch]. */
    var f0Edits by mutableStateOf<FloatArray?>(null)
        private set
    private val f0Undo = ArrayDeque<FloatArray?>()
    /** Drawing f0 with the mouse in the pitch lane instead of selecting. */
    var f0Pencil by mutableStateOf(false)
    /** Visible range of the pitch lane, MIDI note numbers. */
    var pitchLo by mutableStateOf(48.0)
    var pitchHi by mutableStateOf(72.0)

    /** The f0 shown and used for notes: the analysed one with the drawn parts over it. */
    val pitchCurve: mlabeler.core.dsp.Curve?
        get() {
            val p = pitch ?: return null
            val e = f0Edits ?: return p
            return mlabeler.core.dsp.Curve(p.hop, FloatArray(p.values.size) { i -> e.getOrNull(i)?.takeIf { !it.isNaN() } ?: p.values[i] })
        }

    private fun f0Path(id: String) = Paths.join(Paths.join(workspace.metaDir, "f0"), id.replace('/', '_').replace('\\', '_') + ".f0")

    private fun loadF0Edits() {
        val it = item ?: return
        val p = pitch ?: return
        f0Undo.clear()
        f0Edits = runCatching {
            val text = workspace.fs.read(f0Path(it.id)).decodeToString()
            val lines = text.lines().filter { l -> l.isNotBlank() }
            // "hop <seconds>" then "frame value" lines
            val hop = lines.first().removePrefix("hop").trim().toDouble()
            val out = FloatArray(p.values.size) { Float.NaN }
            for (l in lines.drop(1)) {
                val (t, v) = l.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toFloat() }
                val i = (t / p.hop).toInt()
                if (i in out.indices) out[i] = v
            }
            if (hop > 0) out else null
        }.getOrNull()
    }

    private fun saveF0Edits() {
        val it = item ?: return
        val p = pitch ?: return
        val e = f0Edits
        val path = f0Path(it.id)
        runCatching {
            if (e == null || e.all { v -> v.isNaN() }) { if (workspace.fs.exists(path)) workspace.fs.delete(path); return }
            val sb = StringBuilder("hop ${p.hop}\n")
            for (i in e.indices) if (!e[i].isNaN()) sb.append(((i * p.hop * 10000).toLong() / 10000.0)).append(' ').append(e[i]).append('\n')
            workspace.fs.mkdirs(Paths.parent(path))
            workspace.fs.write(path, sb.toString().encodeToByteArray())
        }
    }

    /** Shows the sung range in the pitch lane (a few semitones around it). */
    fun fitPitchRange() {
        val v = pitchCurve?.values?.filter { it > 0f && !it.isNaN() }?.map { mlabeler.core.dsp.Pitch.hzToMidi(it.toDouble()) }?.sorted()
        if (v.isNullOrEmpty()) { pitchLo = 48.0; pitchHi = 72.0; return }
        val lo = v[(v.size * 0.03).toInt()] - 4
        val hi = v[((v.size - 1) * 0.97).toInt()] + 4
        val mid = (lo + hi) / 2
        val span = maxOf(hi - lo, 14.0)
        pitchLo = (mid - span / 2).coerceIn(12.0, 100.0)
        pitchHi = (mid + span / 2).coerceIn(pitchLo + 6, 120.0)
    }

    /** Moves (Alt+wheel) or zooms (Ctrl+Alt+wheel) the pitch lane vertically. */
    fun scrollPitch(semitones: Double, zoom: Boolean) {
        if (zoom) {
            val mid = (pitchLo + pitchHi) / 2
            val span = ((pitchHi - pitchLo) * (1 + semitones * 0.1)).coerceIn(6.0, 96.0)
            pitchLo = mid - span / 2; pitchHi = mid + span / 2
        } else {
            pitchLo += semitones; pitchHi += semitones
        }
    }

    fun beginF0Stroke() {
        f0Undo.addLast(f0Edits?.copyOf())
        while (f0Undo.size > 50) f0Undo.removeFirst()
    }

    /** Sets the drawn f0 from [t0] to [t1] (s) going from MIDI [m0] to [m1]; null erases the drawing there. */
    fun drawF0(t0: Double, m0: Double?, t1: Double, m1: Double?) {
        val p = pitch ?: return
        val e = f0Edits?.copyOf() ?: FloatArray(p.values.size) { Float.NaN }
        val a = (minOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        val b = (maxOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        for (i in a..b) {
            if (m0 == null || m1 == null) { e[i] = Float.NaN; continue }
            val f = if (b == a) 0.0 else (i - a).toDouble() / (b - a)
            val m = if (t0 <= t1) m0 + (m1 - m0) * f else m1 + (m0 - m1) * f
            e[i] = (440.0 * kotlin.math.exp((m - 69) / 12.0 * kotlin.math.ln(2.0))).toFloat()
        }
        f0Edits = e
    }

    fun endF0Stroke() = saveF0Edits()

    fun undoF0() {
        if (f0Undo.isEmpty()) return
        f0Edits = f0Undo.removeLast()
        saveF0Edits()
    }

    val canUndoF0: Boolean get() = f0Undo.isNotEmpty()

    /** Forgets every drawn part of this file's f0 (can be undone). */
    fun resetF0() {
        if (f0Edits == null) return
        beginF0Stroke()
        f0Edits = null
        saveF0Edits()
    }

    /** Drags note [i] of [tier] up or down to MIDI [pitch]. */
    fun notePitchDragTo(tier: Int, i: Int, pitch: Double) {
        val base = noteDragBase ?: return
        val t = base.tiers[tier] as? mlabeler.core.model.NoteTier ?: return
        dragDoc = base.replace(tier, mlabeler.core.edit.NoteEdits.setPitch(t, i, pitch))
    }
    var power by mutableStateOf<mlabeler.core.dsp.Curve?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    private val histories = mutableMapOf<String, History<LabelDoc>>()
    private var history: History<LabelDoc>? = null
    /** Changes whenever the document or its saved state changes. */
    var docVersion by mutableIntStateOf(0)
        private set
    private var dragDoc by mutableStateOf<LabelDoc?>(null)
    private var committed by mutableStateOf<LabelDoc?>(null)

    val doc: LabelDoc? get() = dragDoc ?: committed
    var mode by mutableStateOf(Mode.Labels)
    val oto = OtoState(this, app)
    val cleanup = Cleanup(this, app)

    val labelsDirty: Boolean get() { docVersion; return history?.dirty == true }
    val dirty: Boolean get() = if (mode == Mode.Oto) oto.dirty else labelsDirty
    val canUndo: Boolean get() { docVersion; return if (mode == Mode.Oto) oto.canUndo else history?.canUndo == true }
    val canRedo: Boolean get() { docVersion; return if (mode == Mode.Oto) oto.canRedo else history?.canRedo == true }

    var selection by mutableStateOf<Selection>(Selection.None)
    var activeTier by mutableIntStateOf(0)
    /** Interval tier whose boundaries are drawn over the waveform and spectrogram. */
    val guideTier: Int get() = doc?.let { d -> if (d.tiers.getOrNull(activeTier) is IntervalTier) activeTier else d.phonemeTierIndex() } ?: 0

    // view
    var viewStart by mutableStateOf(0.0)
    var pixelsPerSecond by mutableStateOf(200.0)
    var viewWidthPx by mutableStateOf(1f)
        private set
    private var needsFit = false

    fun setViewWidth(px: Float) {
        if (px == viewWidthPx || px <= 1f) return
        viewWidthPx = px
        if (needsFit && audio != null) {
            needsFit = false
            fitAll()
        } else {
            clampView()
        }
    }
    /** Time under the mouse, or where the user tapped last. */
    var cursor by mutableStateOf<Double?>(null)
    var range by mutableStateOf<Pair<Double, Double>?>(null)
    var playhead by mutableStateOf<Double?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var editingText by mutableStateOf<IntervalRef?>(null)
    var problems by mutableStateOf<List<Problem>>(emptyList())
        private set
    var filter by mutableStateOf(FileFilter.All)
    var query by mutableStateOf("")
    var marksVersion by mutableIntStateOf(0)
        private set

    /** Gives keyboard focus back to the editor (set by the screen). */
    var requestFocus: () -> Unit = {}

    private val player = AudioOut()
    private val labelMtime = mutableMapOf<String, Long>()
    /** When each file's labels were last changed (ms), for the file list. */
    var labelTimes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    private fun readLabelTimes() {
        val list = items
        scope.launch {
            labelTimes = withContext(Dispatchers.Default) {
                list.mapNotNull { it.labelPath?.let { p -> runCatching { it.id to workspace.fs.lastModified(p) }.getOrNull() } }.toMap()
            }
        }
    }
    private var lastEdit = 0L
    private var warnedExternal = ""

    init {
        // autosave and reloading labels changed by other programs
        scope.launch {
            while (isActive) {
                delay(2000)
                runCatching { watch() }
            }
        }
    }

    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun watch() {
        val auto = settings.edit.autosaveSeconds
        if (auto > 0 && dirty && dragDoc == null && editingText == null && now() - lastEdit > auto * 1000L) save(quiet = true)
        val it = item ?: return
        // the recording itself changed (cleaned in another editor): show the new sound, labels stay
        val am = runCatching { workspace.fs.lastModified(it.audioPath) }.getOrDefault(0L)
        if (audioMtime > 0 && am > audioMtime && !loading && toolkitBusy == null) {
            audioMtime = am
            reloadAudio()
            app.message(S.audioReloaded())
            return
        }
        val path = it.labelPath ?: return
        val known = labelMtime[it.id] ?: return
        val m = workspace.fs.lastModified(path)
        if (m <= known) return
        if (!labelsDirty) {
            labelMtime[it.id] = m
            val d = runCatching { workspace.readLabels(it, duration) }.getOrNull() ?: return
            if (d != committed) {
                commit(d)
                history?.markSaved()
                docVersion++
                app.message(S.reloaded())
            }
        } else if (warnedExternal != "$path$m") {
            warnedExternal = "$path$m"
            app.message(S.changedOutside(), error = true)
        }
    }
    private var loadJob: Job? = null
    private var playJob: Job? = null

    private val settings get() = app.settings
    val duration: Double get() = audio?.duration ?: doc?.end ?: 0.0
    val visibleDuration: Double get() = viewWidthPx / pixelsPerSecond

    fun moveOptions(invertRipple: Boolean = false, invertLinked: Boolean = false) = MoveOptions(
        ripple = settings.edit.ripple != invertRipple,
        linked = settings.edit.linked != invertLinked,
        minGap = settings.edit.minIntervalMs / 1000.0,
    )

    // ---------- files ----------

    /** Drive letters: compare paths without case. */
    private val windowsPaths: Boolean get() = workspace.root.getOrNull(1) == ':'

    private fun samePath(a: String, b: String): Boolean {
        val x = a.replace('\\', '/').trimEnd('/')
        val y = b.replace('\\', '/').trimEnd('/')
        return if (windowsPaths) x.equals(y, ignoreCase = true) else x == y
    }

    fun contains(path: String): Boolean {
        val root = workspace.root.replace('\\', '/').trimEnd('/') + "/"
        val p = path.replace('\\', '/')
        return if (windowsPaths) p.lowercase().startsWith(root.lowercase()) else p.startsWith(root)
    }

    /** Opens the recording at [path] if it is in this folder's list. */
    fun openPath(path: String): Boolean {
        val i = items.indexOfFirst { samePath(it.audioPath, path) }
        if (i < 0) return false
        open(i)
        return true
    }

    /**
     * Copies recordings from elsewhere into this folder (next to the open one), with their labels of the same name;
     * a name that is taken gets " (2)". Recordings already in the folder are just opened. Returns how many were added.
     */
    fun addFiles(paths: List<String>): Int {
        val fs = workspace.fs
        val audio = paths.filter { Paths.ext(it).lowercase() in AUDIO_EXTENSIONS }
        val skipped = paths.filter { !fs.isDirectory(it) && Paths.ext(it).lowercase() !in AUDIO_EXTENSIONS }
        if (audio.isEmpty()) {
            app.message(if (skipped.any { Paths.ext(it).lowercase() in ALL_AUDIO_EXTENSIONS }) dropOtherAudio() else dropNoAudio(), error = true)
            return 0
        }
        val dir = item?.let { Paths.parent(it.audioPath) } ?: workspace.root
        var added = 0
        var first: String? = null
        for (src in audio) {
            if (contains(src)) { if (first == null) first = src; continue }
            val stem = Paths.stem(src)
            val ext = Paths.ext(src)
            var name = stem
            var n = 2
            while (fs.exists(Paths.join(dir, "$name.$ext"))) name = "$stem ($n)".also { n++ }
            val target = Paths.join(dir, "$name.$ext")
            try {
                fs.copy(src, target)
                for (le in listOf("lab", "TextGrid", "ds", "txt")) {
                    val l = Paths.join(Paths.parent(src), "$stem.$le")
                    if (fs.exists(l)) fs.copy(l, Paths.join(dir, "$name.$le"))
                }
                added++
                if (first == null) first = target
            } catch (e: Exception) {
                app.message(S.cannotSave.format(e.message ?: e.toString()), error = true)
            }
        }
        if (added > 0) {
            rescan()
            app.message(dropAdded.format(added, Paths.name(dir)))
        }
        first?.let { openPath(it) }
        return added
    }

    fun scan() {
        items = workspace.scan()
        readLabelTimes()
        mode = when (workspace.state.kind) {
            "oto" -> Mode.Oto
            "labels" -> Mode.Labels
            // folders of UTAU voicebanks are oto work
            else -> if (items.any { oto.hasOto(it) } && items.none { it.labelPath != null }) Mode.Oto else Mode.Labels
        }
        if (index !in items.indices) {
            val last = workspace.state.lastItem
            open(items.indexOfFirst { it.id == last }.takeIf { it >= 0 } ?: if (items.isNotEmpty()) 0 else -1)
        }
    }

    /** A labelling shown next to the edited one for comparison (read-only). */
    data class Reference(val name: String, val folder: String, val doc: LabelDoc, val range: Pair<Double, Double>? = null)

    var references by mutableStateOf<List<Reference>>(emptyList())
        private set

    /** References hidden from the picture (still listed in the side panel): their keys, see [refKey]. */
    var hiddenRefs by mutableStateOf<Set<String>>(emptySet())
        private set

    fun refKey(r: Reference) = r.folder.ifEmpty { "model:" + r.name + ":" + r.range }

    fun isHidden(r: Reference) = refKey(r) in hiddenRefs

    fun setHidden(r: Reference, hidden: Boolean) {
        hiddenRefs = if (hidden) hiddenRefs + refKey(r) else hiddenRefs - refKey(r)
    }

    /** Interval tiers drawn below the edited ones: (reference index, tier). */
    val referenceTiers: List<Pair<Int, IntervalTier>> get() =
        references.withIndex().filter { (_, r) -> !isHidden(r) }.flatMap { (k, r) -> r.doc.tiers.filterIsInstance<IntervalTier>().map { k to it } }

    /** Results of autolabelled parts kept for comparison (per file, not saved). */
    private val modelResults = mutableMapOf<String, List<Reference>>()

    fun loadReferences() {
        val it = item
        val dur = duration
        references = if (it == null) emptyList() else workspace.state.compareFolders.mapNotNull { dir ->
            workspace.readLabelsIn(dir, it.name, dur)?.let { d -> Reference(Paths.name(dir), dir, d) }
        } + modelResults[it.id].orEmpty()
    }

    fun dropModelResult(r: Reference) {
        val id = item?.id ?: return
        modelResults[id] = modelResults[id].orEmpty() - r
        loadReferences()
    }

    // ---------- autolabel through the toolkit ----------

    var toolkitBusy by mutableStateOf<String?>(null)
    /** When the current toolkit work began (ms) and its earlier steps, for the busy panel. */
    var toolkitBusySince = 0L
        private set

    /** Marks the start of toolkit work shown in the busy panel (its time counts from here). */
    fun beginToolkitWork(what: String) {
        toolkitBusySince = now()
        toolkitSteps.clear()
        toolkitProgress = null
        toolkitBusy = what
    }
    val toolkitSteps = androidx.compose.runtime.mutableStateListOf<String>()
    /** 0..1 while the toolkit works on a job, null when unknown. */
    var toolkitProgress by mutableStateOf<Double?>(null)
    /** Model results (comparison tiers) of the open file. */
    val modelReferences: List<Reference> get() = references.filter { it.folder.isEmpty() }

    /** Puts a model's result into the labels and removes it from the comparison. */
    fun acceptModelResult(r: Reference) {
        takeReference(r)
        dropModelResult(r)
    }
    var toolkitJob: Job? = null
    /** Coroutine scope of this folder (background work that stops when the folder closes). */
    val workScope: CoroutineScope get() = scope

    /**
     * Aligns [from]..[to] with [model]; the result replaces that part of the tiers ([replace]) or is shown
     * as a comparison tier named after the model.
     */
    fun autolabel(from: Double, to: Double, model: String, language: String?, text: String, phonemes: Boolean, replace: Boolean, recognize: Boolean = false, whisper: Boolean = false) {
        val a = audio ?: return
        val it = item ?: return
        toolkitJob?.cancel()
        toolkitJob = scope.launch {
            val client = app.toolkit.client()
            var serverJob: String? = null
            try {
                toolkitBusySince = now()
                toolkitSteps.clear()
                toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
                if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
                toolkitBusy = S.uploading()
                val s0 = (from * a.sampleRate).toInt().coerceIn(0, a.samples.size)
                val s1 = (to * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
                val wav = withContext(Dispatchers.Default) { Wav.encode16(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))) }
                val fileId = client.upload(it.name + "_part.wav", wav)
                val job = if (recognize) {
                    client.segment(fileId, model, language, text.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }, settings.toolkit.wfl)
                } else client.align(fileId, model, language, text, phonemes, whisper)
                serverJob = job
                val result = client.await(job) { p, stage ->
                    toolkitProgress = p
                    val st = stage.ifEmpty { S.toolkit() }
                    // a new step (not the same one with new numbers) goes to the list of steps
                    val key = st.substringBefore(':').substringBefore('(').trim()
                    val prev = toolkitBusy
                    if (prev != null && prev.substringBefore(':').substringBefore('(').trim() != key) { toolkitSteps.add(prev); while (toolkitSteps.size > 6) toolkitSteps.removeAt(0) }
                    toolkitBusy = st
                }
                val part = mlabeler.app.toolkit.ToolkitClient.labelOf(result, s0.toDouble() / a.sampleRate, (s1 - s0).toDouble() / a.sampleRate)
                if (replace) {
                    updateDoc { d ->
                        var out = d
                        for (pt in part.tiers.filterIsInstance<IntervalTier>()) {
                            val k = out.tierIndex(pt.name).takeIf { k -> k >= 0 } ?: if (pt.name == "phones") out.phonemeTierIndex() else -1
                            if (k >= 0) out = out.replace(k, mlabeler.core.edit.RangeEdits.replace(out.tiers[k] as IntervalTier, from, to, pt))
                            else out = out.copy(tiers = listOf(mlabeler.core.edit.RangeEdits.replace(IntervalTier.empty(pt.name, duration), from, to, pt)) + out.tiers)
                        }
                        out
                    }
                } else {
                    modelResults[it.id] = modelResults[it.id].orEmpty() + Reference(model, "", part, from to to)
                    loadReferences()
                }
                app.message(if (replace) S.autolabelDone() else S.autolabelCompareDone())
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stop the job on the toolkit side too
                serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { client.cancel(id) } }
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                toolkitBusy = null
                toolkitProgress = null
            }
        }
    }

    /** Where each file's text comes from when many files are aligned at once. */
    enum class BatchText { TxtNextToIt, Labels, None }

    /** Files for [autolabelFiles]: those without labels, those not marked done, or all. */
    fun batchFiles(which: FileFilter): List<Item> = items.filter {
        when (which) {
            FileFilter.NoLabels -> it.labelPath == null
            FileFilter.NotDone -> !marks(it).done
            else -> true
        }
    }

    /**
     * Labels many files one after another and saves each (the open one through its history, so it can be undone).
     * Stopping keeps what is done; running again on files without labels goes on from there.
     */
    fun autolabelFiles(
        files: List<Item>, model: String, language: String?, recognize: Boolean, source: BatchText,
        phonemes: Boolean, whisper: Boolean,
    ) {
        if (files.isEmpty()) return
        toolkitJob?.cancel()
        toolkitJob = scope.launch {
            val client = app.toolkit.client()
            var serverJob: String? = null
            val failed = mutableListOf<String>()
            var done = 0
            try {
                toolkitBusySince = now()
                toolkitSteps.clear()
                toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
                if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
                for ((n, f) in files.withIndex()) {
                    val head = batchFile.format(n + 1, files.size, f.name)
                    toolkitBusy = head
                    toolkitProgress = n.toDouble() / files.size
                    try {
                        val bytes = withContext(Dispatchers.Default) { workspace.fs.read(f.audioPath) }
                        val a = withContext(Dispatchers.Default) {
                            if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
                        }
                        val current = if (f.id == item?.id) committed else runCatching { workspace.readLabels(f, a.duration) }.getOrNull()
                        val known: List<String> = when (source) {
                            BatchText.Labels -> current?.let { d -> (d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier)?.texts?.filter { it.isNotEmpty() } }.orEmpty()
                            else -> emptyList()
                        }
                        val txt: String? = if (source == BatchText.TxtNextToIt) {
                            val p = Paths.join(Paths.parent(f.audioPath), Paths.stem(f.audioPath) + ".txt")
                            runCatching { workspace.fs.read(p).decodeToString() }.getOrNull()
                        } else null
                        val upload = if (Wav.isWav(bytes)) bytes else withContext(Dispatchers.Default) { Wav.encode16(a) }
                        val fileId = client.upload(Paths.stem(f.audioPath) + ".wav", upload)
                        val job = if (recognize) client.segment(fileId, model, language, known, settings.toolkit.wfl)
                        else {
                            val asPhonemes = source == BatchText.Labels || (txt != null && (phonemes || mlabeler.core.format.TextImport.looksLikeLab(txt)))
                            val text = when {
                                source == BatchText.Labels -> known.joinToString(" ")
                                txt != null -> mlabeler.core.format.TextImport.clean(txt, asPhonemes)
                                else -> ""
                            }
                            if (text.isBlank() && !whisper) error(noText())
                            client.align(fileId, model, language, text, asPhonemes && text.isNotBlank(), whisper)
                        }
                        serverJob = job
                        val result = client.await(job) { p, stage ->
                            toolkitProgress = (n + p) / files.size
                            toolkitBusy = head + " · " + stage.ifEmpty { S.toolkit() }
                        }
                        serverJob = null
                        val part = mlabeler.app.toolkit.ToolkitClient.labelOf(result, 0.0, a.duration)
                        fun merged(d: LabelDoc): LabelDoc {
                            var out = d
                            for (pt in part.tiers.filterIsInstance<IntervalTier>()) {
                                val k = out.tierIndex(pt.name).takeIf { k -> k >= 0 } ?: if (pt.name == "phones") out.phonemeTierIndex() else -1
                                out = if (k >= 0 && out.tiers[k] is IntervalTier) out.replace(k, pt.copy(name = (out.tiers[k] as IntervalTier).name))
                                else out.copy(tiers = listOf(pt) + out.tiers)
                            }
                            return out
                        }
                        if (f.id == item?.id) {
                            updateDoc { d -> merged(d) }
                            saveLabels(quiet = true)
                        } else {
                            val updated = workspace.writeLabels(f, merged(current ?: LabelDoc.empty(a.duration)), a.duration,
                                if (f.labelFormat == null) workspace.state.defaultFormat else null)
                            histories.remove(f.id)
                            items = items.map { x -> if (x.id == f.id) updated else x }
                            updated.labelPath?.let { p -> labelTimes = labelTimes + (updated.id to (runCatching { workspace.fs.lastModified(p) }.getOrNull() ?: 0L)) }
                            labelMtime[updated.id] = updated.labelPath?.let { p -> workspace.fs.lastModified(p) } ?: 0L
                        }
                        done++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += f.name + ": " + (e.message ?: e.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                        toolkitSteps.add(failed.last()); while (toolkitSteps.size > 6) toolkitSteps.removeAt(0)
                    }
                }
                labelIndex = null
                docVersion++
                if (failed.isEmpty()) app.message(batchDone.format(done))
                else app.message(batchDoneWithErrors.format(done, failed.size) + "\n" + failed.joinToString("\n"), error = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { client.cancel(id) } }
                withContext(kotlinx.coroutines.NonCancellable) { if (done > 0) app.message(batchStopped.format(done, files.size)) }
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                toolkitBusy = null
                toolkitProgress = null
            }
        }
    }

    fun cancelToolkit() {
        toolkitJob?.cancel()
        toolkitBusy = null
        toolkitProgress = null
    }

    fun addCompareFolder(dir: String) {
        workspace.updateState { s -> s.copy(compareFolders = (s.compareFolders - dir) + dir) }
        loadReferences()
    }

    fun removeCompareFolder(dir: String) {
        workspace.updateState { s -> s.copy(compareFolders = s.compareFolders - dir) }
        loadReferences()
    }

    /** Replaces the matching tier with the reference's (undoable). */
    fun takeReference(r: Reference) {
        updateDoc { d ->
            var out = d
            for (t in r.doc.tiers.filterIsInstance<IntervalTier>()) {
                val k = out.tierIndex(t.name).takeIf { it >= 0 } ?: out.phonemeTierIndex()
                val cur = out.tiers[k] as? IntervalTier ?: continue
                val rg = r.range
                out = out.replace(k, if (rg != null) mlabeler.core.edit.RangeEdits.replace(cur, rg.first, rg.second, t) else t.copy(name = cur.name))
            }
            out
        }
    }

    private var specJob: Job? = null

    private var specKey: List<Any>? = null
    private fun currentSpecKey(): List<Any> = settings.view.let { listOf(it.windowMs, it.hopMs, it.bands, it.minDb, it.maxDb) }
    fun specNeedsUpdate() = specKey != null && specKey != currentSpecKey()

    private suspend fun computeSpectrogram(a: Audio) {
        val v = settings.view
        specKey = currentSpecKey()
        val hop = if (v.hopMs > 0) v.hopMs / 1000.0 else if (a.duration <= 120) 0.0025 else 0.005
        val builder = Spectrogram.Builder(
            a.samples, a.sampleRate, hopSeconds = hop, windowSeconds = v.windowMs / 1000.0, bands = v.bands,
            maxFreq = 16000.0, minDb = v.minDb, maxDb = v.maxDb,
        )
        val spec = builder.result
        spectrogram = spec
        // every core works on its own piece of each batch; the picture is refreshed a few times a second at most
        val workers = mlabeler.app.Platform.cores.coerceIn(1, 16)
        val chunk = 2048
        var lastShown = kotlin.time.TimeSource.Monotonic.markNow()
        var f = 0
        withContext(Dispatchers.Default) {
            while (f < builder.frames) {
                val batchEnd = minOf(builder.frames, f + workers * chunk)
                (f until batchEnd step chunk).map { from ->
                    async { builder.fill(from, minOf(from + chunk, batchEnd)) }
                }.awaitAll()
                f = batchEnd
                ensureActive()
                builder.setReady(f)
                if (lastShown.elapsedNow().inWholeMilliseconds > 400 || f >= builder.frames) {
                    lastShown = kotlin.time.TimeSource.Monotonic.markNow()
                    spectrogramProgress = f
                }
            }
        }
        spectrogram = spec
        spectrogramProgress = spec.ready
    }

    /** Builds the spectrogram again after its settings changed. */
    fun recomputeSpectrogram() {
        val a = audio ?: return
        specJob?.cancel()
        specJob = scope.launch { computeSpectrogram(a) }
    }

    private var pendingInterval: Pair<String, Int>? = null

    /** Opens [itemIndex] and selects interval [i] of the tier called [tierName]. */
    fun openInterval(itemIndex: Int, tierName: String, i: Int) {
        if (itemIndex == index && audio != null) {
            val k = doc?.tierIndex(tierName) ?: -1
            if (k >= 0) selectInterval(IntervalRef(k, i))
            return
        }
        pendingInterval = tierName to i
        open(itemIndex)
    }

    private var pendingRange: Pair<Double, Double>? = null

    /** Opens [itemIndex] with [from]..[to] selected and in view. */
    fun openRange(itemIndex: Int, from: Double, to: Double) {
        if (itemIndex == index && audio != null) {
            range = from to to
            reveal(from, to)
            return
        }
        pendingRange = from to to
        open(itemIndex)
    }

    /**
     * Places worth a look in the open file, worst first: errors, then warnings, then the phonemes the aligner was
     * least sure of. Nothing is shown or changed until someone steps through them.
     */
    fun reviewQueue(): List<IntervalRef> {
        val d = doc ?: return emptyList()
        val seen = mutableSetOf<IntervalRef>()
        val out = mutableListOf<IntervalRef>()
        fun conf(r: IntervalRef) = (d.tiers.getOrNull(r.tier) as? IntervalTier)?.takeIf { r.index < it.size }?.confidenceOf(r.index)?.toDouble() ?: 1.0
        for (p in problems.sortedWith(compareBy<Problem>({ if (it.severity == mlabeler.core.check.Severity.Error) 0 else 1 }, { conf(it.ref) }, { it.ref.tier }, { it.ref.index }))) {
            if (seen.add(p.ref)) out += p.ref
        }
        val unsure = mutableListOf<Pair<IntervalRef, Double>>()
        for ((k, t) in d.tiers.withIndex()) if (t is IntervalTier) for (i in 0 until t.size) {
            val c = t.confidenceOf(i) ?: continue
            if (c < 0.8) unsure += IntervalRef(k, i) to c.toDouble()
        }
        for ((r, _) in unsure.sortedBy { it.second }) if (seen.add(r)) out += r
        return out
    }

    /** Selects the next ([step] 1) or previous (-1) place of [reviewQueue] after the selected one. */
    fun reviewStep(step: Int) {
        val q = reviewQueue()
        if (q.isEmpty()) { app.message(reviewNothing()); return }
        val cur = (selection as? Selection.Interval)?.ref
        val at = q.indexOf(cur)
        val next = when {
            at < 0 -> if (step > 0) 0 else q.size - 1
            else -> at + step
        }
        if (next !in q.indices) { app.message(reviewEnd.format(q.size)); return }
        selectInterval(q[next])
        app.message(reviewPos.format(next + 1, q.size))
    }

    /** Scans the folder again, keeping the open file. */
    fun rescan() {
        val id = item?.id
        items = workspace.scan()
        readLabelTimes()
        index = items.indexOfFirst { it.id == id }
        if (index < 0 && items.isNotEmpty()) open(0)
    }

    fun marks(item: Item): ItemMarks {
        marksVersion
        return workspace.itemState(item.id).marks
    }

    fun bumpMarks() { marksVersion++ }

    /** Changes what this folder is labelled with; remembered in the folder. */
    fun setKind(m: Mode) {
        workspace.updateState { it.copy(kind = if (m == Mode.Oto) "oto" else "labels") }
        if (mode == m) return
        mode = m
        query = ""
        if (m == Mode.Oto) oto.onItemOpened()
    }

    fun setMarks(item: Item, transform: (ItemMarks) -> ItemMarks) {
        workspace.updateItem(item.id) { it.copy(marks = transform(it.marks)) }
        marksVersion++
    }

    /** Words of every label file, for searching files by phoneme. Built in the background on the first search. */
    private var labelIndex by mutableStateOf<Map<String, Set<String>>?>(null)
    private var indexJob: Job? = null

    private fun ensureLabelIndex() {
        if (labelIndex != null || indexJob?.isActive == true) return
        val list = items
        indexJob = scope.launch {
            labelIndex = withContext(Dispatchers.Default) {
                list.associate { item ->
                    val words = item.labelPath?.let { p ->
                        runCatching { decodeGuess(workspace.fs.read(p), "UTF-8").first }.getOrNull()
                            ?.split(Regex("[\\s,\"]+"))?.filter { it.isNotEmpty() && it.toDoubleOrNull() == null }?.toSet()
                    } ?: emptySet()
                    item.id to words
                }
            }
        }
    }

    fun filtered(): List<Pair<Int, Item>> {
        marksVersion
        val q = query.trim().lowercase()
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (q.isNotEmpty()) ensureLabelIndex()
        val index = labelIndex
        return items.withIndex().filter { (_, it) ->
            (q.isEmpty() || it.id.lowercase().contains(q) || (index?.get(it.id)?.let { w -> tokens.all { t -> t in w } } == true)) && when (filter) {
                FileFilter.All -> true
                FileFilter.NotDone -> !marks(it).done
                FileFilter.Starred -> marks(it).star
                FileFilter.NoLabels -> it.labelPath == null
            }
        }.map { it.index to it.value }
    }

    fun open(i: Int) {
        finishEditing()
        if (i == index && audio != null) return
        if (i !in items.indices) {
            index = -1
            return
        }
        if (settings.edit.saveOnSwitch && labelsDirty) saveLabels(quiet = true)
        rememberView()
        stop()
        index = i
        val it = items[i]
        workspace.updateState { s -> s.copy(lastItem = it.id) }
        load(it)
        // keys (Delete, Space…) go to the editor again after a click in the file list
        runCatching { requestFocus() }
    }

    fun openRelative(delta: Int) {
        finishEditing()
        val list = filtered()
        if (list.isEmpty()) return
        val pos = list.indexOfFirst { it.first == index }
        val next = if (pos < 0) 0 else (pos + delta).coerceIn(0, list.size - 1)
        open(list[next].first)
    }

    private fun rememberView() {
        val it = item ?: return
        workspace.updateItem(it.id) { s -> s.copy(viewStart = viewStart, pixelsPerSecond = pixelsPerSecond) }
    }

    /** Modification time of the open recording when it was read. */
    private var audioMtime = 0L

    /** Plays a prepared piece of sound (e.g. a cleaning preview). */
    fun playBuffer(a: Audio) {
        stop()
        runCatching { player.play(a, 0, a.samples.size, false) }.onFailure { app.message(S.cannotPlay.format(it.message ?: it.toString()), error = true); return }
        playing = true
        playJob = scope.launch {
            delay(30)
            while (isActive && player.isPlaying) delay(30)
            playing = false
        }
    }

    /** Reads the open recording again (after it was changed on disk); labels, undo history and view stay. */
    fun reloadAudio() {
        val it = item ?: return
        finishEditing()
        rememberView()
        stop()
        val sel = selection
        load(it)
        pendingSelection = sel
    }

    private var pendingSelection: Selection? = null
    private var lastLoadedPath = ""

    private fun load(item: Item) {
        loadJob?.cancel()
        audioMtime = runCatching { workspace.fs.lastModified(item.audioPath) }.getOrDefault(0L)
        if (item.audioPath != lastLoadedPath) cleanup.clearFound()
        lastLoadedPath = item.audioPath
        audio = null
        peaks = null
        pitch = null
        f0Edits = null
        f0Pencil = false
        power = null
        references = emptyList()
        spectrogram = null
        loadError = null
        selection = Selection.None
        range = null
        editingText = null
        loading = true
        val cached = histories[item.id]
        history = cached
        committed = cached?.current
        docVersion++
        loadJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = workspace.fs.read(item.audioPath)
                    if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(item.audioPath) ?: error(S.unsupportedAudio())
                }
            }
            ensureActive()
            val a = result.getOrElse {
                loading = false
                loadError = S.cannotOpen.format(item.name, it.message ?: it.toString())
                return@launch
            }
            if (cached == null) {
                val d = withContext(Dispatchers.Default) {
                    runCatching { labelMtime[item.id] = item.labelPath?.let { p -> workspace.fs.lastModified(p) } ?: 0L; workspace.readLabels(item, a.duration) }
                }
                val docValue = d.getOrElse {
                    app.message(S.labelsUnreadable.format(it.message ?: ""), error = true)
                    LabelDoc.empty(a.duration)
                }
                val h = History(docValue)
                histories[item.id] = h
                history = h
                committed = docValue
            }
            audio = a
            val st = workspace.itemState(item.id)
            if (st.pixelsPerSecond > 0) {
                pixelsPerSecond = st.pixelsPerSecond
                viewStart = st.viewStart
            } else {
                fitAll()
            }
            activeTier = doc?.phonemeTierIndex() ?: 0
            pendingRange?.let { (a0, b0) -> pendingRange = null; range = a0 to b0; reveal(a0, b0) }
            pendingInterval?.let { (tierName, i) ->
                pendingInterval = null
                val k = doc?.tierIndex(tierName) ?: -1
                if (k >= 0) selectInterval(IntervalRef(k, i))
            }
            pendingSelection?.let { selection = it; pendingSelection = null }
            loading = false
            docChanged()
            oto.onItemOpened()
            loadReferences()
            peaks = withContext(Dispatchers.Default) { Peaks.build(a.samples, a.sampleRate) }
            power = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.power(a.samples, a.sampleRate) }
            if (settings.layout.showPitch) pitch = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate) }
            computeSpectrogram(a)
            if (pitch == null) pitch = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate) }
            loadF0Edits()
            fitPitchRange()
        }
    }

    // ---------- document ----------

    private fun docChanged() {
        docVersion++
        val d = committed ?: return
        val builtIn = Checks.run(d, settings.checks)
        problems = builtIn + scriptProblems.takeIf { scriptDoc === d }.orEmpty()
        runScripts(d, builtIn)
    }

    private var scriptProblems: List<mlabeler.core.check.Problem> = emptyList()
    private var scriptDoc: LabelDoc? = null
    private var scriptJob: Job? = null
    /** Own check scripts, read again when the folder opens or the settings page asks. */
    var checkScripts: List<mlabeler.app.plugins.CheckScripts.Script> = runCatching { mlabeler.app.plugins.CheckScripts.load(workspace.root) }.getOrDefault(emptyList())

    fun reloadCheckScripts() {
        checkScripts = runCatching { mlabeler.app.plugins.CheckScripts.load(workspace.root) }.getOrDefault(emptyList())
        docChanged()
    }

    private fun runScripts(d: LabelDoc, builtIn: List<mlabeler.core.check.Problem>) {
        scriptJob?.cancel()
        val scripts = checkScripts
        if (!settings.checks.scripts || scripts.isEmpty()) { scriptProblems = emptyList(); return }
        val name = item?.name ?: ""
        val dur = duration
        scriptJob = scope.launch {
            delay(250)
            val found = runCatching { mlabeler.app.plugins.CheckScripts.run(scripts, d, name, dur) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
            scriptProblems = found
            scriptDoc = d
            if (committed === d) problems = builtIn + found
        }
    }

    fun commit(newDoc: LabelDoc) {
        lastEdit = now()
        val h = history ?: return
        h.push(newDoc)
        committed = h.current
        dragDoc = null
        docChanged()
    }

    fun undo() {
        if (mode == Mode.Oto) return oto.undo()
        val h = history ?: return
        if (h.undo()) {
            committed = h.current
            fixSelection()
            docChanged()
        }
    }

    fun redo() {
        if (mode == Mode.Oto) return oto.redo()
        val h = history ?: return
        if (h.redo()) {
            committed = h.current
            fixSelection()
            docChanged()
        }
    }

    private fun fixSelection() {
        val d = doc ?: return
        val sel = selection
        val ok = when (sel) {
            is Selection.Interval -> (d.tiers.getOrNull(sel.ref.tier) as? IntervalTier)?.let { sel.ref.index < it.size } == true
            is Selection.Bound -> (d.tiers.getOrNull(sel.ref.tier) as? IntervalTier)?.let { sel.ref.bound < it.bounds.size } == true
            Selection.None -> true
            is Selection.Note -> (d.tiers.getOrNull(sel.tier) as? mlabeler.core.model.NoteTier)?.let { sel.index < it.notes.size } == true
        }
        if (!ok) selection = Selection.None
        if (activeTier !in d.tiers.indices) activeTier = 0
    }

    fun save(quiet: Boolean = false) {
        if (mode == Mode.Oto) return oto.save(quiet)
        saveLabels(quiet)
    }

    private fun saveLabels(quiet: Boolean) {
        val it = item ?: return
        val d = committed ?: return
        val h = history ?: return
        try {
            val updated = workspace.writeLabels(it, d, duration, if (it.labelFormat == null) (workspace.state.defaultFormat) else null)
            items = items.map { x -> if (x.id == it.id) updated else x }
            h.markSaved()
            labelIndex = null
            updated.labelPath?.let { p -> labelTimes = labelTimes + (updated.id to (runCatching { workspace.fs.lastModified(p) }.getOrNull() ?: 0L)) }
            labelMtime[updated.id] = updated.labelPath?.let { p -> workspace.fs.lastModified(p) } ?: 0L
            docVersion++
            if (!quiet) app.message(S.saved.format(Paths.name(updated.labelPath ?: "")))
        } catch (e: Exception) {
            app.message(S.cannotSave.format(e.message ?: e.toString()), error = true)
        }
    }

    fun saveAllOnClose() {
        if (settings.edit.saveOnSwitch && labelsDirty) saveLabels(quiet = true)
        if (settings.edit.saveOnSwitch && oto.dirty) oto.save(quiet = true)
        rememberView()
        player.release()
        scope.cancel()
    }

    // ---------- selection ----------

    private fun tier(i: Int) = doc?.tiers?.getOrNull(i) as? IntervalTier

    fun selectInterval(ref: IntervalRef, reveal: Boolean = true) {
        selection = Selection.Interval(ref)
        activeTier = ref.tier
        if (reveal) tier(ref.tier)?.let { reveal(it.startOf(ref.index), it.endOf(ref.index)) }
    }

    fun selectBound(ref: BoundRef, reveal: Boolean = true) {
        selection = Selection.Bound(ref)
        activeTier = ref.tier
        if (reveal) tier(ref.tier)?.let { reveal(it.bounds[ref.bound], it.bounds[ref.bound]) }
    }

    /** Next/previous interval in the active tier. */
    fun stepInterval(delta: Int) {
        val t = tier(activeTier) ?: return
        val cur = when (val s = selection) {
            is Selection.Interval -> if (s.ref.tier == activeTier) s.ref.index else null
            is Selection.Bound -> if (s.ref.tier == activeTier) (if (delta > 0) s.ref.bound - 1 else s.ref.bound) else null
            else -> null
        }
        val next = when {
            cur == null -> t.indexAt(cursor ?: (viewStart + visibleDuration / 2)).coerceAtLeast(0)
            else -> (cur + delta).coerceIn(0, t.size - 1)
        }
        selectInterval(IntervalRef(activeTier, next))
    }

    /** Left/right: from an interval to its boundary, from a boundary to the next boundary. */
    fun stepBound(delta: Int) {
        val t = tier(activeTier) ?: return
        val next = when (val s = selection) {
            is Selection.Interval -> if (delta < 0) s.ref.index else s.ref.index + 1
            is Selection.Bound -> (s.ref.bound + delta).coerceIn(0, t.bounds.size - 1)
            else -> t.nearestBound(cursor ?: (viewStart + visibleDuration / 2))
        }
        selectBound(BoundRef(activeTier, next))
    }

    fun stepTier(delta: Int) {
        val d = doc ?: return
        val time = selectionTime() ?: cursor ?: (viewStart + visibleDuration / 2)
        var k = activeTier
        do {
            k += delta
        } while (k in d.tiers.indices && d.tiers[k] !is IntervalTier)
        if (k !in d.tiers.indices) return
        val t = d.tiers[k] as IntervalTier
        if (selection is Selection.Bound) selectBound(BoundRef(k, t.nearestBound(time)), reveal = false)
        else selectInterval(IntervalRef(k, t.indexAt(time).coerceAtLeast(0)), reveal = false)
    }

    private fun selectionTime(): Double? = when (val s = selection) {
        is Selection.Interval -> tier(s.ref.tier)?.let { (it.startOf(s.ref.index) + it.endOf(s.ref.index)) / 2 }
        is Selection.Bound -> tier(s.ref.tier)?.bounds?.get(s.ref.bound)
        else -> null
    }

    fun selectedInterval(): IntervalRef? = (selection as? Selection.Interval)?.ref

    /** What is typed in the label field right now, and for which interval. */
    var editingDraft: Pair<IntervalRef, String>? = null

    /** Commits a label being typed, if any (before playing, switching files, running a command): no Enter needed. */
    fun finishEditing() {
        val e = editingText ?: return
        editingText = null
        val d = editingDraft
        if (d != null && d.first == e) setText(e, d.second.trim())
    }

    // ---------- edits ----------

    private var dragBase: LabelDoc? = null

    fun beginDrag(ref: BoundRef) {
        dragBase = committed
        selectBound(ref, reveal = false)
    }

    fun dragTo(ref: BoundRef, time: Double, invertRipple: Boolean, invertLinked: Boolean) {
        val base = dragBase ?: return
        dragDoc = Edits.moveBound(base, ref, time.coerceIn(0.0, duration), duration, moveOptions(invertRipple, invertLinked))
    }

    fun endDrag(bound: BoundRef? = null) {
        val d = dragDoc
        dragBase = null
        if (d != null) commit(d) else dragDoc = null
        // the phoneme the boundary belongs to becomes selected, so Space plays it right away
        if (bound != null && settings.edit.selectAfterDrag) boundOwnerInterval(bound)?.let { selectInterval(it, reveal = false) }
    }

    /** A boundary was pressed but not moved. */
    fun cancelDrag(bound: BoundRef? = null) {
        dragBase = null
        dragDoc = null
        if (bound != null && settings.edit.selectAfterDrag) boundOwnerInterval(bound)?.let { selectInterval(it, reveal = false) }
    }

    fun moveSelectedBound(time: Double) {
        val ref = (selection as? Selection.Bound)?.ref ?: return
        val d = committed ?: return
        commit(Edits.moveBound(d, ref, time, duration, moveOptions()))
    }

    /** Tier the selected part works on, and the inner boundaries inside the part. */
    private fun rangeBounds(): Pair<Int, List<Int>>? {
        val (a, b) = range ?: return null
        val k = if (tier(activeTier) != null) activeTier else committed?.phonemeTierIndex() ?: return null
        val t = tier(k) ?: return null
        val inside = (1 until t.bounds.size - 1).filter { t.bounds[it] > a + 1e-6 && t.bounds[it] < b - 1e-6 }
        return if (inside.isEmpty()) null else k to inside
    }

    /** Removes every boundary inside the selected part: its phonemes become one (the first name stays). */
    fun deleteInRange(): Boolean {
        val d = committed ?: return false
        val (k, inside) = rangeBounds() ?: return false
        var doc = d
        for (b in inside.reversed()) doc = Edits.removeBound(doc, BoundRef(k, b))
        commit(doc)
        app.message(removedInRange.format(inside.size))
        fixSelection()
        return true
    }

    /** Moves every boundary inside the selected part together (and the part with them). */
    fun nudgeRange(seconds: Double): Boolean {
        val d = committed ?: return false
        val (k, inside) = rangeBounds() ?: return false
        val t = tier(k) ?: return false
        val lo = t.bounds[inside.first() - 1] + settings.edit.minIntervalMs / 1000.0
        val hi = t.bounds[inside.last() + 1] - settings.edit.minIntervalMs / 1000.0
        val dt = seconds.coerceIn(lo - t.bounds[inside.first()], hi - t.bounds[inside.last()])
        if (kotlin.math.abs(dt) < 1e-9) return true
        val nb = t.bounds.toMutableList()
        for (b in inside) nb[b] = nb[b] + dt
        commit(d.replace(k, t.copy(bounds = nb)))
        range = range?.let { (a, b) -> a + dt to b + dt }
        return true
    }

    fun nudge(steps: Int) {
        if (selection == Selection.None && range != null && nudgeRange(steps * settings.edit.nudgeMs / 1000.0)) return
        val ref = (selection as? Selection.Bound)?.ref ?: return
        val t = tier(ref.tier) ?: return
        moveSelectedBound(t.bounds[ref.bound] + steps * settings.edit.nudgeMs / 1000.0)
    }

    /** Where edits at "the cursor" happen: the mouse position, else the playhead, else the middle of the view. */
    fun editTime(): Double = cursor ?: playhead ?: (viewStart + visibleDuration / 2)

    private fun noteTier(i: Int) = doc?.tiers?.getOrNull(i) as? mlabeler.core.model.NoteTier

    fun selectNote(tier: Int, index: Int) {
        selection = Selection.Note(tier, index)
        activeTier = tier
    }

    private var noteDragBase: LabelDoc? = null

    fun beginNoteDrag() { noteDragBase = committed }

    /** Drags the start or end border of note [i]. */
    fun noteDragTo(tier: Int, i: Int, start: Boolean, time: Double) {
        val base = noteDragBase ?: return
        val t = base.tiers[tier] as? mlabeler.core.model.NoteTier ?: return
        val nt = if (start) mlabeler.core.edit.NoteEdits.moveStart(t, i, time) else mlabeler.core.edit.NoteEdits.moveEnd(t, i, time)
        dragDoc = base.replace(tier, nt)
    }

    fun endNoteDrag() {
        val d = dragDoc
        noteDragBase = null
        if (d != null) commit(d) else dragDoc = null
    }

    fun changeNote(transform: (mlabeler.core.model.NoteTier, Int) -> mlabeler.core.model.NoteTier) {
        val s = selection as? Selection.Note ?: return
        val t = noteTier(s.tier) ?: return
        if (s.index >= t.notes.size) return
        updateDoc { it.replace(s.tier, transform(t, s.index)) }
    }

    fun nudgePitch(semitones: Double) = changeNote { t, i ->
        mlabeler.core.edit.NoteEdits.setPitch(t, i, t.notes[i].pitch?.let { it + semitones } ?: 60.0)
    }

    /** Pitch of the selected note, or of every note when [all], from the analysed f0. */
    fun notePitchFromAudio(all: Boolean, round: Boolean = true) {
        val f0 = pitchCurve ?: return app.message(S.pitchNotReady())
        val s = selection as? Selection.Note
        val k = s?.tier ?: doc?.tiers?.indexOfFirst { it is mlabeler.core.model.NoteTier } ?: -1
        val t = noteTier(k) ?: return
        updateDoc { it.replace(k, mlabeler.core.edit.NoteEdits.pitchFromCurve(t, f0, round, if (all) null else setOfNotNull(s?.index))) }
    }

    /** Writes the notes tier to <name>.mid next to the recording. */
    fun exportMidi() {
        val it = item ?: return
        val t = doc?.tiers?.filterIsInstance<mlabeler.core.model.NoteTier>()?.firstOrNull() ?: return app.message(S.noNotes(), error = true)
        val path = Paths.withExt(it.audioPath, "mid")
        runCatching { workspace.fs.write(path, mlabeler.core.format.Midi.write(t)) }
            .onSuccess { app.message(S.saved.format(Paths.name(path))) }
            .onFailure { e -> app.message(S.cannotSave.format(e.message ?: ""), error = true) }
    }

    /** Reads <name>.mid (or .midi) next to the recording into the notes tier. */
    fun importMidi() {
        val it = item ?: return
        val path = listOf("mid", "midi", "MID").map { e -> Paths.withExt(it.audioPath, e) }.firstOrNull { p -> workspace.fs.exists(p) }
            ?: return app.message(S.noMidi.format(Paths.stem(it.audioPath) + ".mid"), error = true)
        val notes = runCatching { mlabeler.core.format.Midi.read(workspace.fs.read(path), duration) }.getOrElse { e -> return app.message(e.message ?: "", error = true) }
        updateDoc { d ->
            val k = d.tiers.indexOfFirst { t -> t is mlabeler.core.model.NoteTier }
            if (k >= 0) d.replace(k, notes) else d.copy(tiers = d.tiers + notes)
        }
    }

    fun splitAt(time: Double = editTime(), tierIndex: Int? = null, askName: Boolean = !Platform.isMobile, playLeft: Boolean = false) {
        val d = committed ?: return
        if (tierIndex != null && tier(tierIndex) != null) activeTier = tierIndex
        noteTier(activeTier)?.let { t ->
            val r = mlabeler.core.edit.NoteEdits.split(t, time) ?: return
            commit(d.replace(activeTier, r.first))
            selectNote(activeTier, r.second)
            return
        }
        val k = if (tier(activeTier) != null) activeTier else d.phonemeTierIndex()
        // the new phoneme is the one the new boundary belongs to: before it ("end", default) or after it
        val newLeft = settings.edit.boundaryOwner == "end"
        val queued = phonemeQueue.firstOrNull()
        val r = Edits.split(d, k, time, queued ?: "", settings.edit.minIntervalMs / 1000.0, newOnLeft = newLeft) ?: return
        commit(r.first)
        val fresh = IntervalRef(k, if (newLeft) r.second.bound - 1 else r.second.bound)
        selectInterval(fresh, reveal = false)
        if (playLeft) tier(k)?.let { t -> val i = r.second.bound - 1; if (i >= 0) play(t.startOf(i), t.endOf(i), loop = false) }
        // with a keyboard, name the new part right away
        // phonemes typed in advance name the new parts one by one
        if (queued != null) { phonemeQueue = phonemeQueue.drop(1); return }
        if (askName) editingText = fresh
    }

    /** Phonemes typed in advance: each new boundary names its part with the next one. */
    var phonemeQueue by mutableStateOf<List<String>>(emptyList())

    fun setQueueText(text: String) {
        phonemeQueue = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    }

    /** The queued phonemes spread evenly over the selected part (or the selected interval), replacing what was there. */
    fun fillWithQueue() {
        val d = committed ?: return
        val names = phonemeQueue.ifEmpty { return app.message(queueEmpty()) }
        val k = (selection as? Selection.Interval)?.ref?.tier?.takeIf { tier(it) != null }
            ?: activeTier.takeIf { tier(it) != null } ?: d.phonemeTierIndex()
        val t = tier(k) ?: return
        val (from, to) = range ?: selectedInterval()?.takeIf { it.tier == k }?.let { t.startOf(it.index) to t.endOf(it.index) }
            ?: return app.message(queueNoPlace())
        val step = (to - from) / names.size
        val part = IntervalTier(t.name, List(names.size + 1) { from + it * step }, names)
        commit(d.replace(k, mlabeler.core.edit.RangeEdits.replace(t, from, to, part)))
        phonemeQueue = emptyList()
        fixSelection()
    }

    private fun dictionary() = Dictionaries.byName(workspace.state.dictionary)

    /** Groups the phonemes into notes (the words tier), using the folder's dictionary. */
    fun groupPhonemes() {
        updateDoc { mlabeler.core.ds.Grouping.withGroups(it, dictionary()) }
        app.message(grouped())
    }

    /** One note per group, its pitch from the recording; replaces the notes tier. */
    fun notesFromGroups() {
        val a = audio ?: return
        val ready = pitchCurve
        scope.launch {
            val f0 = ready ?: withContext(Dispatchers.Default) { mlabeler.core.ds.Dataset.f0(a) }
            val d = committed ?: return@launch
            val withGroups = if (d.wordTierIndex() >= 0) d else mlabeler.core.ds.Grouping.withGroups(d, dictionary())
            val noNotes = withGroups.copy(tiers = withGroups.tiers.filter { it !is mlabeler.core.model.NoteTier })
            val notes = mlabeler.core.ds.Dataset.notesFor(noNotes, f0, 0.0, mlabeler.core.ds.DatasetOptions(dict = dictionary())).tiers.last()
            val k = withGroups.tiers.indexOfFirst { it is mlabeler.core.model.NoteTier }
            commit(if (k >= 0) withGroups.replace(k, notes) else withGroups.copy(tiers = withGroups.tiers + notes))
            fixSelection()
        }
    }

    fun mergeSelected() {
        val d = committed ?: return
        when (val s = selection) {
            is Selection.Note -> { noteTier(s.tier)?.let { commit(d.replace(s.tier, mlabeler.core.edit.NoteEdits.mergeNext(it, s.index))) }; return }
            is Selection.Interval -> commit(Edits.mergeWithNext(d, s.ref))
            is Selection.Bound -> {
                commit(Edits.removeBound(d, s.ref))
                selectInterval(IntervalRef(s.ref.tier, (s.ref.bound - 1).coerceAtLeast(0)), reveal = false)
            }
            Selection.None -> return
        }
        fixSelection()
    }

    fun deleteSelected() {
        val d = committed ?: return
        when (val s = selection) {
            is Selection.Bound -> {
                // the phoneme the boundary belongs to goes away (see EditSettings.boundaryOwner)
                commit(Edits.removeBound(d, s.ref, keepRight = settings.edit.boundaryOwner == "end"))
                selectInterval(IntervalRef(s.ref.tier, (s.ref.bound - 1).coerceAtLeast(0)), reveal = false)
            }
            // the selected phoneme goes away: joined to the one before it (or after it, for the first)
            is Selection.Interval -> if (s.ref.index > 0) {
                commit(Edits.removeBound(d, BoundRef(s.ref.tier, s.ref.index)))
                selectInterval(IntervalRef(s.ref.tier, s.ref.index - 1), reveal = false)
            } else if ((tier(s.ref.tier)?.size ?: 0) > 1) {
                commit(Edits.removeBound(d, BoundRef(s.ref.tier, 1), keepRight = true))
                selectInterval(IntervalRef(s.ref.tier, 0), reveal = false)
            }
            // a note is removed by joining it to the previous one
            is Selection.Note -> noteTier(s.tier)?.let { t ->
                if (s.index > 0) { commit(d.replace(s.tier, mlabeler.core.edit.NoteEdits.mergeNext(t, s.index - 1))); selectNote(s.tier, s.index - 1) }
            }
            Selection.None -> if (range != null) deleteInRange()
        }
        fixSelection()
    }

    /** Sets the left (or right) boundary of the interval at [time] in the active tier to [time]. */
    fun setBoundAtCursor(left: Boolean, time: Double = editTime()) {
        val d = committed ?: return
        val k = if (tier(activeTier) != null) activeTier else d.phonemeTierIndex()
        val t = tier(k) ?: return
        val sel = selectedInterval()?.takeIf { it.tier == k }?.index
        val i = sel ?: t.indexAt(time).takeIf { it >= 0 } ?: return
        val b = if (left) i else i + 1
        commit(Edits.moveBound(d, BoundRef(k, b), time, duration, moveOptions()))
    }

    fun setText(ref: IntervalRef, text: String) {
        val d = committed ?: return
        commit(Edits.setText(d, ref, text))
    }

    fun updateDoc(transform: (LabelDoc) -> LabelDoc) {
        val d = committed ?: return
        commit(transform(d))
        fixSelection()
    }

    // ---------- view ----------

    fun clampView() {
        val vis = visibleDuration
        val maxStart = max(0.0, duration - vis * 0.9)
        viewStart = viewStart.coerceIn(min(0.0, -vis * 0.05), maxStart)
    }

    fun fitAll() {
        val d = duration
        if (d <= 0 || viewWidthPx <= 1f) {
            needsFit = true
            return
        }
        pixelsPerSecond = viewWidthPx / d
        viewStart = 0.0
    }

    fun zoom(factor: Double, anchorTime: Double = viewStart + visibleDuration / 2) {
        val minPps = if (duration > 0) viewWidthPx / duration else 1.0
        val newPps = (pixelsPerSecond * factor).coerceIn(minPps, 20000.0)
        val anchorX = (anchorTime - viewStart) * pixelsPerSecond
        pixelsPerSecond = newPps
        viewStart = anchorTime - anchorX / newPps
        clampView()
    }

    fun scrollBy(px: Double) {
        viewStart += px / pixelsPerSecond
        clampView()
    }

    /** Makes [from]..[to] visible, zooming out if needed. */
    fun reveal(from: Double, to: Double) {
        val vis = visibleDuration
        if (to - from > vis * 0.9) {
            zoomTo(from, to)
            return
        }
        val margin = vis * 0.1
        if (from < viewStart + margin) viewStart = from - margin
        else if (to > viewStart + vis - margin) viewStart = to - vis + margin
        clampView()
    }

    fun zoomTo(from: Double, to: Double) {
        val len = max(to - from, 0.02)
        pixelsPerSecond = (viewWidthPx / (len * 1.2)).coerceIn(1.0, 20000.0)
        viewStart = from - len * 0.1
        clampView()
    }

    fun zoomSelection() {
        val r = range
        if (r != null) return zoomTo(r.first, r.second)
        val s = selectedInterval() ?: return
        val t = tier(s.tier) ?: return
        zoomTo(t.startOf(s.index), t.endOf(s.index))
    }

    // ---------- playback ----------

    /** Plays the selection range, else the selected interval, else from the cursor to the end of the view. */
    fun playOtoEntry() {
        val e = oto.current() ?: return
        val a = oto.absolute(e)
        play(a.left / 1000, a.right / 1000)
    }

    /** The interval a selected boundary belongs to (see EditSettings.boundaryOwner). */
    fun boundOwnerInterval(b: BoundRef): IntervalRef? {
        val t = tier(b.tier) ?: return null
        val i = if (settings.edit.boundaryOwner == "end") b.bound - 1 else b.bound
        return if (i in 0 until t.size) IntervalRef(b.tier, i) else null
    }

    fun togglePlay() {
        if (playing) {
            val lp = lastPlay
            if (settings.edit.spaceRestarts && lp != null) return play(lp.first, lp.second, lp.third)
            return stop()
        }
        if (mode == Mode.Oto && range == null && oto.current() != null) return playOtoEntry()
        val r = range
        val s = selectedInterval() ?: (selection as? Selection.Bound)?.let { boundOwnerInterval(it.ref) }
        val t = s?.let { tier(it.tier) }
        when {
            r != null -> play(r.first, r.second)
            s != null && t != null -> play(t.startOf(s.index), t.endOf(s.index))
            else -> play(cursor ?: viewStart, viewStart + visibleDuration)
        }
    }

    fun playFromCursor() {
        if (playing) stop()
        play(cursor ?: playhead ?: viewStart, duration)
    }

    /** What is playing now, so a change of speed or loop can take effect at once. */
    private var lastPlay: Triple<Double, Double, Boolean>? = null
    private var lastPlayFollowsSettings = false

    /** Speed or loop changed: restart what is playing with the new values (from where it is now). */
    fun playbackSettingsChanged() {
        val lp = lastPlay ?: return
        if (!playing || !lastPlayFollowsSettings) return
        val loop = settings.edit.loop
        val from = if (loop) lp.first else (playhead ?: lp.first)
        play(from, lp.second, loop, keepRange = lp.first)
    }

    fun play(
        from: Double, to: Double, loop: Boolean = settings.edit.loop, speed: Double = settings.edit.speed.toDouble(),
        keepRange: Double? = null,
    ) {
        val a = audio ?: return
        finishEditing()
        stop()
        lastPlay = Triple(keepRange ?: from, to, loop)
        lastPlayFollowsSettings = speed == settings.edit.speed.toDouble()
        val sr = a.sampleRate
        val s = (max(0.0, from) * sr).toInt().coerceIn(0, a.samples.size)
        val e = (min(duration, to) * sr).toInt().coerceIn(s, a.samples.size)
        if (e - s < 16) return
        val slow = speed < 0.99 && e - s > 4096
        val vol = settings.edit.volume.coerceIn(0f, 1f)
        // a quieter or slowed copy of the part: positions map back to the recording
        val copy = slow || vol < 0.995f
        try {
            if (copy) {
                var part = if (slow) mlabeler.core.dsp.Stretch.wsola(a.samples.copyOfRange(s, e), sr, speed) else a.samples.copyOfRange(s, e)
                if (vol < 0.995f) { if (!slow) part = part.copyOf(); for (i in part.indices) part[i] *= vol }
                player.play(Audio(sr, part), 0, part.size, loop)
            } else {
                player.play(a, s, e, loop)
            }
        } catch (ex: Exception) {
            app.message(S.cannotPlay.format(ex.message ?: ex.toString()), error = true)
            return
        }
        playing = true
        playJob = scope.launch {
            delay(30)
            // the sound card reports its position in buffer-sized steps: between steps the playhead runs on the clock,
            // so the picture moves evenly (needed when the view rides along with the playhead)
            var lastRaw = Double.NaN
            var lastAt = 0L
            val rate = if (slow) speed.toDouble() else 1.0
            val clock = kotlin.time.TimeSource.Monotonic.markNow()
            while (isActive && player.isPlaying) {
                val p = player.position()
                if (p >= 0) {
                    val raw = when {
                        slow -> (s + p * speed) / sr
                        copy -> (s + p).toDouble() / sr
                        else -> p.toDouble() / sr
                    }
                    val now = clock.elapsedNow().inWholeNanoseconds
                    if (raw != lastRaw) { lastRaw = raw; lastAt = now }
                    val t = (raw + (now - lastAt) / 1e9 * rate).coerceAtMost(raw + 0.25)
                    playhead = t
                    followPlayhead(t)
                }
                delay(8)
            }
            playing = false
            playhead = null
        }
    }

    /** Keeps the playhead in view while playing (Settings → Editing → following the playback). */
    private fun followPlayhead(t: Double) {
        val mode = settings.edit.follow
        if (mode == "off") return
        val vis = visibleDuration
        if (vis <= 0 || vis >= duration) return
        if (mode == "keep") {
            viewStart = (t - vis * settings.edit.followAt.coerceIn(0f, 1f)).coerceIn(0.0, max(0.0, duration - vis))
        } else if (t > viewStart + vis * 0.98 || t < viewStart) {
            viewStart = (t - vis * 0.02).coerceIn(0.0, max(0.0, duration - vis))
        }
    }

    /** Cycles 1× → 0.75× → 0.5× → 0.25×. */
    fun cycleSpeed() {
        val next = when (settings.edit.speed) { 1f -> 0.75f; 0.75f -> 0.5f; 0.5f -> 0.25f; else -> 1f }
        app.update { it.copy(edit = it.edit.copy(speed = next)) }
    }

    private var lastPreview = 0L

    /** Short sound around [time] while dragging, at most every 80 ms. */
    fun previewAt(time: Double) {
        if (!settings.edit.playOnDrag) return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastPreview < 80) return
        lastPreview = now
        play(time - 0.03, time + 0.03, loop = false, speed = 1.0)
    }

    fun stop() {
        playJob?.cancel()
        player.stop()
        playing = false
        playhead = null
    }
}

private val batchFile = L("File {0} of {1}: {2}", "Файл {0} из {1}: {2}")
private val batchDone = L("Labelled {0} files", "Размечено файлов: {0}")
private val batchDoneWithErrors = L("Labelled {0} files, {1} with errors:", "Размечено файлов: {0}, с ошибками: {1}:")
private val batchStopped = L("Stopped: {0} of {1} files labelled and saved", "Остановлено: размечено и сохранено {0} из {1}")
private val noText = L("no text (a .txt with the same name, or Whisper)", "нет текста (.txt с тем же именем или Whisper)")
private val reviewNothing = L("Nothing to look at in this file", "В этом файле нечего проверять")
private val reviewEnd = L("That was the last of {0} places in this file; PgDn opens the next file", "Это было последнее из {0} мест в этом файле; PgDn — следующий файл")
private val reviewPos = L("Place {0} of {1}", "Место {0} из {1}")
