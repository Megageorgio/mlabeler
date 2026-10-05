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
    val playOnDrag: Boolean = true,
    /** Playback speed, 0.25..1, pitch kept. */
    val speed: Float = 1f,
    /** Save every N seconds when there are changes; 0 = off. */
    val autosaveSeconds: Int = 0,
)

@Serializable
data class ToolkitSettings(
    val url: String = "http://127.0.0.1:8765",
    val token: String = "",
    val lastModel: String = "",
    val lastLanguage: String = "",
)

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
