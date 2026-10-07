package mlabeler.app.state

import kotlinx.serialization.Serializable
import mlabeler.app.Platform
import mlabeler.core.check.CheckSettings
import mlabeler.core.format.LabelFormat
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace

@Serializable
data class LayoutSettings(
    val showFiles: Boolean = true,
    val showInspector: Boolean = true,
    val filesWidth: Float = 260f,
    val inspectorWidth: Float = 300f,
    val showWaveform: Boolean = true,
    val showSpectrogram: Boolean = true,
    /** Share of the audio area given to the waveform when both are shown. */
    val waveShare: Float = 0.38f,
    /** Height of the audio area (waveform + spectrogram) in dp; 0 = fill. */
    val audioHeight: Float = 0f,
    val tierHeight: Float = 40f,
    /** Size of the label text on the lanes, sp (lanes grow to fit). */
    val labelFontSize: Float = 13f,
    /** Phoneme names also drawn over the waveform and the spectrogram, at [namesX], [namesY] of each phoneme (0..1). */
    val namesOnAudio: Boolean = false,
    val namesX: Float = 0.5f,
    val namesY: Float = 0.5f,
    val showPitch: Boolean = false,
    val showPower: Boolean = false,
    /** Pitch drawn over the spectrogram instead of its own lane. */
    val pitchOverSpectrogram: Boolean = true,
    val pitchShare: Float = 0.3f,
    val powerShare: Float = 0.18f,
    /** Waveform over the spectrogram and labels over both, instead of separate lanes. */
    val overlay: Boolean = false,
    /** Label tiers above the audio instead of below. */
    val tiersOnTop: Boolean = false,
    /** Spectrogram lane above the waveform lane. */
    val spectrogramFirst: Boolean = false,
    /** Which side the files/entries panel and the details panel are on: "left" or "right". */
    val filesSide: String = "left",
    val inspectorSide: String = "right",
    /** Entries as a panel of their own (otherwise a tab of the files panel), its side and whether it's shown. */
    val entriesSeparate: Boolean = false,
    val entriesSide: String = "left",
    val showEntries: Boolean = true,
    /** Lanes from top to bottom ("wave", "spec", "pitch", "power", "labels"); empty = the usual order. */
    val laneOrder: List<String> = emptyList(),
    /** Share of the audio area per lane (dragged at the lines between lanes); missing = the usual share. */
    val laneWeights: Map<String, Float> = emptyMap(),
    /** Vertical zoom of the waveform (Alt+wheel over it). */
    val waveGain: Float = 1f,
    /** oto: the selected alias in large type over the picture (renamed in place). */
    val otoHeader: Boolean = true,
    /** Sections of the details panel: their order and the folded ones. */
    val inspectorOrder: List<String> = emptyList(),
    val inspectorFolded: Set<String> = emptySet(),
    /** A whole side of panels folded away (the buttons at the ends of the top bar). */
    val leftCollapsed: Boolean = false,
    val rightCollapsed: Boolean = false,
    /** How much the spectrogram is darkened in the overlaid view so labels and the waveform stay readable (0..0.8). */
    val overlayDim: Float = 0.35f,
    /** Overlaid view: fill the waveform instead of drawing only its outline. */
    val overlayWaveFill: Boolean = false,
    /** Opacity of the waveform over the spectrogram: 1 = as in its own lane. */
    val overlayWaveFillAlpha: Float = 0.85f,
)

@Serializable
data class ViewSettings(
    val palette: String = "",
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val maxFreq: Float = 8000f,
    /** Analysis window, ms (longer = finer frequencies, blurrier in time). */
    val windowMs: Float = 25f,
    /** Step between columns, ms; 0 = by file length. */
    val hopMs: Float = 0f,
    val bands: Int = 192,
    val minDb: Float = -100f,
    val maxDb: Float = -10f,
)

