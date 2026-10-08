package mlabeler.app.state

import kotlinx.serialization.Serializable
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace

/**
 * A work environment: which panels, lanes and toolbar groups are shown. Built-in ones plus the user's own,
 * kept as JSON files in <data>/environments so they can be shared.
 */
@Serializable
data class Environment(
    val name: String,
    val layout: LayoutSettings,
    val toolbar: ToolbarSettings,
    val menuBar: Boolean = true,
    val statusBar: Boolean = true,
    /** Mouse tools (scissors, hand, play) available. */
    val tools: Boolean = true,
)

data class EnvironmentEntry(val id: String, val title: String, val description: String, val env: Environment, val builtIn: Boolean)

object Environments {
    private class BuiltIn(val id: String, val title: L, val description: L, val make: () -> Environment)

    private val builtIn = listOf(
        BuiltIn(
            "basic", L("Basic", "Базовая"),
            L("Big named buttons for the main things.", "Крупные подписанные кнопки для основного."),
        ) {
            Environment("basic", LayoutSettings(showInspector = false), ToolbarSettings(ToolbarGroups.simple, labels = true, big = true), tools = false)
        },
        BuiltIn(
            "labeling", L("Labeling", "Разметка"),
            L("Editing, marks and zoom; details on the right.", "Правка, отметки и масштаб; подробности справа."),
        ) {
            Environment(
                "labeling", LayoutSettings(showInspector = true),
                ToolbarSettings(listOf(ToolbarGroups.FILES, ToolbarGroups.HISTORY, ToolbarGroups.PLAY, ToolbarGroups.EDIT, ToolbarGroups.MODES,
                    ToolbarGroups.AUTO, ToolbarGroups.MARKS, ToolbarGroups.ZOOM), labels = true, big = false),
            )
        },
        BuiltIn(
            "singing", L("Singing data", "Певческий датасет"),
            L("Pitch as its own lane, loudness and notes.", "Высота тона своей полосой, громкость и ноты."),
        ) {
            Environment(
                "singing", LayoutSettings(showInspector = true, showPitch = true, pitchOverSpectrogram = false, showPower = true, waveShare = 0.3f),
                ToolbarSettings(listOf(ToolbarGroups.FILES, ToolbarGroups.HISTORY, ToolbarGroups.PLAY, ToolbarGroups.EDIT, ToolbarGroups.MODES,
                    ToolbarGroups.AUTO, ToolbarGroups.MARKS, ToolbarGroups.ZOOM), labels = true, big = false),
            )
        },
        BuiltIn(
            "oto", L("Voicebank (oto)", "Войсбанк (oto)"),
            L("Oto entries in their own panel.", "Записи oto отдельной панелью."),
        ) {
            Environment(
                "oto", LayoutSettings(showInspector = true, entriesSeparate = true, entriesSide = "left", showPitch = false, waveShare = 0.45f),
                ToolbarSettings(listOf(ToolbarGroups.FILES, ToolbarGroups.HISTORY, ToolbarGroups.PLAY, ToolbarGroups.MODES, ToolbarGroups.AUTO,
                    ToolbarGroups.ZOOM), labels = true, big = false),
            )
        },
        BuiltIn(
            "compact", L("Small screen", "Маленький экран"),
            L("Panels hidden, big buttons: for a phone or a narrow window.", "Панели скрыты, кнопки крупные: для телефона или узкого окна."),
        ) {
            Environment(
                "compact", LayoutSettings(showFiles = false, showInspector = false, showSpectrogram = false),
                ToolbarSettings(ToolbarGroups.simple, labels = false, big = true), statusBar = false, tools = false,
            )
        },
        BuiltIn(
            "studio", L("Studio", "Студия"),
            L("Labels on top, every lane of the sound, the notepad at hand; few buttons, more room.",
                "Разметка сверху, все полосы звука, блокнот под рукой; мало кнопок, больше места."),
        ) {
            Environment(
                "studio",
                LayoutSettings(
                    tierHeight = 32f, labelFontSize = 17f, showPitch = true, pitchOverSpectrogram = false, showPower = true, tiersOnTop = true,
                    entriesSide = "right", laneOrder = listOf("labels", "wave", "spec", "power", "pitch"),
                    laneWeights = mapOf("wave" to 0.53f, "spec" to 0.35f, "power" to 0.12f), waveGain = 2.44f,
                    showNotepad = true, notepadX = 40f, notepadY = 368f, notepadW = 257f, notepadH = 264f,
                    inspectorOrder = listOf("file", "selection", "problems", "queue", "notes", "tiers", "compare"),
                    overlayDim = 0.34f, overlayWaveFill = true, overlayWaveFillAlpha = 1f,
                ),
                ToolbarSettings(
                    listOf(ToolbarGroups.HISTORY, ToolbarGroups.AUTO, ToolbarGroups.MARKS, ToolbarGroups.VIEW, ToolbarGroups.EXTRAS, ToolbarGroups.FILES),
                    order = listOf(ToolbarGroups.PLAY, ToolbarGroups.EDIT, ToolbarGroups.HISTORY, ToolbarGroups.MODES, ToolbarGroups.AUTO,
                        ToolbarGroups.MARKS, ToolbarGroups.VIEW, ToolbarGroups.EXTRAS, ToolbarGroups.FILES, ToolbarGroups.ZOOM),
                    scaleButton = false,
                ),
                statusBar = false,
            )
        },
    )

