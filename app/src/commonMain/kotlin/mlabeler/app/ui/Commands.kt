package mlabeler.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.core.format.OtoMarker

/** A key combination. "Ctrl" is Cmd on macOS. */
data class Chord(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    fun matches(e: KeyEvent): Boolean {
        val ctrlDown = if (Platform.isMac) e.isMetaPressed else e.isCtrlPressed
        return e.key == key && ctrlDown == ctrl && e.isShiftPressed == shift && e.isAltPressed == alt
    }

    fun label(): String = buildString {
        if (ctrl) append(if (Platform.isMac) "⌘" else "Ctrl+")
        if (alt) append(if (Platform.isMac) "⌥" else "Alt+")
        if (shift) append(if (Platform.isMac) "⇧" else "Shift+")
        append(keyName(key))
    }
}

private fun keyName(k: Key): String = when (k) {
    Key.Spacebar -> "Space"
    Key.DirectionLeft -> "←"
    Key.DirectionRight -> "→"
    Key.DirectionUp -> "↑"
    Key.DirectionDown -> "↓"
    Key.Comma -> ","
    Key.Period -> "."
    Key.Equals -> "="
    Key.Minus -> "-"
    Key.Enter -> "Enter"
    Key.Delete -> "Del"
    Key.Backspace -> "Backspace"
    Key.PageUp -> "PgUp"
    Key.PageDown -> "PgDn"
    Key.Home -> "Home"
    Key.MoveEnd -> "End"
    Key.Tab -> "Tab"
    Key.F2 -> "F2"
    Key.Zero -> "0"
    else -> letters.entries.firstOrNull { it.value == k }?.key?.toString() ?: otherKeyName(k)
}

private val letters = mapOf(
    'A' to Key.A, 'B' to Key.B, 'D' to Key.D, 'F' to Key.F, 'G' to Key.G, 'I' to Key.I, 'K' to Key.K, 'L' to Key.L,
    'M' to Key.M, 'N' to Key.N, 'E' to Key.E, 'C' to Key.C, 'H' to Key.H, 'J' to Key.J, 'P' to Key.P, 'U' to Key.U, 'V' to Key.V, 'X' to Key.X, 'O' to Key.O, 'Q' to Key.Q, 'R' to Key.R, 'S' to Key.S, 'T' to Key.T, 'W' to Key.W, 'Y' to Key.Y,
    'Z' to Key.Z,
)