@Serializable
data class EditSettings(
    val ripple: Boolean = false,
    val linked: Boolean = true,
    val loop: Boolean = false,
    val nudgeMs: Float = 5f,
    /** The view during playback: "off", "page" (turns the page at the edge) or "keep" (the playhead stays at [followAt]). */
    val follow: String = "page",
    val followAt: Float = 0.5f,
    /** Playback volume, 0..1. */
    val volume: Float = 1f,
    val minIntervalMs: Float = 1f,
    val saveOnSwitch: Boolean = true,
    val newFormat: LabelFormat = LabelFormat.Lab,
    val otoLockedDrag: Boolean = true,
    /** Play a short piece around a boundary while it is dragged. */
    val playOnDrag: Boolean = false,
    /** Which phoneme a boundary "belongs" to: "end" = the one that ends at it, "start" = the one that starts at it.
     *  Deleting a selected boundary removes that phoneme; Space plays it. */
    val boundaryOwner: String = "end",
    /** Space while playing starts again instead of stopping. */
    val spaceRestarts: Boolean = false,
    /** Mouse tool: "cursor" (click selects, drag moves boundaries), "cut" (click adds a boundary),
     *  "pan" (dragging scrolls), "play" (click plays the phoneme, a selection plays when let go). */
    val tool: String = "cursor",
    /** After adding a boundary with the mouse: type the name of the new part right away. */
    val cutAskName: Boolean = true,
    /** After adding a boundary with the mouse: play the part before it. */
    val cutPlay: Boolean = true,
    /** Scissors cut on label lanes too (otherwise only over the audio; clicks on labels select and rename). */
    val cutOnLanes: Boolean = false,
    /** After moving a boundary, select the phoneme it belongs to (Space then plays it). */
    val selectAfterDrag: Boolean = true,
    /** A click on the waveform or spectrogram removes the phoneme selection (Space then plays from the cursor). */
    val audioClickDeselects: Boolean = true,
    /** Playback speed, 0.25..1, pitch kept. */
    val speed: Float = 1f,
    /** Save every N seconds when there are changes; 0 = off. */
    val autosaveSeconds: Int = 0,
    /** Mouse tools 1–4 (cursor, scissors, hand, play); off = the cursor always. */
    val tools: Boolean = true,
) {
    val activeTool: String get() = if (tools) tool else "cursor"
}

/** What mouse gestures do, on label lanes and on the waveform/spectrogram. Values are [MouseActions] ids. */
@Serializable
data class MouseSettings(
    val tierDouble: String = MouseActions.RENAME,
    val tierRight: String = MouseActions.PLAY,
    val tierMiddle: String = MouseActions.PLAY,
    val tierCtrl: String = MouseActions.SPLIT,
    val tierAlt: String = MouseActions.NONE,
    val audioDouble: String = MouseActions.PLAY,
    val audioRight: String = MouseActions.PLAY,
    val audioMiddle: String = MouseActions.PLAY,
    val audioCtrl: String = MouseActions.SPLIT,
    val audioAlt: String = MouseActions.NONE,
    /** The mouse wheel: "scroll" through time, or "phonemes" (selects the next/previous phoneme; Shift+wheel scrolls). */
    val wheel: String = "scroll",
)

object MouseActions {
    const val NONE = "none"
    const val SELECT = "select"
    const val PLAY = "play"
    const val PLAY_FROM = "play-from"
    const val RENAME = "rename"
    const val SPLIT = "split"
    const val SPLIT_NAME = "split-name"
    const val DELETE = "delete"
    const val DESELECT = "deselect"
    val all = listOf(NONE, SELECT, DESELECT, PLAY, PLAY_FROM, RENAME, SPLIT, SPLIT_NAME, DELETE)
}

@Serializable
data class ToolkitSettings(
    val url: String = "http://127.0.0.1:8765",
    val token: String = "",
    val lastModel: String = "",
    val lastLanguage: String = "",
    /** Model for recognising phonemes without lyrics. */
    val lastSegmentModel: String = "",
    /** Aligning without text: recognise the words with Whisper first (a large download on first use). */
    val whisper: Boolean = false,
    /** Phoneme recognition (WFL-ASR): see [mlabeler.app.toolkit.SegmentOptions]. */
    val wfl: WflSettings = WflSettings(),
    /** Start the toolkit on this computer when it's needed and not running. */
    val autoStart: Boolean = true,
    /** `mvt` command or the folder it's in; empty = look in the usual places. */
    val mvtPath: String = "",
    /** What `uv tool install` installs: a git/zip URL or a local folder. */
    val installSource: String = ToolkitSettings.DEFAULT_SOURCE,
    /** Let phones and other computers in the local network use the toolkit started here. */
    val shareOnNetwork: Boolean = false,
    /** Before starting the toolkit, install a newer version from the install source if there is one. */
    val autoUpdate: Boolean = true,
    /** Revision (commit) of the source the installed toolkit was made from. */
    val installedRevision: String = "",
    /** When the source was last checked for a newer version (ms since epoch). */
    val lastUpdateCheck: Long = 0,
) {
    companion object {
        const val DEFAULT_SOURCE = "https://github.com/Megageorgio/mVocalToolkit/archive/refs/heads/main.zip"
    }
}

