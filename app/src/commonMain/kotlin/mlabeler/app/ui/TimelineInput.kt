package mlabeler.app.ui

// Pointer handling of the timeline: what is under the pointer and what a click does there.

import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.imageFromArgb
import mlabeler.app.resizeHorizontalIcon
import mlabeler.app.state.EditorState
import mlabeler.app.state.MouseActions
import mlabeler.app.state.LayoutSettings
import mlabeler.app.state.Mode
import mlabeler.core.format.get
import mlabeler.app.state.Selection
import mlabeler.app.state.ViewSettings
import mlabeler.app.theme.T
import mlabeler.app.theme.Themes
import mlabeler.app.theme.Tokens
import mlabeler.core.check.Problem
import mlabeler.core.check.Severity
import mlabeler.core.dsp.Spectrogram
import mlabeler.core.edit.BoundRef
import mlabeler.core.edit.IntervalRef
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import mlabeler.core.model.NoteTier
import mlabeler.core.model.PointTier
import mlabeler.core.model.name
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun hitBound(ed: EditorState, g: Geom, region: Region?, x: Float, grab: Float): BoundRef? {
    // labels are locked while the sound is edited: a press near a boundary selects sound like anywhere else
    if (ed.soundMode) return null
    val doc = ed.doc ?: return null
    val k = when (region) {
        is Region.Tier -> region.index
        Region.Wave, Region.Spec -> ed.guideTier
        else -> return null
    }
    val tier = doc.tiers.getOrNull(k) as? IntervalTier ?: return null
    val t = ed.viewStart + x / ed.pixelsPerSecond
    val b = tier.nearestBound(t)
    val bx = ((tier.bounds[b] - ed.viewStart) * ed.pixelsPerSecond).toFloat()
    if (abs(bx - x) > grab) return null
    // prefer the selected boundary when two are close together
    val sel = (ed.selection as? Selection.Bound)?.ref
    if (sel != null && sel.tier == k && abs(((tier.bounds[sel.bound] - ed.viewStart) * ed.pixelsPerSecond).toFloat() - x) <= grab) return sel
    return BoundRef(k, b)
}

internal enum class Gesture { Click, Double, Right, Middle, Ctrl, Alt }

/** The configured action for [g] where the pointer is (label lane or audio). */
internal fun mouseFor(ed: EditorState, region: Region?, g: Gesture): String {
    val m = ed.app.settings.mouse
    val tier = region is Region.Tier
    return when (g) {
        Gesture.Click -> if (tier) m.tierClick else m.audioClick
        Gesture.Double -> if (tier) m.tierDouble else m.audioDouble
        Gesture.Right -> if (tier) m.tierRight else m.audioRight
        Gesture.Middle -> if (tier) m.tierMiddle else m.audioMiddle
        Gesture.Ctrl -> if (tier) m.tierCtrl else m.audioCtrl
        Gesture.Alt -> if (tier) m.tierAlt else m.audioAlt
    }
}

/** Interval tier the pointer works on: the lane under it, or the guide tier over the audio. */
internal fun laneOf(ed: EditorState, region: Region?): Int = if (region is Region.Tier) region.index else ed.guideTier

internal fun isLabelLane(ed: EditorState, region: Region?): Boolean =
    ed.doc?.tiers?.getOrNull(laneOf(ed, region)) is IntervalTier

internal fun mouseAction(ed: EditorState, region: Region?, time: Double, action: String) {
    val doc = ed.doc ?: return
    val k = laneOf(ed, region)
    if (k >= doc.tiers.size) {
        // a comparison lane: only listening makes sense there
        val ref = ed.referenceTiers.getOrNull(k - doc.tiers.size)?.second ?: return
        val i = ref.indexAt(time)
        if (i >= 0 && action != MouseActions.NONE) ed.play(ref.startOf(i), ref.endOf(i), loop = false)
        return
    }
    val tier = doc.tiers.getOrNull(k) as? IntervalTier
    if (tier == null) {
        // notes and points keep their own simple behaviour
        if (action == MouseActions.PLAY || action == MouseActions.PLAY_FROM) ed.play(time, ed.viewStart + ed.visibleDuration)
        return
    }
    val i = tier.indexAt(time)
    val ref = if (i >= 0) IntervalRef(k, i) else null
    when (action) {
        MouseActions.NONE -> Unit
        MouseActions.SELECT -> ref?.let { ed.selectInterval(it, reveal = false) }
        MouseActions.DESELECT -> { ed.selection = mlabeler.app.state.Selection.None; ed.range = null }
        MouseActions.PLAY -> if (ref != null) ed.play(tier.startOf(i), tier.endOf(i))
        MouseActions.PLAY_FROM -> ed.play(time, ed.viewStart + ed.visibleDuration)
        MouseActions.RENAME -> ref?.let { ed.selectInterval(it, reveal = false); ed.editingText = it }
        MouseActions.SPLIT -> ed.splitAt(time, k, askName = false, playLeft = ed.app.settings.edit.cutPlay)
        MouseActions.SPLIT_NAME -> ed.splitAt(time, k, askName = true, playLeft = ed.app.settings.edit.cutPlay)
        MouseActions.DELETE -> ref?.let { ed.selectInterval(it, reveal = false); ed.deleteSelected() }
    }
}

