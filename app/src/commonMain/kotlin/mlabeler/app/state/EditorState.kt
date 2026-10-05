package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.AudioOut
import mlabeler.app.Platform
import mlabeler.app.i18n.S
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
import mlabeler.core.io.Workspace
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.math.max
import kotlin.math.min

sealed interface Selection {
    data object None : Selection
    data class Interval(val ref: IntervalRef) : Selection
    data class Bound(val ref: BoundRef) : Selection
}

enum class FileFilter { All, NotDone, Starred, NoLabels }

enum class Mode { Labels, Oto }

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

    fun scan() {
        items = workspace.scan()
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

    /** Interval tiers drawn below the edited ones: (reference index, tier). */
    val referenceTiers: List<Pair<Int, IntervalTier>> get() =
        references.withIndex().flatMap { (k, r) -> r.doc.tiers.filterIsInstance<IntervalTier>().map { k to it } }

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
        private set
    private var toolkitJob: Job? = null

    /**
     * Aligns [from]..[to] with [model]; the result replaces that part of the tiers ([replace]) or is shown
     * as a comparison tier named after the model.
     */
    fun autolabel(from: Double, to: Double, model: String, language: String?, text: String, phonemes: Boolean, replace: Boolean) {
        val a = audio ?: return
        val it = item ?: return
        val t = app.settings.toolkit
        toolkitJob?.cancel()
        toolkitJob = scope.launch {
            val client = mlabeler.app.toolkit.ToolkitClient(t.url, t.token)
            try {
                toolkitBusy = S.uploading()
                val s0 = (from * a.sampleRate).toInt().coerceIn(0, a.samples.size)
                val s1 = (to * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
                val wav = withContext(Dispatchers.Default) { Wav.encode16(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))) }
                val fileId = client.upload(it.name + "_part.wav", wav)
                val job = client.align(fileId, model, language, text, phonemes)
                val result = client.await(job) { p, stage -> toolkitBusy = "${(p * 100).toInt()}%  $stage" }
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
                app.message(S.autolabelDone())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                toolkitBusy = null
            }
        }
    }

    fun cancelToolkit() {
        toolkitJob?.cancel()
        toolkitBusy = null
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

    /** Scans the folder again, keeping the open file. */
    fun rescan() {
        val id = item?.id
        items = workspace.scan()
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

    fun filtered(): List<Pair<Int, Item>> {
        marksVersion
        val q = query.trim().lowercase()
        return items.withIndex().filter { (_, it) ->
            (q.isEmpty() || it.id.lowercase().contains(q)) && when (filter) {
                FileFilter.All -> true
                FileFilter.NotDone -> !marks(it).done
                FileFilter.Starred -> marks(it).star
                FileFilter.NoLabels -> it.labelPath == null
            }
        }.map { it.index to it.value }
    }

    fun open(i: Int) {
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
    }

    fun openRelative(delta: Int) {
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

    private fun load(item: Item) {
        loadJob?.cancel()
        audio = null
        peaks = null
        pitch = null
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
                    runCatching { workspace.readLabels(item, a.duration) }
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
            pendingInterval?.let { (tierName, i) ->
                pendingInterval = null
                val k = doc?.tierIndex(tierName) ?: -1
                if (k >= 0) selectInterval(IntervalRef(k, i))
            }
            loading = false
            docChanged()
            oto.onItemOpened()
            loadReferences()
            peaks = withContext(Dispatchers.Default) { Peaks.build(a.samples, a.sampleRate) }
            power = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.power(a.samples, a.sampleRate) }
            if (settings.layout.showPitch) pitch = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate) }
            val hop = if (a.duration <= 120) 0.0025 else 0.005
            val spec = withContext(Dispatchers.Default) {
                Spectrogram.compute(a.samples, a.sampleRate, hopSeconds = hop, maxFreq = 16000.0) { partial ->
                    ensureActive()
                    if (spectrogram !== partial) spectrogram = partial
                    spectrogramProgress = partial.ready
                }
            }
            spectrogram = spec
            spectrogramProgress = spec.ready
            if (pitch == null) pitch = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate) }
        }
    }

    // ---------- document ----------

    private fun docChanged() {
        docVersion++
        val d = committed ?: return
        problems = Checks.run(d, settings.checks)
    }

    fun commit(newDoc: LabelDoc) {
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
            Selection.None -> null
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
            Selection.None -> t.nearestBound(cursor ?: (viewStart + visibleDuration / 2))
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
        Selection.None -> null
    }

    fun selectedInterval(): IntervalRef? = (selection as? Selection.Interval)?.ref

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

    fun endDrag() {
        val d = dragDoc
        dragBase = null
        if (d != null) commit(d) else dragDoc = null
    }

    fun cancelDrag() {
        dragBase = null
        dragDoc = null
    }

    fun moveSelectedBound(time: Double) {
        val ref = (selection as? Selection.Bound)?.ref ?: return
        val d = committed ?: return
        commit(Edits.moveBound(d, ref, time, duration, moveOptions()))
    }

    fun nudge(steps: Int) {
        val ref = (selection as? Selection.Bound)?.ref ?: return
        val t = tier(ref.tier) ?: return
        moveSelectedBound(t.bounds[ref.bound] + steps * settings.edit.nudgeMs / 1000.0)
    }

    /** Where edits at "the cursor" happen: the mouse position, else the playhead, else the middle of the view. */
    fun editTime(): Double = cursor ?: playhead ?: (viewStart + visibleDuration / 2)

    fun splitAt(time: Double = editTime()) {
        val d = committed ?: return
        val k = if (tier(activeTier) != null) activeTier else d.phonemeTierIndex()
        val r = Edits.split(d, k, time, "", settings.edit.minIntervalMs / 1000.0) ?: return
        commit(r.first)
        val right = IntervalRef(k, r.second.bound)
        selectInterval(right, reveal = false)
        // with a keyboard, name the new part right away
        if (!Platform.isMobile) editingText = right
    }

    fun mergeSelected() {
        val d = committed ?: return
        when (val s = selection) {
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
                commit(Edits.removeBound(d, s.ref))
                selectInterval(IntervalRef(s.ref.tier, (s.ref.bound - 1).coerceAtLeast(0)), reveal = false)
            }
            is Selection.Interval -> if (s.ref.index > 0) {
                commit(Edits.removeBound(d, BoundRef(s.ref.tier, s.ref.index)))
                selectInterval(IntervalRef(s.ref.tier, s.ref.index - 1), reveal = false)
            }
            Selection.None -> Unit
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
        val minPps = if (duration > 0) viewWidthPx / duration / 2 else 1.0
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
        play(a.left / 1000, a.right / 1000, loop = false)
    }

    fun togglePlay() {
        if (playing) return stop()
        if (mode == Mode.Oto && range == null && oto.current() != null) return playOtoEntry()
        val r = range
        val s = selectedInterval()
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

    fun play(from: Double, to: Double, loop: Boolean = settings.edit.loop) {
        val a = audio ?: return
        stop()
        val sr = a.sampleRate
        val s = (max(0.0, from) * sr).toInt().coerceIn(0, a.samples.size)
        val e = (min(duration, to) * sr).toInt().coerceIn(s, a.samples.size)
        if (e - s < 16) return
        try {
            player.play(a, s, e, loop)
        } catch (ex: Exception) {
            app.message(S.cannotPlay.format(ex.message ?: ex.toString()), error = true)
            return
        }
        playing = true
        playJob = scope.launch {
            delay(30)
            while (isActive && player.isPlaying) {
                val p = player.position()
                if (p >= 0) playhead = p.toDouble() / sr
                delay(16)
            }
            playing = false
            playhead = null
        }
    }

    private var lastPreview = 0L

    /** Short sound around [time] while dragging, at most every 80 ms. */
    fun previewAt(time: Double) {
        if (!settings.edit.playOnDrag) return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastPreview < 80) return
        lastPreview = now
        play(time - 0.03, time + 0.03, loop = false)
    }

    fun stop() {
        playJob?.cancel()
        player.stop()
        playing = false
        playhead = null
    }
}
