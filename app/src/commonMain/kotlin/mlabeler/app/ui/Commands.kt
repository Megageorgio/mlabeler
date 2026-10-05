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
    else -> letters.entries.firstOrNull { it.value == k }?.key?.toString() ?: "?"
}

private val letters = mapOf(
    'A' to Key.A, 'B' to Key.B, 'D' to Key.D, 'F' to Key.F, 'G' to Key.G, 'I' to Key.I, 'K' to Key.K, 'L' to Key.L,
    'M' to Key.M, 'O' to Key.O, 'Q' to Key.Q, 'R' to Key.R, 'S' to Key.S, 'T' to Key.T, 'W' to Key.W, 'Y' to Key.Y,
    'Z' to Key.Z,
)

private fun ch(c: Char, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
    Chord(letters.getValue(c), ctrl, shift, alt)

class Command(
    val id: String,
    val title: L,
    val keys: List<Chord>,
    val enabled: (EditorState) -> Boolean = { true },
    val run: (EditorState, AppState) -> Unit,
) {
    val keyLabel: String get() = keys.firstOrNull()?.label() ?: ""
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
    val done = Command("done", S.toggleDone, listOf(ch('D'))) { e, _ -> e.item?.let { i -> e.setMarks(i) { it.copy(done = !it.done) } } }
    val star = Command("star", S.toggleStar, listOf(ch('B'))) { e, _ -> e.item?.let { i -> e.setMarks(i) { it.copy(star = !it.star) } } }
    val files = Command("files", S.toggleFiles, listOf(ch('B', ctrl = true))) { _, a -> a.update { it.copy(layout = it.layout.copy(showFiles = !it.layout.showFiles)) } }
    val inspector = Command("inspector", S.toggleInspector, listOf(ch('I', ctrl = true))) { _, a -> a.update { it.copy(layout = it.layout.copy(showInspector = !it.layout.showInspector)) } }
    val wave = Command("waveform", S.waveform, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(showWaveform = !it.layout.showWaveform)) } }
    val spectrogram = Command("spectrogram", S.spectrogram, emptyList()) { _, a -> a.update { it.copy(layout = it.layout.copy(showSpectrogram = !it.layout.showSpectrogram)) } }
    val palette = Command("commands", S.commands, listOf(ch('K', ctrl = true))) { _, a -> a.showCommands = true }
    val settings = Command("settings", S.settings, listOf(Chord(Key.Comma, ctrl = true))) { _, a -> a.showSettings = true }
    val openFolder = Command("open", S.openFolder, listOf(ch('O', ctrl = true))) { _, a -> a.closeFolder() }

    val all = listOf(
        togglePlay, playFrom, loop, ripple, linked, undo, redo, save, split, merge, delete, rename, setLeft, setRight,
        nudgeLeft, nudgeRight, nudgeLeftBig, nudgeRightBig, prevBound, nextBound, prevInterval, nextInterval, tierUp, tierDown,
        prevFile, nextFile, zoomIn, zoomOut, zoomFit, zoomSel, home, end, done, star, files, inspector, wave, spectrogram,
        palette, settings, openFolder,
    )

    fun find(e: KeyEvent): Command? = all.firstOrNull { c -> c.keys.any { it.matches(e) } }
}