internal fun playUnder(ed: EditorState, region: Region?, time: Double) {
    if (ed.mode == Mode.Oto) return ed.playOtoEntry()
    val doc = ed.doc ?: return
    val k = if (region is Region.Tier) region.index else ed.guideTier
    val tier = doc.tiers.getOrNull(k) as? IntervalTier ?: return
    val i = tier.indexAt(time)
    if (i >= 0) ed.play(tier.startOf(i), tier.endOf(i), loop = false)
}

internal fun onTap(ed: EditorState, region: Region?, time: Double, double: Boolean, touch: Boolean, shift: Boolean) {
    val doc = ed.doc ?: return
    when (region) {
        is Region.Tier -> {
            if (region.index >= doc.tiers.size) {
                // a reference tier: double click plays its interval
                val ref = ed.referenceTiers.getOrNull(region.index - doc.tiers.size)?.second ?: return
                val i = ref.indexAt(time)
                if (double && i >= 0) ed.play(ref.startOf(i), ref.endOf(i), loop = false)
                ed.cursor = time
                return
            }
            val tier = doc.tiers.getOrNull(region.index)
            if (tier is IntervalTier) {
                val i = tier.indexAt(time)
                if (i >= 0) {
                    val ref = IntervalRef(region.index, i)
                    ed.selectInterval(ref, reveal = false)
                    if (double) ed.editingText = ref
                }
            } else if (tier is NoteTier) {
                val i = mlabeler.core.edit.NoteEdits.indexAt(tier, time)
                if (i >= 0) {
                    ed.selectNote(region.index, i)
                    if (double) tier.notes[i].let { n -> ed.play(n.start, n.end, loop = false) }
                } else ed.activeTier = region.index
            } else {
                ed.activeTier = region.index
            }
            if (touch) ed.cursor = time
        }
        Region.Wave, Region.Spec -> {
            ed.range = null
            ed.cursor = time.coerceIn(0.0, ed.duration)
            if (ed.mode == Mode.Labels && ed.app.settings.edit.audioClickDeselects && !double && ed.selection !is mlabeler.app.state.Selection.Note) {
                ed.selection = mlabeler.app.state.Selection.None
            }
            if (ed.mode == Mode.Oto) {
                if (double) {
                    // the entry whose preutterance is closest
                    val best = ed.oto.entriesOfItem().minByOrNull { (_, e) -> abs(ed.oto.absolute(e).preutterance / 1000 - time) }
                    best?.let { ed.oto.select(it.first); ed.playOtoEntry() }
                } else if (touch || shift) {
                    ed.play(time, ed.viewStart + ed.visibleDuration)
                }
                return
            }
            if (double) {
                val tier = doc.tiers.getOrNull(ed.guideTier) as? IntervalTier ?: return
                val i = tier.indexAt(time)
                if (i >= 0) {
                    ed.selectInterval(IntervalRef(ed.guideTier, i), reveal = false)
                    ed.play(tier.startOf(i), tier.endOf(i), loop = false)
                }
            } else if (touch || shift) {
                ed.play(time, ed.viewStart + ed.visibleDuration)
            }
        }
        Region.Ruler -> {
            ed.cursor = time
            ed.play(time, ed.duration)
        }
        else -> Unit
    }
}

internal fun hitOto(ed: EditorState, g: Geom, region: Region?, x: Float, grab: Float): mlabeler.core.format.OtoMarker? {
    if (ed.mode != Mode.Oto || (region != Region.Wave && region != Region.Spec)) return null
    val e = ed.oto.current() ?: return null
    val a = ed.oto.absolute(e)
    // preutterance first: it is the one people grab most
    val order = listOf(mlabeler.core.format.OtoMarker.Preutterance, mlabeler.core.format.OtoMarker.Overlap, mlabeler.core.format.OtoMarker.Consonant,
        mlabeler.core.format.OtoMarker.Left, mlabeler.core.format.OtoMarker.Right)
    return order.minByOrNull { m -> abs(((a.get(m) / 1000 - ed.viewStart) * ed.pixelsPerSecond).toFloat() - x) }
        ?.takeIf { m -> abs(((a.get(m) / 1000 - ed.viewStart) * ed.pixelsPerSecond).toFloat() - x) <= grab }
}

/** A note border near [x] in the notes tier [k]: (tier, note, is it the start). */
internal fun hitNote(ed: EditorState, k: Int, x: Float, grab: Float): Triple<Int, Int, Boolean>? {
    val t = ed.doc?.tiers?.getOrNull(k) as? NoteTier ?: return null
    var best: Triple<Int, Int, Boolean>? = null
    var bestD = grab
    for ((i, n) in t.notes.withIndex()) {
        val xs = ((n.start - ed.viewStart) * ed.pixelsPerSecond).toFloat()
        val xe = ((n.end - ed.viewStart) * ed.pixelsPerSecond).toFloat()
        if (abs(xe - x) <= bestD) { bestD = abs(xe - x); best = Triple(k, i, false) }
        if (i == 0 || abs(t.notes[i - 1].end - n.start) > 1e-6) if (abs(xs - x) < bestD) { bestD = abs(xs - x); best = Triple(k, i, true) }
    }
    return best
}

/** Like awaitFirstDown, but for every mouse button (awaitFirstDown only reacts to the left one on desktop). */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitAnyButtonDown(): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
        if (ch != null) return ch
    }
}
