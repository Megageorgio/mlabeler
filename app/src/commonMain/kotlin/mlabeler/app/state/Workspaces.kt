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
            "one-picture", L("One picture", "Одна картинка"),
            L("Sound and labels in one picture.", "Звук и разметка одной картинкой."),
        ) {
            Environment(
                "one-picture", LayoutSettings(overlay = true, showPitch = true, showInspector = false),
                ToolbarSettings(listOf(ToolbarGroups.FILES, ToolbarGroups.HISTORY, ToolbarGroups.PLAY, ToolbarGroups.EDIT, ToolbarGroups.AUTO,
                    ToolbarGroups.VIEW), labels = true, big = false),
            )
        },
        BuiltIn(
            "full", L("Full", "Полная"),
            L("Every button as an icon, pitch and loudness.", "Все кнопки значками, высота тона и громкость."),
        ) {
            Environment(
                "full", LayoutSettings(showInspector = true, showPitch = true, showPower = true),
                ToolbarSettings(ToolbarGroups.all, labels = false, big = false),
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