private fun ch(c: Char, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
    Chord(letters.getValue(c), ctrl, shift, alt)

/** User key bindings, by command id; loaded from the settings. */
object Keymap {
    private val state = androidx.compose.runtime.mutableStateOf<Map<String, List<Chord>>>(emptyMap())
    var overrides: Map<String, List<Chord>>
        get() = state.value
        set(v) { state.value = v }

    fun encode(c: Chord) = buildString {
        if (c.ctrl) append("ctrl+")
        if (c.shift) append("shift+")
        if (c.alt) append("alt+")
        append(c.key.keyCode)
    }

    fun decode(s: String): Chord? {
        val parts = s.split('+')
        val code = parts.last().toLongOrNull() ?: return null
        return Chord(Key(code), "ctrl" in parts, "shift" in parts, "alt" in parts)
    }

    fun load(map: Map<String, List<String>>) {
        overrides = map.mapValues { (_, v) -> v.mapNotNull { decode(it) } }
    }

    fun toSettings(): Map<String, List<String>> = overrides.mapValues { (_, v) -> v.map { encode(it) } }
}

class Command(
    val id: String,
    val title: L,
    val defaultKeys: List<Chord>,
    val enabled: (EditorState) -> Boolean = { true },
    val run: (EditorState, AppState) -> Unit,
) {
    val keys: List<Chord> get() = Keymap.overrides[id] ?: defaultKeys
    val keyLabel: String get() = keys.firstOrNull()?.label() ?: ""
    /** Null: works in every mode. */
    var mode: Mode? = null
        private set
    fun only(m: Mode) = also { mode = m }
}

object Commands {
    val togglePlay = Command("play", S.play, listOf(Chord(Key.Spacebar))) { e, _ -> e.togglePlay() }
    val playFrom = Command("play-from", L("Play from cursor", "Играть от курсора"), listOf(Chord(Key.Spacebar, shift = true))) { e, _ -> e.playFromCursor() }
    val loop = Command("loop", S.loop, listOf(ch('L'))) { _, a -> a.update { it.copy(edit = it.edit.copy(loop = !it.edit.loop)) } }
    val ripple = Command("ripple", S.ripple, listOf(ch('R'))) { _, a -> a.update { it.copy(edit = it.edit.copy(ripple = !it.edit.ripple)) } }
    val linked = Command("linked", S.linked, listOf(ch('G'))) { _, a -> a.update { it.copy(edit = it.edit.copy(linked = !it.edit.linked)) } }
    val undo = Command("undo", S.undo, listOf(ch('Z', ctrl = true)), { it.canUndo }) { e, _ -> e.undo() }
    val redo = Command("redo", S.redo, listOf(ch('Z', ctrl = true, shift = true), ch('Y', ctrl = true)), { it.canRedo }) { e, _ -> e.redo() }
    val save = Command("save", S.save, listOf(ch('S', ctrl = true))) { e, _ -> e.save() }
    val split = Command("split", S.split, listOf(ch('S'))) { e, _ -> e.splitAt() }
    val merge = Command("merge", S.merge, listOf(ch('M'))) { e, _ -> e.mergeSelected() }
    val delete = Command("delete", S.removeBoundary, listOf(Chord(Key.Delete), Chord(Key.Backspace))) { e, _ -> e.deleteSelected() }
    val rename = Command("rename", S.rename, listOf(Chord(Key.Enter), Chord(Key.F2)), { it.selectedInterval() != null }) { e, _ ->
        e.selectedInterval()?.let { e.editingText = it }
    }
    val setLeft = Command("set-left", S.setLeft, listOf(ch('Q'))) { e, _ -> e.setBoundAtCursor(left = true) }
    val setRight = Command("set-right", S.setRight, listOf(ch('W'))) { e, _ -> e.setBoundAtCursor(left = false) }
    val nudgeLeft = Command("nudge-left", S.nudgeLeft, listOf(Chord(Key.Comma), Chord(Key.DirectionLeft, alt = true))) { e, _ -> e.nudge(-1) }
    val nudgeRight = Command("nudge-right", S.nudgeRight, listOf(Chord(Key.Period), Chord(Key.DirectionRight, alt = true))) { e, _ -> e.nudge(1) }
    val nudgeLeftBig = Command("nudge-left-10", L("Nudge left ×10", "Сдвинуть влево ×10"), listOf(Chord(Key.Comma, shift = true), Chord(Key.DirectionLeft, alt = true, shift = true))) { e, _ -> e.nudge(-10) }
    val nudgeRightBig = Command("nudge-right-10", L("Nudge right ×10", "Сдвинуть вправо ×10"), listOf(Chord(Key.Period, shift = true), Chord(Key.DirectionRight, alt = true, shift = true))) { e, _ -> e.nudge(10) }
    val prevBound = Command("prev-bound", L("Previous boundary", "Предыдущая граница"), listOf(Chord(Key.DirectionLeft))) { e, _ -> e.stepBound(-1) }
    val nextBound = Command("next-bound", L("Next boundary", "Следующая граница"), listOf(Chord(Key.DirectionRight))) { e, _ -> e.stepBound(1) }
    val prevInterval = Command("prev-interval", S.prevInterval, listOf(Chord(Key.Tab, shift = true), Chord(Key.DirectionLeft, ctrl = true))) { e, _ -> e.stepInterval(-1) }
    val nextInterval = Command("next-interval", S.nextInterval, listOf(Chord(Key.Tab), Chord(Key.DirectionRight, ctrl = true))) { e, _ -> e.stepInterval(1) }
    val tierUp = Command("tier-up", L("Tier above", "Слой выше"), listOf(Chord(Key.DirectionUp))) { e, _ -> e.stepTier(-1) }
    val tierDown = Command("tier-down", L("Tier below", "Слой ниже"), listOf(Chord(Key.DirectionDown))) { e, _ -> e.stepTier(1) }
    val prevFile = Command("prev-file", S.prevFile, listOf(Chord(Key.PageUp), Chord(Key.DirectionUp, ctrl = true))) { e, _ -> e.openRelative(-1) }
    val nextFile = Command("next-file", S.nextFile, listOf(Chord(Key.PageDown), Chord(Key.DirectionDown, ctrl = true))) { e, _ -> e.openRelative(1) }
    val zoomIn = Command("zoom-in", S.zoomIn, listOf(Chord(Key.Equals, ctrl = true), Chord(Key.Equals))) { e, _ -> e.zoom(1.5, e.cursor ?: (e.viewStart + e.visibleDuration / 2)) }
    val zoomOut = Command("zoom-out", S.zoomOut, listOf(Chord(Key.Minus, ctrl = true), Chord(Key.Minus))) { e, _ -> e.zoom(1 / 1.5, e.cursor ?: (e.viewStart + e.visibleDuration / 2)) }
    val zoomFit = Command("zoom-fit", S.zoomFit, listOf(ch('F'), Chord(Key.Zero, ctrl = true))) { e, _ -> e.fitAll() }
    val zoomSel = Command("zoom-selection", S.zoomSelection, listOf(ch('Z'))) { e, _ -> e.zoomSelection() }
    val home = Command("home", L("Go to start", "В начало"), listOf(Chord(Key.Home))) { e, _ -> e.viewStart = 0.0; e.clampView() }
    val end = Command("end", L("Go to end", "В конец"), listOf(Chord(Key.MoveEnd))) { e, _ -> e.viewStart = e.duration; e.clampView() }
    val done = Command("done", S.toggleDone, listOf(ch('D'))) { e, _ ->
        if (e.mode == Mode.Oto) e.oto.current()?.let { o -> e.oto.setMarks(o) { it.copy(done = !it.done) } }
        else e.item?.let { i -> e.setMarks(i) { it.copy(done = !it.done) } }
    }
    val star = Command("star", S.toggleStar, listOf(ch('B'))) { e, _ ->
        if (e.mode == Mode.Oto) e.oto.current()?.let { o -> e.oto.setMarks(o) { it.copy(star = !it.star) } }
        else e.item?.let { i -> e.setMarks(i) { it.copy(star = !it.star) } }
    }
    val files = Command("files", S.toggleFiles, listOf(ch('B', ctrl = true))) { _, a -> a.update { it.copy(layout = it.layout.copy(showFiles = !it.layout.showFiles)) } }
    val inspector = Command("inspector", S.toggleInspector, listOf(ch('I', ctrl = true))) { _, a -> a.update { it.copy(layout = it.layout.copy(showInspector = !it.layout.showInspector)) } }
    val wave = Command("waveform", S.waveform, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(showWaveform = !it.layout.showWaveform)) } }
    val spectrogram = Command("spectrogram", S.spectrogram, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(showSpectrogram = !it.layout.showSpectrogram)) } }
    val pitchLane = Command("pitch", S.pitch, listOf(ch('P'))) { _, a -> a.update { it.copy(layout = it.layout.copy(showPitch = !it.layout.showPitch)) } }
    val powerLane = Command("power", S.power, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(showPower = !it.layout.showPower)) } }
    val palette = Command("commands", S.commands, listOf(ch('K', ctrl = true))) { _, a -> a.showCommands = true }
    val settings = Command("settings", S.settings, listOf(Chord(Key.Comma, ctrl = true))) { _, a -> a.showSettings = true }
    val openFolder = Command("open", S.openFolder, listOf(ch('O', ctrl = true))) { _, a -> a.closeFolder() }

    private fun otoSet(id: String, title: L, key: Char, m: OtoMarker) =
        Command(id, title, listOf(ch(key))) { e, _ -> e.oto.setMarker(m, e.editTime()) }.only(Mode.Oto)
    val otoLeft = otoSet("oto-left", L("Offset to cursor", "Начало (offset) к курсору"), 'Q', OtoMarker.Left)
    val otoOverlap = otoSet("oto-overlap", L("Overlap to cursor", "Overlap к курсору"), 'W', OtoMarker.Overlap)
    val otoPreu = otoSet("oto-preutterance", L("Preutterance to cursor", "Preutterance к курсору"), 'E', OtoMarker.Preutterance)
    val otoCons = otoSet("oto-consonant", L("Consonant to cursor", "Consonant к курсору"), 'R', OtoMarker.Consonant)
    val otoRight = otoSet("oto-cutoff", L("Cutoff to cursor", "Cutoff к курсору"), 'T', OtoMarker.Right)
    val nextEntry = Command("next-entry", L("Next entry", "Следующая запись"), listOf(Chord(Key.DirectionDown), Chord(Key.Tab))) { e, _ -> e.oto.step(1) }.only(Mode.Oto)
    val prevEntry = Command("prev-entry", L("Previous entry", "Предыдущая запись"), listOf(Chord(Key.DirectionUp), Chord(Key.Tab, shift = true))) { e, _ -> e.oto.step(-1) }.only(Mode.Oto)
    val otoDelete = Command("oto-delete", L("Delete entry", "Удалить запись"), listOf(Chord(Key.Delete))) { e, _ -> e.oto.delete() }.only(Mode.Oto)
    val otoDuplicate = Command("oto-duplicate", L("Duplicate entry", "Дублировать запись"), listOf(ch('D', ctrl = true))) { e, _ -> e.oto.duplicate() }.only(Mode.Oto)
    val otoAdd = Command("oto-add", L("New entry at cursor", "Новая запись у курсора"), listOf(Chord(Key.N))) { e, _ -> e.oto.add() }.only(Mode.Oto)
    val otoLock = Command("oto-lock", L("Preutterance moves all markers", "Preutterance двигает все маркеры"), listOf(ch('G'))) { _, a ->
        a.update { it.copy(edit = it.edit.copy(otoLockedDrag = !it.edit.otoLockedDrag)) }
    }.only(Mode.Oto)
    val batchRename = Command("batch-rename", L("Rename by pattern…", "Переименовать по шаблону…"), listOf(ch('H', ctrl = true))) { _, a -> a.showBatchRename = true }
    val overlay = Command("overlay", S.overlayShort, listOf(ch('V'))) { _, a -> a.update { it.copy(layout = it.layout.copy(overlay = !it.layout.overlay)) } }
    val tiersOnTop = Command("tiers-top", S.tiersOnTop, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(tiersOnTop = !it.layout.tiersOnTop)) } }
    val autoOto = Command("auto-oto", L("Automatic oto…", "Автоматическое oto…"), listOf(ch('A', ctrl = true, shift = true))) { _, a -> a.showAutoOto = true }.only(Mode.Oto)
    val help = Command("help", L("How it works", "Как с этим работать"), listOf(Chord(Key.F1))) { _, a -> a.showHelp = true }
    val speed = Command("speed", L("Playback speed", "Скорость воспроизведения"), listOf(ch('Y'))) { e, _ -> e.cycleSpeed() }
    val autolabel = Command("autolabel", L("Autolabel the selected part…", "Авторазметка выделенного…"), listOf(ch('A', ctrl = true, shift = true))) { _, a -> a.showAutolabel = true }.only(Mode.Labels)
    val workspace = Command("workspace", L("Folder settings…", "Настройки папки…"), listOf(ch('M', ctrl = true))) { _, a -> a.showWorkspace = true }

    init {
        for (c in listOf(ripple, linked, split, merge, delete, rename, setLeft, setRight, nudgeLeft, nudgeRight, nudgeLeftBig, nudgeRightBig,
            prevBound, nextBound, prevInterval, nextInterval, tierUp, tierDown)) c.only(Mode.Labels)
    }

    val all = listOf(
        overlay, tiersOnTop, autoOto, help, workspace, autolabel, speed, batchRename, otoLeft, otoOverlap, otoPreu, otoCons, otoRight, nextEntry, prevEntry, otoDelete, otoDuplicate, otoAdd, otoLock,
        togglePlay, playFrom, loop, ripple, linked, undo, redo, save, split, merge, delete, rename, setLeft, setRight,
        nudgeLeft, nudgeRight, nudgeLeftBig, nudgeRightBig, prevBound, nextBound, prevInterval, nextInterval, tierUp, tierDown,
        prevFile, nextFile, zoomIn, zoomOut, zoomFit, zoomSel, home, end, done, star, files, inspector, wave, spectrogram, pitchLane, powerLane,
        palette, settings, openFolder,
    )

    fun find(e: KeyEvent, mode: Mode): Command? = all.firstOrNull { c -> (c.mode == null || c.mode == mode) && c.keys.any { it.matches(e) } }

    fun visible(mode: Mode) = all.filter { it.mode == null || it.mode == mode }
}

private fun otherKeyName(k: Key): String {
    val named = mapOf(
        Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4", Key.Five to "5", Key.Six to "6", Key.Seven to "7",
        Key.Eight to "8", Key.Nine to "9", Key.F1 to "F1", Key.F3 to "F3", Key.F4 to "F4", Key.F5 to "F5", Key.F6 to "F6",
        Key.F7 to "F7", Key.F8 to "F8", Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12", Key.Escape to "Esc",
        Key.Semicolon to ";", Key.Apostrophe to "'", Key.Slash to "/", Key.Backslash to "\\", Key.LeftBracket to "[", Key.RightBracket to "]",
        Key.Grave to "`", Key.Insert to "Ins",
    )
    named[k]?.let { return it }
    // letters not in the table above
    val s = k.toString()
    return s.substringAfterLast(' ').takeIf { it.length in 1..3 } ?: "#${k.keyCode}"
}
