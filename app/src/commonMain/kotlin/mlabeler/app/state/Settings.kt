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
    /** How much the spectrogram is darkened in the overlaid view so labels and the waveform stay readable (0..0.8). */
    val overlayDim: Float = 0.35f,
    /** Overlaid view: fill the waveform instead of drawing only its outline. */
    val overlayWaveFill: Boolean = false,
    /** Opacity of that fill: 1 = solid. */
    val overlayWaveFillAlpha: Float = 0.55f,
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
    /** Mouse tool: "cursor" (click selects, drag moves boundaries) or "cut" (click adds a boundary). */
    val tool: String = "cursor",
    /** After adding a boundary with the mouse: type the name of the new part right away. */
    val cutAskName: Boolean = true,
    /** After adding a boundary with the mouse: play the part before it. */
    val cutPlay: Boolean = true,
    /** Playback speed, 0.25..1, pitch kept. */
    val speed: Float = 1f,
    /** Save every N seconds when there are changes; 0 = off. */
    val autosaveSeconds: Int = 0,
)

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
    val all = listOf(NONE, SELECT, PLAY, PLAY_FROM, RENAME, SPLIT, SPLIT_NAME, DELETE)
}

@Serializable
data class ToolkitSettings(
    val url: String = "http://127.0.0.1:8765",
    val token: String = "",
    val lastModel: String = "",
    val lastLanguage: String = "",
    /** Model for recognising phonemes without lyrics. */
    val lastSegmentModel: String = "",
    /** Start the toolkit on this computer when it's needed and not running. */
    val autoStart: Boolean = true,
    /** `mvt` command or the folder it's in; empty = look in the usual places. */
    val mvtPath: String = "",
    /** What `uv tool install` installs: a git/zip URL or a local folder. */
    val installSource: String = ToolkitSettings.DEFAULT_SOURCE,
    /** Let phones and other computers in the local network use the toolkit started here. */
    val shareOnNetwork: Boolean = false,
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
)

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
