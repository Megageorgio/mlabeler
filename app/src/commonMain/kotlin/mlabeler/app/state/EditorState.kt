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
private val removedInRange = L("Boundaries removed in the selected part: {0}", "Удалено границ в выделенном фрагменте: {0}")
private val queueEmpty = L("Type the phonemes first", "Сначала впишите фонемы")
private val queueNoPlace = L("Select a part or a phoneme to fill", "Выделите фрагмент или фонему для заполнения")
private val grouped = L("Phonemes are grouped into notes (the words tier)", "Фонемы сгруппированы по нотам (слой words)")

class EditorState(
    val workspace: Workspace,
    val app: AppState,
    internal val scope: CoroutineScope,
) {
    var items by mutableStateOf<List<Item>>(emptyList())
        internal set
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

    // ---------- voicing and loudness drawn by hand ----------

    /** The pitch lane marks parts unvoiced (left button) or voiced again (right button) instead of drawing. */
    var vuvTool by mutableStateOf(false)

    /**
     * Marks [t0]..[t1] unvoiced, or voiced again: where the recording has a pitch there it comes back, elsewhere a
     * pitch is drawn between the voiced parts on both sides.
     */
    fun setVoicing(t0: Double, t1: Double, voiced: Boolean) {
        val p = pitch ?: return
        val cur = pitchCurve ?: return
        val e = f0Edits?.copyOf() ?: FloatArray(p.values.size) { Float.NaN }
        if (e.isEmpty()) return
        val a = (minOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        val b = (maxOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        if (!voiced) {
            for (i in a..b) e[i] = 0f
        } else {
            fun on(i: Int) = cur.values[i] > 0f
            var l = a - 1
            while (l >= 0 && !on(l)) l--
            var r = b + 1
            while (r < e.size && !on(r)) r++
            val lv = if (l >= 0) cur.values[l] else null
            val rv = if (r < e.size) cur.values[r] else null
            for (i in a..b) {
                if (on(i)) continue
                if (p.values[i] > 0f) { e[i] = Float.NaN; continue }
                e[i] = when {
                    lv != null && rv != null -> (lv * kotlin.math.exp(kotlin.math.ln((rv / lv).toDouble()) * (i - l) / (r - l))).toFloat()
                    else -> lv ?: rv ?: 220f
                }
            }
        }
        f0Edits = e
    }

    /** Loudness drawn by hand: dB added to each frame of [power] (NaN = unchanged). */
    var gainEdits by mutableStateOf<FloatArray?>(null)
        private set
    /** Drawing the loudness in the loudness lane. */
    var dynPencil by mutableStateOf(false)
    private val gainUndo = ArrayDeque<FloatArray?>()

    /** The loudness shown: the analysed one with the drawn changes. */
    val loudnessCurve: mlabeler.core.dsp.Curve?
        get() {
            val p = power ?: return null
            val e = gainEdits ?: return p
            return mlabeler.core.dsp.Curve(p.hop, FloatArray(p.values.size) { i -> p.values[i] + (e.getOrNull(i)?.takeIf { !it.isNaN() } ?: 0f) })
        }

    private fun gainPath(id: String) = Paths.join(Paths.join(workspace.metaDir, "gain"), id.replace('/', '_').replace('\\', '_') + ".gain")

    private fun loadGainEdits() {
        val it = item ?: return
        val p = power ?: return
        gainUndo.clear()
        gainEdits = runCatching {
            val lines = workspace.fs.read(gainPath(it.id)).decodeToString().lines().filter { l -> l.isNotBlank() }
            val out = FloatArray(p.values.size) { Float.NaN }
            for (l in lines.drop(1)) {
                val (t, v) = l.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toFloat() }
                val i = (t / p.hop).toInt()
                if (i in out.indices) out[i] = v
            }
            out
        }.getOrNull()
    }

    private fun saveGainEdits() {
        val it = item ?: return
        val p = power ?: return
        val e = gainEdits
        val path = gainPath(it.id)
        runCatching {
            if (e == null || e.all { v -> v.isNaN() }) { if (workspace.fs.exists(path)) workspace.fs.delete(path); return }
            val sb = StringBuilder("hop ${p.hop}\n")
            for (i in e.indices) if (!e[i].isNaN()) sb.append(((i * p.hop * 10000).toLong() / 10000.0)).append(' ').append(e[i]).append('\n')
            workspace.fs.mkdirs(Paths.parent(path))
            workspace.fs.write(path, sb.toString().encodeToByteArray())
        }
    }

    fun beginGainStroke() {
        gainUndo.addLast(gainEdits?.copyOf())
        while (gainUndo.size > 50) gainUndo.removeFirst()
    }

    /** Draws the loudness from [t0] at [db0] to [t1] at [db1] (dB of full scale); null erases the drawing there. */
    fun drawGain(t0: Double, db0: Float?, t1: Double, db1: Float?) {
        val p = power ?: return
        val e = gainEdits?.copyOf() ?: FloatArray(p.values.size) { Float.NaN }
        if (e.isEmpty()) return
        val a = (minOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        val b = (maxOf(t0, t1) / p.hop).toInt().coerceIn(0, e.size - 1)
        for (i in a..b) {
            if (db0 == null || db1 == null) { e[i] = Float.NaN; continue }
            val f = if (b == a) 0f else (i - a).toFloat() / (b - a)
            val target = if (t0 <= t1) db0 + (db1 - db0) * f else db1 + (db0 - db1) * f
            e[i] = (target - p.values[i].coerceAtLeast(-60f)).coerceIn(-40f, 24f)
        }
        gainEdits = e
    }

    fun endGainStroke() = saveGainEdits()

    fun undoGain() {
        if (gainUndo.isEmpty()) return
        gainEdits = gainUndo.removeLast()
        saveGainEdits()
    }

    val canUndoGain: Boolean get() = gainUndo.isNotEmpty()

    /** Forgets the drawn loudness (can be undone). */
    fun resetGain() {
        if (gainEdits == null) return
        beginGainStroke()
        gainEdits = null
        saveGainEdits()
    }

    /** The drawn loudness was written into the sound: the drawing goes (the loudness is analysed again). */
    internal fun gainApplied() {
        gainUndo.clear()
        gainEdits = null
        saveGainEdits()
    }

    /** Drags note [i] of [tier] up or down to MIDI [pitch]. */
    fun notePitchDragTo(tier: Int, i: Int, pitch: Double) {
        val base = noteDragBase ?: return
        val t = base.tiers[tier] as? mlabeler.core.model.NoteTier ?: return
        dragDoc = base.replace(tier, mlabeler.core.edit.NoteEdits.setPitch(t, i, pitch))
    }
    /** Formants of the open recording, worked out when they are shown. */
    var formants by mutableStateOf<mlabeler.core.dsp.FormantTrack?>(null)
        private set
    private var formantsJob: Job? = null

    fun ensureFormants() {
        val a = audio ?: return
        if (formants != null || formantsJob?.isActive == true) return
        formantsJob = scope.launch { formants = withContext(Dispatchers.Default) { mlabeler.core.dsp.Formants.track(a.samples, a.sampleRate) } }
    }

    var power by mutableStateOf<mlabeler.core.dsp.Curve?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    internal val histories = mutableMapOf<String, History<LabelDoc>>()
    private var history: History<LabelDoc>? = null
    /** Changes whenever the document or its saved state changes. */
    var docVersion by mutableIntStateOf(0)
        internal set
    private var dragDoc by mutableStateOf<LabelDoc?>(null)
    internal var committed by mutableStateOf<LabelDoc?>(null)

    val doc: LabelDoc? get() = dragDoc ?: committed
    var mode by mutableStateOf(Mode.Labels)
    val oto = OtoState(this, app)
    val cleanup = Cleanup(this, app)

    val labelsDirty: Boolean get() { docVersion; return history?.dirty == true }
    val dirty: Boolean get() = if (mode == Mode.Oto) oto.dirty else labelsDirty
    val canUndo: Boolean get() { docVersion; return audioStepToUndo() != null || if (mode == Mode.Oto) oto.canUndo else history?.canUndo == true }
    val canRedo: Boolean get() { docVersion; return audioStepToRedo() != null || if (mode == Mode.Oto) oto.canRedo else history?.canRedo == true }

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
            if (fitLimit < Double.MAX_VALUE) fitStart() else fitAll()
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
    internal val labelMtime = mutableMapOf<String, Long>()
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

    internal fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

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

    internal val settings get() = app.settings
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
    internal val modelResults = mutableMapOf<String, List<Reference>>()

    fun loadReferences() {
        val it = item
        val dur = duration
        references = if (it == null) emptyList() else workspace.state.compareFolders.mapNotNull { dir ->
            workspace.readLabelsIn(dir, it.name, dur)?.let { d -> Reference(Paths.name(dir), dir, d) }
        } + modelResults[it.id].orEmpty()
    }

    /** The place [goToDifference] went to last. */
    private var lastDifference: Double? = null

    /**
     * Selects the next (or the previous) place where [r] differs from the labels: a boundary 30 ms or more away, or
     * another text. Returns how many such places there are (0: none).
     */
    fun goToDifference(r: Reference, forward: Boolean): Int {
        val d = doc ?: return 0
        // (time, tier of the labels it belongs to)
        val spots = r.doc.tiers.filterIsInstance<IntervalTier>().flatMap { t ->
            val main = mlabeler.core.check.Compare.counterpart(d, t) ?: return@flatMap emptyList()
            val k = d.tiers.indexOf(main)
            mlabeler.core.check.Compare.differences(main, t).map { it to k }
        }.sortedBy { it.first }
        if (spots.isEmpty()) return 0
        val span = selectedSpan()
        val last = lastDifference
        val here = if (last != null && span != null && last >= span.first - 1e-6 && last <= span.second + 1e-6) last
            else span?.let { (a, b) -> (a + b) / 2 } ?: cursor ?: viewStart
        val (t, k) = if (forward) spots.firstOrNull { it.first > here + 0.003 } ?: spots.first()
            else spots.lastOrNull { it.first < here - 0.003 } ?: spots.last()
        lastDifference = t
        val i = (d.tiers.getOrNull(k) as? IntervalTier)?.indexAt(t) ?: -1
        if (i >= 0) selectInterval(IntervalRef(k, i)) else reveal(t - 0.2, t + 0.2)
        return spots.size
    }

    fun dropModelResult(r: Reference) {
        val id = item?.id ?: return
        modelResults[id] = modelResults[id].orEmpty() - r
        loadReferences()
    }

    // ---------- autolabel through the toolkit ----------

    private var busyText by mutableStateOf<String?>(null)
    /** What the toolkit is doing for this folder, in words; null when nothing. */
    var toolkitBusy: String?
        get() = busyText
        set(v) {
            val was = busyText != null
            busyText = v
            if (v == null) {
                toolkitDetail = null
                if (was && toolkitBusySince > 0) app.workFinished(toolkitBusySince)
            }
        }
    /** The toolkit job being waited for: its step and numbers (a download's megabytes, files done). */
    var toolkitDetail by mutableStateOf<mlabeler.app.toolkit.JobProgress?>(null)
    /** When the current toolkit work began (ms) and its earlier steps, for the busy panel. */
    var toolkitBusySince = 0L
        internal set

    /** Marks the start of toolkit work shown in the busy panel (its time counts from here). */
    fun beginToolkitWork(what: String) {
        toolkitBusySince = now()
        toolkitSteps.clear()
        toolkitDetail = null
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
     * Writes the labels of [f], a file other than the open one, for work over many files (autolabel, refinement, a
     * plugin): its undo history goes, the list of files and the change times follow.
     */
    fun writeOtherLabels(f: Item, doc: LabelDoc, duration: Double, format: mlabeler.core.format.LabelFormat? = null) {
        val updated = workspace.writeLabels(f, doc, duration, format)
        histories.remove(f.id)
        items = items.map { x -> if (x.id == f.id) updated else x }
        updated.labelPath?.let { p -> labelTimes = labelTimes + (updated.id to (runCatching { workspace.fs.lastModified(p) }.getOrNull() ?: 0L)) }
        labelMtime[updated.id] = updated.labelPath?.let { p -> workspace.fs.lastModified(p) } ?: 0L
    }

    /** The labels of [f] as they are now: the open one's, unsaved changes of another, else its file; null without any. */
    fun currentLabels(f: Item): LabelDoc? =
        if (f.id == item?.id) doc else histories[f.id]?.current ?: f.labelPath?.let { runCatching { workspace.readLabels(f, 0.0) }.getOrNull() }

    /** After labels of many files changed at once: the search over all labels reads them again. */
    fun labelsChangedOnDisk() { labelIndex = null; docVersion++ }

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
     * Adds to the units of the transcription (.trans) the unit of [size] phonemes starting at the selected phoneme,
     * or takes it away when it is there; the units are a tier of their own ([mlabeler.core.format.SegUnits.TIER]).
     */
    fun toggleUnit(size: Int) {
        val d = doc ?: return
        val k = d.phonemeTierIndex()
        val ph = d.tiers.getOrNull(k) as? IntervalTier ?: return
        val ref = selectedInterval()
        val i = when {
            ref == null -> ph.indexAt(cursor ?: return)
            ref.tier == k -> ref.index
            else -> (d.tiers.getOrNull(ref.tier) as? IntervalTier)?.let { t -> ph.indexAt(t.startOf(ref.index) + 1e-6) } ?: -1
        }
        if (i < 0 || i + size > ph.size) return
        val u = d.tierIndex(mlabeler.core.format.SegUnits.TIER)
        val old = (if (u >= 0) d.tiers[u] else null) as? IntervalTier
        val t = mlabeler.core.format.SegUnits.toggle(old, ph, i, size)
        updateDoc { x -> if (u >= 0) x.replace(u, t) else x.copy(tiers = x.tiers + t) }
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
        updateDocShowingChanges { d ->
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
        val hop = if (v.hopMs > 0) v.hopMs / 1000.0 else if (a.duration <= (if (mlabeler.app.Platform.isMobile) 120 else 900)) 0.0025 else 0.005
        val builder = Spectrogram.Builder(
            a.samples, a.sampleRate, hopSeconds = hop, windowSeconds = v.windowMs / 1000.0, bands = v.bands,
            maxFreq = 16000.0, minDb = v.minDb, maxDb = v.maxDb,
        )
        val spec = builder.result
        // a new picture starts from nothing; without this, reading the same file again keeps the old count and the
        // view is never redrawn past the first (empty) frame
        spectrogramProgress = 0
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

    /** What renaming the recording at [i] would touch (files, oto entries, csv rows) and why it can't be done. */
    fun planRename(i: Int, newStem: String): Workspace.FilePlan? = items.getOrNull(i)?.let { workspace.planRename(it, newStem.trim()) }

    /**
     * Renames the recording at [i] with every file named after it (labels, .trans, MIDI, UTAU caches), its oto
     * entries, its transcriptions.csv rows, marks and drawn pitch. Changes are saved first.
     */
    fun renameFile(i: Int, newStem: String) {
        val it = items.getOrNull(i) ?: return
        finishEditing()
        if (dirty) save(quiet = true)
        val openId = item?.id
        val wasOpen = i == index
        stop()
        try {
            val f0Old = f0Path(it.id)
            val renamed = workspace.rename(it, newStem.trim()) ?: return
            if (workspace.fs.exists(f0Old)) runCatching {
                workspace.fs.copy(f0Old, f0Path(renamed.id)); workspace.fs.delete(f0Old)
            }
            histories.remove(it.id)
            oto.forget()
            items = workspace.items
            labelIndex = null
            readLabelTimes()
            marksVersion++
            val target = if (wasOpen) renamed.id else openId
            index = items.indexOfFirst { x -> x.id == target }
            if (wasOpen && index >= 0) load(items[index])
            app.message(renamedFileT.format(Paths.name(it.audioPath), Paths.name(renamed.audioPath)))
        } catch (e: Exception) {
            app.message(fileOpFailedT.format(e.message ?: e.toString()), error = true)
        }
    }

    /** Moves the recording at [i] and its files into .mlabeler/trash and takes its entries out of oto.ini and csv. */
    fun trashFile(i: Int) {
        val it = items.getOrNull(i) ?: return
        finishEditing()
        if (dirty) save(quiet = true)
        val openId = item?.id
        val wasOpen = i == index
        stop()
        try {
            val dest = workspace.trash(it)
            histories.remove(it.id)
            oto.forget()
            items = workspace.items
            labelIndex = null
            readLabelTimes()
            marksVersion++
            index = if (wasOpen) -1 else items.indexOfFirst { x -> x.id == openId }
            if (wasOpen && items.isNotEmpty()) open(i.coerceAtMost(items.size - 1))
            app.message(trashedT.format(Paths.name(it.audioPath), workspace.relative(dest)))
        } catch (e: Exception) {
            app.message(fileOpFailedT.format(e.message ?: e.toString()), error = true)
        }
    }

    // ---------- units of the transcriptions over the whole folder ----------

    /** A place where a unit is or could be: the file, its first phoneme, how many, the times, whether it is chosen. */
    data class UnitPlace(val itemId: String, val unit: String, val index: Int, val size: Int, val start: Double, val end: Double, val chosen: Boolean)

    /** The .seg/.trans recordings of the folder: every chosen unit and every change between two phonemes. */
    suspend fun unitPlaces(progress: (Int, Int) -> Unit): List<UnitPlace> = withContext(Dispatchers.Default) {
        val segs = items.filter { it.labelFormat == mlabeler.core.format.LabelFormat.Seg }
        val out = mutableListOf<UnitPlace>()
        for ((n, it) in segs.withIndex()) {
            progress(n, segs.size)
            val d = (if (it.id == item?.id) committed else null) ?: runCatching {
                workspace.readLabels(it, Wav.decode(workspace.fs.read(it.audioPath)).duration)
            }.getOrNull() ?: continue
            val ph = d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier ?: continue
            val names = mlabeler.core.format.SegUnits.phonemeNames(ph)
            val unitsTier = d.tiers.filterIsInstance<IntervalTier>().firstOrNull { t -> t.name == mlabeler.core.format.SegUnits.TIER }
            val parsed = unitsTier?.let { u -> mlabeler.core.format.SegUnits.parse(u, ph) }.orEmpty()
            val chosen = parsed.associateBy { u -> u.index to u.phonemes.size }
            val places = (chosen.keys + (0 until names.size - 1).map { i -> i to 2 }).distinct()
            for ((i, size) in places) {
                // the unlabelled rest after the labels (they may end before the sound) is not a phoneme
                if (i < 0 || i + size > names.size || (i + size - 1 == ph.size - 1 && ph.texts.last().isEmpty())) continue
                // a kept transition plays as the lane has it, another a little around its change
                val b = chosen[i to size]?.bounds ?: mlabeler.core.format.SegUnits.defaultBounds(ph, i, size)
                out += UnitPlace(it.id, names.subList(i, i + size).joinToString(" "), i, size, b.first(), b.last(), (i to size) in chosen)
            }
        }
        progress(segs.size, segs.size)
        out
    }

    /** Plays the phonemes of [p] from its recording. */
    fun playPlace(p: UnitPlace) {
        val it = items.firstOrNull { x -> x.id == p.itemId } ?: return
        scope.launch {
            val a = withContext(Dispatchers.Default) { runCatching { Wav.decode(workspace.fs.read(it.audioPath)) }.getOrNull() } ?: return@launch
            val s0 = (p.start * a.sampleRate).toInt().coerceIn(0, a.samples.size)
            val s1 = (p.end * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
            playBuffer(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1)))
        }
    }

    /** Saves the chosen units of the recordings in [changed] (file id → its places); returns the files written. */
    suspend fun applyUnits(changed: Map<String, List<Pair<Int, Int>>>): Int {
        var n = 0
        for ((id, places) in changed) {
            val it = items.firstOrNull { x -> x.id == id } ?: continue
            if (id == item?.id) {
                val d = committed ?: continue
                val ph = d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier ?: continue
                val u = d.tierIndex(mlabeler.core.format.SegUnits.TIER)
                val t = mlabeler.core.format.SegUnits.tierAt(ph, places, d.tiers.getOrNull(u) as? IntervalTier)
                updateDoc { x -> if (u >= 0) x.replace(u, t) else x.copy(tiers = x.tiers + t) }
                saveLabels(quiet = true)
                n++
                continue
            }
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    val a = Wav.decode(workspace.fs.read(it.audioPath))
                    val d = workspace.readLabels(it, a.duration)
                    val ph = d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier ?: return@runCatching false
                    val u = d.tierIndex(mlabeler.core.format.SegUnits.TIER)
                    val t = mlabeler.core.format.SegUnits.tierAt(ph, places, d.tiers.getOrNull(u) as? IntervalTier)
                    workspace.writeLabels(it, if (u >= 0) d.replace(u, t) else d.copy(tiers = d.tiers + t), a.duration)
                    histories.remove(it.id)
                    true
                }.getOrDefault(false)
            }
            if (ok) n++
        }
        items = workspace.items
        readLabelTimes()
        return n
    }

    // ---------- several files at once ----------

    /** Files picked in the list (by id) for doing something with all of them; the open file is apart from this. */
    var pickedFiles by mutableStateOf<Set<String>>(emptySet())
    private var pickAnchor: String? = null

    /** Ctrl+click: one file in or out of the picked ones. */
    fun togglePicked(item: Item) {
        pickedFiles = if (item.id in pickedFiles) pickedFiles - item.id else pickedFiles + item.id
        pickAnchor = item.id
    }

    /** Shift+click: every listed file from the last picked one to [item]. */
    fun pickRange(item: Item) {
        val list = filtered().map { it.second.id }
        val a = list.indexOf(pickAnchor ?: this.item?.id).takeIf { it >= 0 } ?: list.indexOf(this.item?.id)
        val b = list.indexOf(item.id)
        if (a < 0 || b < 0) { togglePicked(item); return }
        pickedFiles = pickedFiles + list.subList(minOf(a, b), maxOf(a, b) + 1)
    }

    fun clearPicked() { pickedFiles = emptySet(); pickAnchor = null }

    /** The picked files in the order of the list. */
    fun pickedItems(): List<Item> = items.filter { it.id in pickedFiles }

    /** Moves the files [ids] (with everything named after them) to the folder's trash, as [trashFile] does one. */
    fun trashFiles(ids: Set<String>) {
        finishEditing()
        if (dirty) save(quiet = true)
        stop()
        val openId = item?.id
        var moved = 0
        var dest = ""
        val failed = mutableListOf<String>()
        for (id in ids) {
            val it = workspace.items.firstOrNull { x -> x.id == id } ?: continue
            try {
                dest = workspace.trash(it)
                histories.remove(id)
                moved++
            } catch (e: Exception) {
                failed += Paths.name(it.audioPath) + ": " + (e.message ?: e.toString())
            }
        }
        oto.forget()
        items = workspace.items
        labelIndex = null
        readLabelTimes()
        marksVersion++
        clearPicked()
        index = items.indexOfFirst { x -> x.id == openId }
        if (index < 0 && items.isNotEmpty()) open(0)
        if (failed.isEmpty()) app.message(trashedManyT.format(moved, workspace.relative(dest)))
        else app.message(fileOpFailedT.format(failed.joinToString("; ")), error = true)
    }

    /**
     * Joins the recordings [files] (in this order) into a new one named [name] next to the first, [gap] seconds of
     * silence between them, with their labels: every interval tier of any of them, its times moved along (a file
     * without that tier leaves it unlabelled there). The labels are in the first file's format. With [removeOld] the
     * joined files go to the trash. Returns false when it couldn't (the message says why).
     */
    fun mergeFiles(files: List<Item>, name: String, gap: Double, removeOld: Boolean): Boolean {
        if (files.size < 2) return false
        finishEditing()
        if (dirty) save(quiet = true)
        stop()
        val first = files.first()
        val dir = Paths.parent(first.audioPath)
        val wavPath = Paths.join(dir, name.trim() + ".wav")
        if (name.isBlank() || name.any { it in "/\\:*?\"<>|" }) { app.message(fileOpFailedT.format(badMergeNameT()), error = true); return false }
        if (workspace.fs.exists(wavPath)) { app.message(fileOpFailedT.format(mergeExistsT.format(Paths.name(wavPath))), error = true); return false }
        return try {
            val parts = files.map { f ->
                val bytes = workspace.fs.read(f.audioPath)
                val a = if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
                val d = runCatching { if (f.id == item?.id) committed else null }.getOrNull() ?: runCatching { workspace.readLabels(f, a.duration) }.getOrNull()
                Triple(f, a, d)
            }
            val rate = parts.first().second.sampleRate
            val sounds = parts.map { (_, a, _) -> if (a.sampleRate == rate) a.samples else mlabeler.core.dsp.Stretch.resample(a.samples, a.sampleRate.toDouble() / rate) }
            val gapN = (gap * rate).toInt().coerceAtLeast(0)
            val all = FloatArray(sounds.sumOf { it.size } + gapN * (sounds.size - 1))
            val offsets = mutableListOf<Double>()
            var pos = 0
            for ((k, x) in sounds.withIndex()) {
                offsets += pos.toDouble() / rate
                x.copyInto(all, pos)
                pos += x.size + if (k < sounds.size - 1) gapN else 0
            }
            val total = all.size.toDouble() / rate
            // the tiers by name, in the order they first turn up
            val names = parts.flatMap { (_, _, d) -> d?.tiers?.filterIsInstance<IntervalTier>()?.map { it.name } ?: emptyList() }.distinct()
            val tiers = names.map { tierName ->
                val ivs = mutableListOf<Triple<Double, Double, String>>()
                for ((k, part) in parts.withIndex()) {
                    val t = part.third?.tiers?.filterIsInstance<IntervalTier>()?.firstOrNull { it.name == tierName } ?: continue
                    val o = offsets[k]
                    val len = sounds[k].size.toDouble() / rate
                    for (i in 0 until t.size) {
                        if (t.texts[i].isEmpty()) continue
                        val a = t.startOf(i).coerceIn(0.0, len)
                        val b = t.endOf(i).coerceIn(0.0, len)
                        if (b > a) ivs += Triple(o + a, o + b, t.texts[i])
                    }
                }
                IntervalTier.fromIntervals(tierName, ivs, total)
            }
            val doc = if (tiers.isEmpty()) LabelDoc.empty(total) else LabelDoc(tiers)
            workspace.fs.write(wavPath, Wav.encode16(Audio(rate, all)))
            items = workspace.scan()
            val newItem = items.first { it.audioPath == wavPath }
            workspace.writeLabels(newItem, doc, total, first.labelFormat ?: workspace.state.defaultFormat)
            if (removeOld) trashFiles(files.map { it.id }.toSet()) else { items = workspace.scan(); clearPicked() }
            items = workspace.items
            labelIndex = null
            readLabelTimes()
            marksVersion++
            items.indexOfFirst { it.audioPath == wavPath }.takeIf { it >= 0 }?.let { open(it) }
            app.message(mergedT.format(files.size, Paths.name(wavPath)))
            true
        } catch (e: Exception) {
            app.message(fileOpFailedT.format(e.message ?: e.toString()), error = true)
            false
        }
    }

    /** Scans the folder again, keeping the open file. */
    fun rescan() {
        val id = item?.id
        items = workspace.scan()
        labelIndex = null
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
    internal var labelIndex by mutableStateOf<Map<String, Set<String>>?>(null)
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
        carriedZoom = if (audio != null) pixelsPerSecond else null
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
        if (workspace.audioChanged()) rescan()
        val it = item ?: return
        finishEditing()
        rememberView()
        stop()
        val sel = selection
        // the view stays exactly where it was (a cut or a fade must not jump to the whole file)
        val view = viewStart to pixelsPerSecond
        load(it)
        pendingSelection = sel
        pendingView = view
    }

    private var pendingView: Pair<Double, Double>? = null

    private var pendingSelection: Selection? = null
    private var lastLoadedPath = ""
    /** Scale of the previously open file, carried to the next one when [EditSettings.keepZoom] is on. */
    private var carriedZoom: Double? = null

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
        vuvTool = false
        gainEdits = null
        dynPencil = false
        power = null
        formantsJob?.cancel()
        formants = null
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
            val carried = carriedZoom
            carriedZoom = null
            if (carried != null && settings.edit.keepZoom) {
                // the same scale as the file before; the place in this file where it was left (or its start)
                pixelsPerSecond = carried
                viewStart = if (st.pixelsPerSecond > 0) st.viewStart else 0.0
                clampView()
            } else if (st.pixelsPerSecond > 0) {
                pixelsPerSecond = st.pixelsPerSecond
                viewStart = st.viewStart
                clampView()
            } else {
                fitStart()
            }
            pendingView?.let { (start, pps) -> pendingView = null; pixelsPerSecond = pps; viewStart = start; clampView() }
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
            loadGainEdits()
            if (settings.layout.showPitch) pitch = withContext(Dispatchers.Default) { pitchOf(item, a) }
            computeSpectrogram(a)
            if (pitch == null) pitch = withContext(Dispatchers.Default) { pitchOf(item, a) }
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

    /** Sound editing mode: the recording is changed, the labels can't be (they move only with cut sound). */
    var soundMode by mutableStateOf(false)
    private var audioEditCommit = false

    fun commit(newDoc: LabelDoc) {
        if (soundMode && !audioEditCommit) {
            dragDoc = null
            app.message(mlabeler.app.ui.SoundTitles.locked())
            return
        }
        lastEdit = now()
        val h = history ?: return
        audioRedo.clear()
        h.push(newDoc)
        committed = h.current
        dragDoc = null
        docChanged()
    }

    // ---------- changes of the recording itself, undone with Ctrl+Z in order with label changes ----------

    /** A change of the audio file: its bytes before and after, whether labels were changed with it, and the label
     *  version right after it (the step is next to undo only while the labels are still at that version). */
    private class AudioStep(val path: String, val before: ByteArray, val after: ByteArray, val labels: Boolean, var version: Long)
    private val audioUndo = ArrayDeque<AudioStep>()
    private val audioRedo = ArrayDeque<AudioStep>()

    /** Writes [after] to [path] (the open recording) and, when given, [labels] as one undo step. */
    fun applyAudioEdit(path: String, before: ByteArray, after: ByteArray, labels: LabelDoc?) {
        workspace.fs.write(path, after)
        val v0 = history?.version
        if (labels != null) { audioEditCommit = true; try { commit(labels) } finally { audioEditCommit = false } }
        audioRedo.clear()
        audioUndo.addLast(AudioStep(path, before, after, history?.version != v0, history?.version ?: 0))
        // kept in memory: a few steps, at most ~400 MB
        while (audioUndo.size > 1 && (audioUndo.size > 20 || audioUndo.sumOf { it.before.size.toLong() + it.after.size } > 400L shl 20)) audioUndo.removeFirst()
        reloadAudio()
    }

    private fun audioStepToUndo(): AudioStep? = audioUndo.lastOrNull()?.takeIf { it.path == item?.audioPath && it.version == (history?.version ?: 0) }
    private fun audioStepToRedo(): AudioStep? = audioRedo.lastOrNull()?.takeIf { it.path == item?.audioPath && it.version == (history?.version ?: 0) }
    val canUndoAudio: Boolean get() { docVersion; return audioStepToUndo() != null }

    /** Undoes the last change of the recording (and the label change made with it). */
    fun undoAudio(): Boolean {
        val s = audioStepToUndo() ?: return false
        audioUndo.removeLast()
        workspace.fs.write(s.path, s.before)
        val h = history
        if (s.labels && h != null && h.undo()) { committed = h.current; fixSelection(); docChanged() }
        s.version = history?.version ?: 0
        audioRedo.addLast(s)
        reloadAudio()
        docVersion++
        return true
    }

    private fun redoAudio(): Boolean {
        val s = audioStepToRedo() ?: return false
        audioRedo.removeLast()
        workspace.fs.write(s.path, s.after)
        val h = history
        if (s.labels && h != null && h.redo()) { committed = h.current; fixSelection(); docChanged() }
        s.version = history?.version ?: 0
        audioUndo.addLast(s)
        reloadAudio()
        docVersion++
        return true
    }

    fun undo() {
        if (runCatching { undoAudio() }.getOrElse { app.message(it.message ?: it.toString(), error = true); true }) return
        if (mode == Mode.Oto) return oto.undo()
        val h = history ?: return
        val before = committed
        if (h.undo()) {
            committed = h.current
            showChanges(before, committed)
            fixSelection()
            docChanged()
        }
    }

    fun redo() {
        if (runCatching { redoAudio() }.getOrElse { app.message(it.message ?: it.toString(), error = true); true }) return
        if (mode == Mode.Oto) return oto.redo()
        val h = history ?: return
        val before = committed
        if (h.redo()) {
            committed = h.current
            showChanges(before, committed)
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

    /**
     * Pitch of the recording: analysed here, and where a .ds has its own f0 (from the dataset's pitch extractor),
     * that f0 instead.
     */
    private fun pitchOf(item: Item, a: Audio): mlabeler.core.dsp.Curve {
        val own = mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate)
        val path = item.labelPath
        if (item.labelFormat != mlabeler.core.format.LabelFormat.Ds || path == null) return own
        val ds = runCatching { mlabeler.core.format.DsFile.readF0(workspace.fs.read(path).decodeToString()) }.getOrDefault(emptyList())
        if (ds.isEmpty()) return own
        val v = own.values.copyOf()
        for (i in v.indices) {
            val t = i * own.hop
            for ((off, step, f0) in ds) {
                val j = kotlin.math.round((t - off) / step).toInt()
                if (j in f0.indices) { v[i] = f0[j]; break }
            }
        }
        return mlabeler.core.dsp.Curve(own.hop, v)
    }

    /** Drawn f0 at a time (Hz), for writing it into a .ds; null where nothing was drawn. */
    private fun drawnF0At(time: Double): Float? {
        val e = f0Edits ?: return null
        val hop = pitch?.hop ?: return null
        return e.getOrNull(kotlin.math.round(time / hop).toInt())?.takeIf { !it.isNaN() }
    }

    internal fun saveLabels(quiet: Boolean) {
        val it = item ?: return
        val d = committed ?: return
        val h = history ?: return
        try {
            val updated = workspace.writeLabels(it, d, duration, if (it.labelFormat == null) (workspace.state.defaultFormat) else null,
                f0 = if (f0Edits != null) ::drawnF0At else null)
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
        // one selection at a time: a selected phoneme replaces a selected part
        range = null
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

    /** Start and end of the selected interval, seconds. */
    fun selectedSpan(): Pair<Double, Double>? = (selection as? Selection.Interval)?.ref?.let { r -> tier(r.tier)?.let { it.startOf(r.index) to it.endOf(r.index) } }

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
        dragDoc = Edits.moveBound(base, ref, snapToZero(time).coerceIn(0.0, duration), duration, moveOptions(invertRipple, invertLinked))
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

    /** Dragging every boundary inside the selected part together: the labels and the part as they were at the start. */
    private var groupDrag: Triple<LabelDoc, Int, List<Int>>? = null
    private var groupRange: Pair<Double, Double>? = null

    /** True when the selected part has boundaries in it and they can be dragged together. */
    fun beginGroupDrag(): Boolean {
        val d = committed ?: return false
        val (k, inside) = rangeBounds() ?: return false
        groupDrag = Triple(d, k, inside)
        groupRange = range
        return true
    }

    /** Moves the boundaries by [seconds] from where they were at [beginGroupDrag], not past their neighbours. */
    fun groupDragBy(seconds: Double) {
        val (d, k, inside) = groupDrag ?: return
        val t = d.tiers[k] as? IntervalTier ?: return
        val gap = settings.edit.minIntervalMs / 1000.0
        val lo = t.bounds[inside.first() - 1] + gap
        val hi = t.bounds[inside.last() + 1] - gap
        val dt = seconds.coerceIn(lo - t.bounds[inside.first()], hi - t.bounds[inside.last()])
        val nb = t.bounds.toMutableList()
        for (b in inside) nb[b] = nb[b] + dt
        dragDoc = d.replace(k, t.copy(bounds = nb))
        range = groupRange?.let { (a, b) -> a + dt to b + dt }
        docVersion++
    }

    fun endGroupDrag() {
        val d = dragDoc
        groupDrag = null
        groupRange = null
        if (d != null) commit(d) else dragDoc = null
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

    /** Note [i] gets its pitch from the analysed f0 again (rounded to a semitone). */
    fun restoreNotePitch(tier: Int, i: Int) {
        val f0 = pitchCurve ?: return app.message(S.pitchNotReady())
        val t = noteTier(tier) ?: return
        updateDoc { it.replace(tier, mlabeler.core.edit.NoteEdits.pitchFromCurve(t, f0, true, setOf(i))) }
        selectNote(tier, i)
    }

    /**
     * Cuts note [i] at [time]; the right part is sung on the same syllable (a slur). Both parts get the pitch sung in
     * them, when the pitch is known.
     */
    fun splitNoteAt(tier: Int, time: Double) {
        val t = noteTier(tier) ?: return
        val (nt, k) = mlabeler.core.edit.NoteEdits.split(t, time) ?: return
        val right = nt.notes.getOrNull(k)
        val withSlur = if (right != null && k > 0) mlabeler.core.edit.NoteEdits.setSlur(nt, k, true) else nt
        val f0 = pitchCurve
        val sung = if (f0 != null && k > 0 && nt.notes[k - 1].pitch != null) mlabeler.core.edit.NoteEdits.pitchFromCurve(withSlur, f0, true, setOf(k - 1, k)) else withSlur
        updateDoc { it.replace(tier, sung) }
        selectNote(tier, k)
    }

    /** Joins note [i] with the next one. */
    fun mergeNotes(tier: Int, i: Int) {
        val t = noteTier(tier) ?: return
        if (i !in 0 until t.notes.size - 1) return
        updateDoc { it.replace(tier, mlabeler.core.edit.NoteEdits.mergeNext(t, i)) }
        selectNote(tier, i)
    }

    /**
     * The major key that fits the notes of this file best (by sung time per pitch class): 0 = C … 11 = B, or null
     * without notes. Its minor relative has the same notes.
     */
    fun detectedKey(): Int? {
        val t = doc?.tiers?.filterIsInstance<mlabeler.core.model.NoteTier>()?.firstOrNull() ?: return null
        val weight = DoubleArray(12)
        for (n in t.notes) n.pitch?.let { p -> weight[((kotlin.math.round(p).toInt() % 12) + 12) % 12] += n.end - n.start }
        if (weight.sum() <= 0) return null
        val major = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return (0 until 12).maxByOrNull { k -> major.sumOf { weight[(k + it) % 12] } }
    }

    /** [midi] moved to the nearest note of [key] (major; same notes as its minor relative). */
    fun snapToKey(midi: Double, key: Int): Double {
        val major = setOf(0, 2, 4, 5, 7, 9, 11)
        val base = kotlin.math.round(midi).toInt()
        val cands = (base - 2..base + 2).filter { ((it - key) % 12 + 12) % 12 in major }
        return (cands.minByOrNull { kotlin.math.abs(it - midi) } ?: base).toDouble()
    }

    /**
     * Renames labels of the tier named [tierName] in every file of the folder. The open file and files edited in this
     * session change in their undo history (saved as usual); the others are read, changed and written at once (the
     * previous file goes to .mlabeler/backup). Returns files and labels changed.
     */
    fun renameEverywhere(tierName: String, rename: (String) -> String): Pair<Int, Int> {
        var files = 0
        var labels = 0
        for (it in items) {
            val h = histories[it.id]
            val doc = if (it.id == item?.id) committed else h?.current ?: runCatching { workspace.readLabels(it, 0.0) }.getOrNull()
            if (doc == null || it.labelPath == null && h == null && it.id != item?.id) continue
            val k = doc.tierIndex(tierName).takeIf { k -> doc.tiers.getOrNull(k) is IntervalTier } ?: continue
            val t = doc.tiers[k] as IntervalTier
            val n = t.texts.count { x -> rename(x) != x }
            if (n == 0) continue
            val nd = Edits.setTexts(doc, (0 until t.size).map { i -> IntervalRef(k, i) }, rename)
            when {
                it.id == item?.id -> commit(nd)
                // a file edited earlier in this session: the change goes into its history and is written right away
                h != null -> { h.push(nd); runCatching { workspace.writeLabels(it, nd, t.end); h.markSaved() } }
                else -> runCatching { workspace.writeLabels(it, nd, t.end) }.onFailure { e -> app.message(e.message ?: e.toString(), error = true); return files to labels }
            }
            files++
            labels += n
        }
        labelIndex = null
        docVersion++
        return files to labels
    }

    /** What [renameEverywhere] would change: file, old → new (read only). */
    fun previewEverywhere(tierName: String, rename: (String) -> String): List<Triple<String, String, String>> = items.flatMap { it ->
        val doc = if (it.id == item?.id) committed else histories[it.id]?.current ?: runCatching { workspace.readLabels(it, 0.0) }.getOrNull()
        val t = doc?.let { d -> d.tiers.getOrNull(d.tierIndex(tierName)) as? IntervalTier } ?: return@flatMap emptyList()
        t.texts.mapNotNull { x -> rename(x).takeIf { y -> y != x }?.let { y -> Triple(it.name, x, y) } }
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

    /** With [EditSettings.snapToZero]: the nearest place within 3 ms where the waveform crosses zero. */
    fun snapToZero(time: Double): Double {
        if (!settings.edit.snapToZero) return time
        val a = audio ?: return time
        val x = a.samples
        val c = (time * a.sampleRate).toInt()
        val reach = (a.sampleRate * 0.003).toInt()
        for (d in 0..reach) for (i in intArrayOf(c - d, c + d)) {
            if (i <= 0 || i >= x.size) continue
            if ((x[i - 1] <= 0f && x[i] >= 0f) || (x[i - 1] >= 0f && x[i] <= 0f)) return i.toDouble() / a.sampleRate
        }
        return time
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
        val r = Edits.split(d, k, snapToZero(time), queued ?: "", settings.edit.minIntervalMs / 1000.0, newOnLeft = newLeft) ?: return
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

    /** [updateDoc] for changes made for the user (autolabel, a plugin, refinement): what changed glows a moment. */
    fun updateDocShowingChanges(transform: (LabelDoc) -> LabelDoc) {
        val before = committed
        updateDoc(transform)
        showChanges(before, committed)
    }

    /** Spans of each interval tier (by index) that changed a moment ago; they glow while [changeGlow] fades. */
    var changedSpans by mutableStateOf<Map<Int, List<Pair<Double, Double>>>>(emptyMap())
        private set
    /** 1 when something has just changed, down to 0 over a second or so. */
    var changeGlow by mutableStateOf(0f)
        private set
    private var glowJob: Job? = null

    /** Lets the intervals that differ between [before] and [after] glow for a moment (Settings → Interface → Animations). */
    fun showChanges(before: LabelDoc?, after: LabelDoc?) {
        if (!settings.flashChanges || settings.animations == "off" || before == null || after == null || before === after) return
        val spans = changedSpansOf(before, after)
        if (spans.isEmpty()) return
        glowJob?.cancel()
        changedSpans = spans
        val ms = if (settings.animations == "reduced") 700 else 1400
        glowJob = scope.launch {
            val t0 = now()
            try {
                while (true) {
                    val f = ((now() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
                    // stays bright a little, then fades out
                    changeGlow = (1.0 - ((f - 0.25) / 0.75).coerceIn(0.0, 1.0)).let { it * it }.toFloat()
                    if (f >= 1.0) break
                    delay(16)
                }
            } finally {
                changeGlow = 0f
                changedSpans = emptyMap()
            }
        }
    }

    /** The boundary under the mouse pointer when it is to be highlighted (Settings → Interface → Animations). */
    var hoverBound by mutableStateOf<mlabeler.core.edit.BoundRef?>(null)

    // ---------- view ----------

    fun clampView() {
        // never wider than the whole recording: a short file after a long one (or a stored view) shows all of itself
        val d = duration
        if (d > 0 && viewWidthPx > 1f && pixelsPerSecond < viewWidthPx / d) { pixelsPerSecond = viewWidthPx / d; viewStart = 0.0 }
        val vis = visibleDuration
        val maxStart = max(0.0, duration - vis * 0.9)
        viewStart = viewStart.coerceIn(min(0.0, -vis * 0.05), maxStart)
    }

    fun fitAll() {
        val d = duration
        if (d <= 0 || viewWidthPx <= 1f) {
            needsFit = true
            fitLimit = Double.MAX_VALUE
            return
        }
        pixelsPerSecond = viewWidthPx / d
        viewStart = 0.0
    }

    /** First look at a file: all of it when short, otherwise its beginning ([FIRST_VIEW_SECONDS]); zooming out shows the rest. */
    private fun fitStart() {
        val d = duration
        if (d <= 0 || viewWidthPx <= 1f) {
            needsFit = true
            fitLimit = FIRST_VIEW_SECONDS
            return
        }
        pixelsPerSecond = viewWidthPx / min(d, FIRST_VIEW_SECONDS)
        viewStart = 0.0
    }
    private var fitLimit = Double.MAX_VALUE

    companion object {
        /** For each interval tier of [after]: the spans of intervals that are not in the same tier of [before]. */
        fun changedSpansOf(before: LabelDoc, after: LabelDoc): Map<Int, List<Pair<Double, Double>>> {
            val out = mutableMapOf<Int, List<Pair<Double, Double>>>()
            for ((k, t) in after.tiers.withIndex()) {
                if (t !is IntervalTier) continue
                val old = (before.tiers.firstOrNull { it is IntervalTier && it.name == t.name } ?: before.tiers.getOrNull(k)) as? IntervalTier
                fun key(a: Double, b: Double, s: String) = Triple(kotlin.math.round(a * 10000), kotlin.math.round(b * 10000), s)
                val known = old?.let { o -> (0 until o.size).map { i -> key(o.startOf(i), o.endOf(i), o.texts[i]) }.toHashSet() } ?: hashSetOf()
                val spans = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until t.size) {
                    if (key(t.startOf(i), t.endOf(i), t.texts[i]) in known) continue
                    val a = t.startOf(i)
                    val b = t.endOf(i)
                    // neighbours that both changed glow as one span
                    if (spans.isNotEmpty() && kotlin.math.abs(spans.last().second - a) < 1e-6) spans[spans.lastIndex] = spans.last().first to b
                    else spans += a to b
                }
                if (spans.isNotEmpty()) out[k] = spans
            }
            return out
        }

        /** How much of a long recording the first look shows, seconds. */
        const val FIRST_VIEW_SECONDS = 15.0
    }

    fun zoom(factor: Double, anchorTime: Double = viewStart + visibleDuration / 2) {
        val minPps = if (duration > 0) viewWidthPx / duration else 1.0
        val newPps = (pixelsPerSecond * factor).coerceIn(minPps, 20000.0)
        val anchorX = (anchorTime - viewStart) * pixelsPerSecond
        viewGlide?.cancel()
        pixelsPerSecond = newPps
        viewStart = anchorTime - anchorX / newPps
        clampView()
    }

    fun scrollBy(px: Double) {
        viewGlide?.cancel()
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
        val start = viewGlideTarget?.first ?: viewStart
        val target = when {
            from < start + margin -> from - margin
            to > start + vis - margin -> to - vis + margin
            else -> return
        }
        glideTo(target, pixelsPerSecond)
    }

    fun zoomTo(from: Double, to: Double) {
        val len = max(to - from, 0.02)
        glideTo(from - len * 0.1, (viewWidthPx / (len * 1.2)).coerceIn(1.0, 20000.0))
    }

    private var viewGlide: Job? = null
    /** Where the view is gliding to (start, pixels per second), while it is. */
    private var viewGlideTarget: Pair<Double, Double>? = null

    /**
     * Puts the view at [start] with [pps] (kept within the recording): it glides there when the interface may
     * move and smooth jumps are on (Settings → Interface → Animations), otherwise it is there at once. Scrolling
     * or zooming by hand stops a glide.
     */
    private fun glideTo(start: Double, pps: Double) {
        viewGlide?.cancel()
        viewGlideTarget = null
        val s0 = viewStart
        val p0 = pixelsPerSecond
        // the target as the view would have it
        viewStart = start; pixelsPerSecond = pps
        clampView()
        val s1 = viewStart
        val p1 = pixelsPerSecond
        val ms = if (!settings.smoothView) 0 else when (settings.animations) { "off" -> 0; "reduced" -> 90; else -> 180 }
        if (ms == 0 || viewWidthPx <= 1f || (kotlin.math.abs(s1 - s0) * p1 < 1.0 && kotlin.math.abs(p1 - p0) < 1e-6)) return
        viewStart = s0; pixelsPerSecond = p0
        viewGlideTarget = s1 to p1
        viewGlide = scope.launch {
            val t0 = now()
            try {
                while (true) {
                    val f = ((now() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
                    val e = 1 - (1 - f) * (1 - f) * (1 - f)
                    // the zoom changes evenly on a log scale; the middle of the view travels in a straight line
                    val pps = kotlin.math.exp(kotlin.math.ln(p0) + (kotlin.math.ln(p1) - kotlin.math.ln(p0)) * e)
                    val mid0 = s0 + viewWidthPx / p0 / 2
                    val mid1 = s1 + viewWidthPx / p1 / 2
                    pixelsPerSecond = pps
                    viewStart = mid0 + (mid1 - mid0) * e - viewWidthPx / pps / 2
                    if (f >= 1.0) break
                    delay(16)
                }
                viewStart = s1; pixelsPerSecond = p1
            } finally {
                viewGlideTarget = null
            }
        }
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
                val p = runCatching { player.position() }.getOrDefault(-1)
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

private val reviewNothing = L("Nothing to look at in this file", "В этом файле нечего проверять")
private val reviewEnd = L("That was the last of {0} places in this file; PgDn opens the next file", "Это было последнее из {0} мест в этом файле; PgDn — следующий файл")
private val reviewPos = L("Place {0} of {1}", "Место {0} из {1}")

private val renamedFileT = mlabeler.app.i18n.L("Renamed: {0} → {1}", "Переименовано: {0} → {1}")
private val trashedManyT = mlabeler.app.i18n.L("Files moved to the trash: {0} ({1})", "Перемещено в корзину файлов: {0} ({1})")
private val mergedT = mlabeler.app.i18n.L("Joined {0} recordings into {1}", "Склеено записей: {0}, в {1}")
private val mergeExistsT = mlabeler.app.i18n.L("{0} already exists", "{0} уже существует")
private val badMergeNameT = mlabeler.app.i18n.L("the name is empty or has characters a file name can't have", "имя пустое или в нём есть недопустимые символы")
private val trashedT = mlabeler.app.i18n.L("{0} was moved to {1}", "{0} перемещён в {1}")
private val fileOpFailedT = mlabeler.app.i18n.L("Couldn't do it: {0}", "Не получилось: {0}")