@Serializable
data class ToolbarSettings(
    /** Button groups shown, in order. */
    val groups: List<String> = ToolbarGroups.simple,
    /** Text under the buttons. */
    val labels: Boolean = true,
    val big: Boolean = true,
    /** Order of all groups, shown or not (so turning one off and on keeps its place). */
    val order: List<String> = emptyList(),
    /** A button with the interface scale in percent (null = on phones and tablets only). */
    val scaleButton: Boolean? = null,
) {
    fun fullOrder(): List<String> = (order + groups + ToolbarGroups.all).distinct().filter { it in ToolbarGroups.all }

    /** Shows or hides [g] keeping every group where it was. */
    fun toggled(g: String, on: Boolean): ToolbarSettings {
        val o = fullOrder()
        val vis = groups.toSet().let { if (on) it + g else it - g }
        return copy(order = o, groups = o.filter { it in vis })
    }

    fun moved(g: String, delta: Int): ToolbarSettings {
        val o = fullOrder().toMutableList()
        val i = o.indexOf(g)
        val j = i + delta
        if (i < 0 || j !in o.indices) return this
        o[i] = o[j]; o[j] = g
        return copy(order = o, groups = o.filter { it in groups })
    }
}

object ToolbarGroups {
    const val FILES = "files"
    const val HISTORY = "history"
    const val PLAY = "play"
    const val EDIT = "edit"
    const val MODES = "modes"
    const val VIEW = "view"
    const val ZOOM = "zoom"
    const val AUTO = "auto"
    const val MARKS = "marks"
    const val EXTRAS = "extras"
    val all = listOf(FILES, HISTORY, PLAY, EDIT, MODES, AUTO, MARKS, VIEW, ZOOM, EXTRAS)
    val simple = listOf(FILES, HISTORY, PLAY, EDIT, MODES, AUTO)
}

@Serializable
data class AppSettings(
    val language: String = "",
    val theme: String = "modern-dark",
    val scale: Float = 1f,
    val recent: List<String> = emptyList(),
    val layout: LayoutSettings = LayoutSettings(),
    val view: ViewSettings = ViewSettings(),
    val edit: EditSettings = EditSettings(),
    val checks: CheckSettings = CheckSettings(),
    val toolkit: ToolkitSettings = ToolkitSettings(),
    /** Key bindings changed by the user: command id to chords ("ctrl+shift+<key code>"). */
    val keymap: Map<String, List<String>> = emptyMap(),
    /** Last parameters per plugin, as JSON objects. */
    val pluginParams: Map<String, String> = emptyMap(),
    /** Layouts saved by the user, by name. */
    val layoutPresets: Map<String, LayoutSettings> = emptyMap(),
    /** The short guide was shown once. */
    val seenHelp: Boolean = false,
    /** Phones: "landscape", "portrait" or "auto". */
    val orientation: String = "landscape",
    /** Phones: hide the status and navigation bars. */
    val fullscreen: Boolean = true,
    /** Phones: keep content away from the camera cutout. */
    val avoidCutout: Boolean = false,
    /** The user picked the interface size (else phones get a smaller default). */
    val scaleChosen: Boolean = false,
    /** Plugins on the quick slots (Ctrl+1 … Ctrl+4). */
    val pluginSlots: List<String> = emptyList(),
    /** File / Edit / View … menus at the top (computers). */
    val menuBar: Boolean = !Platform.isMobile,
    val statusBar: Boolean = true,
    val toolbar: ToolbarSettings = ToolbarSettings(),
    /** The first-start choice (simple or everything) was made. */
    val setupDone: Boolean = false,
    /** List mp3, flac, ogg … too (needs ffmpeg on computers); WAV only by default. */
    val otherAudio: Boolean = false,
    /** Id of the chosen work environment (built-in id or "user:<name>"). */
    val environment: String = "basic",
    val mouse: MouseSettings = MouseSettings(),
    /** Interface font from the system; empty = the theme's. */
    val font: String = "",
    val clean: CleanSettings = CleanSettings(),
) {
    companion object {
        private val path get() = Paths.join(Platform.dataDir(), "settings.json")

        fun load(): AppSettings = try {
            if (PlatformFs.exists(path)) Workspace.json.decodeFromString(serializer(), PlatformFs.read(path).decodeToString()) else AppSettings()
        } catch (e: Exception) {
            AppSettings()
        }

        fun save(s: AppSettings) {
            try {
                PlatformFs.write(path, Workspace.json.encodeToString(serializer(), s).encodeToByteArray())
            } catch (_: Exception) {
            }
        }
    }
}