    const val USER = "user:"

    fun dir(): String = Paths.join(Platform.dataDir(), "environments")

    private fun fileName(name: String) = name.map { if (it.isLetterOrDigit() || it in " -_") it else '_' }.joinToString("").trim().ifEmpty { "environment" } + ".json"

    fun user(): List<EnvironmentEntry> = runCatching {
        PlatformFs.list(dir()).filter { Paths.ext(it) == "json" }.mapNotNull { p ->
            runCatching { Workspace.json.decodeFromString(Environment.serializer(), PlatformFs.read(p).decodeToString()) }.getOrNull()
                ?.let { EnvironmentEntry(USER + it.name, it.name, "", it, builtIn = false) }
        }.sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    fun builtIns(): List<EnvironmentEntry> = builtIn.map { EnvironmentEntry(it.id, it.title(), it.description(), it.make(), builtIn = true) }

    fun all(): List<EnvironmentEntry> = builtIns() + user()

    fun byId(id: String): EnvironmentEntry? = all().firstOrNull { it.id == id }

    /** Applies [e], keeping the panel sizes the user dragged. */
    fun apply(s: AppSettings, e: EnvironmentEntry): AppSettings = s.copy(
        environment = e.id,
        setupDone = true,
        menuBar = e.env.menuBar && !Platform.isMobile,
        statusBar = e.env.statusBar,
        toolbar = e.env.toolbar,
        edit = s.edit.copy(tools = e.env.tools),
        layout = e.env.layout.copy(
            filesWidth = s.layout.filesWidth, inspectorWidth = s.layout.inspectorWidth,
            tierHeight = s.layout.tierHeight, waveShare = s.layout.waveShare,
        ),
    )

    /** True when the interface still looks exactly like [e] (panel sizes aside). */
    fun matches(s: AppSettings, e: EnvironmentEntry): Boolean {
        val norm = { l: LayoutSettings -> l.copy(filesWidth = 0f, inspectorWidth = 0f, tierHeight = 0f, waveShare = 0f, leftCollapsed = false, rightCollapsed = false) }
        return norm(s.layout) == norm(e.env.layout) && s.toolbar.copy(order = emptyList()) == e.env.toolbar.copy(order = emptyList()) && s.statusBar == e.env.statusBar && s.edit.tools == e.env.tools &&
            (Platform.isMobile || s.menuBar == e.env.menuBar)
    }

    /** Saves the current interface as the user's environment [name] (replacing one with that name). */
    fun saveCurrent(s: AppSettings, name: String): String {
        val env = Environment(name, s.layout, s.toolbar, s.menuBar, s.statusBar, s.edit.tools)
        PlatformFs.mkdirs(dir())
        PlatformFs.write(Paths.join(dir(), fileName(name)), Workspace.json.encodeToString(Environment.serializer(), env).encodeToByteArray())
        return USER + name
    }

    fun delete(id: String) {
        val name = id.removePrefix(USER)
        PlatformFs.delete(Paths.join(dir(), fileName(name)))
    }
}