/** Layouts that come with the app. */
object LayoutPresets {
    val builtIn: List<Pair<mlabeler.app.i18n.L, LayoutSettings>> = listOf(
        mlabeler.app.i18n.L("Simple", "Простой") to LayoutSettings(showSpectrogram = false, showPitch = false, showPower = false, overlay = false),
        mlabeler.app.i18n.L("Waveform and spectrogram", "Волна и спектрограмма") to LayoutSettings(),
        mlabeler.app.i18n.L("One picture", "Одна картинка") to LayoutSettings(overlay = true, showPitch = true),
        mlabeler.app.i18n.L("Notes and pitch", "Ноты и высота") to LayoutSettings(showWaveform = false, showPitch = true, pitchOverSpectrogram = true, showPower = true),
    )

    /** Applies the view parts of [p], keeping panel sizes. */
    fun apply(current: LayoutSettings, p: LayoutSettings) = p.copy(
        showFiles = current.showFiles, showInspector = current.showInspector,
        filesWidth = current.filesWidth, inspectorWidth = current.inspectorWidth,
    )
}

/** Panels assigned to a side ("left"/"right"), shown or not. */
fun LayoutSettings.panelsOn(side: String): List<String> = buildList {
    if (filesSide == side) add("files")
    if (entriesSeparate && entriesSide == side) add("entries")
    if (inspectorSide == side) add("details")
}

private fun LayoutSettings.shown(p: String) = when (p) {
    "files" -> showFiles
    "entries" -> showEntries
    else -> showInspector
}

private fun LayoutSettings.withShown(p: String, on: Boolean) = when (p) {
    "files" -> copy(showFiles = on)
    "entries" -> copy(showEntries = on)
    else -> copy(showInspector = on)
}

private fun LayoutSettings.sideOf(p: String) = when (p) {
    "files" -> filesSide
    "entries" -> entriesSide
    else -> inspectorSide
}

fun LayoutSettings.sideVisible(side: String): Boolean =
    !(if (side == "left") leftCollapsed else rightCollapsed) && panelsOn(side).any { shown(it) }

/** Folds a side away, or brings it back (showing its panels if all were hidden). */
fun LayoutSettings.toggleSide(side: String): LayoutSettings {
    if (sideVisible(side)) return if (side == "left") copy(leftCollapsed = true) else copy(rightCollapsed = true)
    var l = if (side == "left") copy(leftCollapsed = false) else copy(rightCollapsed = false)
    if (panelsOn(side).none { l.shown(it) }) for (p in panelsOn(side)) l = l.withShown(p, true)
    return l
}

/** Shows or hides one panel; showing it also unfolds its side. */
fun LayoutSettings.togglePanel(p: String): LayoutSettings {
    val on = !shown(p)
    var l = withShown(p, on)
    if (on) {
        val side = sideOf(p)
        l = if (side == "left") l.copy(leftCollapsed = false) else l.copy(rightCollapsed = false)
    }
    return l
}

/** Options of phoneme recognition without text (WFL-ASR); negative confidence = the model's own value. */
@Serializable
data class WflSettings(
    val confidence: Float = -1f,
    val decoder: String = "viterbi",
    val viterbiBias: Float = 5f,
    val silenceThreshold: Float = 0.005f,
    val minSilence: Float = 0.5f,
)
