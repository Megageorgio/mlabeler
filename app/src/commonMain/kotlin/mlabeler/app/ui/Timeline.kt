package mlabeler.app.ui

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

/** Width of the piano keys at the left of the pitch lane, dp. */
private const val PIANO_KEYS = 22f

private sealed interface Region {
    data object Ruler : Region
    data object Wave : Region
    data object Spec : Region
    data class Tier(val index: Int) : Region
    data object WaveSpecSplit : Region
    data object TierSplit : Region
    /** The line between two lanes ([above], [below] are lane ids), dragged to share the height differently. */
    data class LaneSplit(val above: String, val below: String) : Region
}

/** Vertical layout of the timeline in pixels. */
private class Geom(
    val width: Float,
    val height: Float,
    val ruler: Float,
    val waveTop: Float,
    val waveBottom: Float,
    val specTop: Float,
    val specBottom: Float,
    val tiersTop: Float,
    val tierH: Float,
    val tierCount: Int,
    val pitchTop: Float = 0f,
    val pitchBottom: Float = 0f,
    val powerTop: Float = 0f,
    val powerBottom: Float = 0f,
    val overlay: Boolean = false,
    val tiersOnTop: Boolean = false,
    val dim: Float = 0f,
    val waveFill: Float = 0f,
    val namesOnAudio: Boolean = false,
    val namesX: Float = 0.5f,
    val namesY: Float = 0.5f,
    /** Lines between neighbouring lanes: y and the lanes above and below. */
    val splits: List<Triple<Float, String, String>> = emptyList(),
    /** Each shown lane's top and bottom, by id. */
    val lanes: Map<String, Pair<Float, Float>> = emptyMap(),
    /** Height shared by the audio lanes (for dragging their lines). */
    val audioArea: Float = 0f,
    /** Lane sizes can't be changed (no grips, the lines between lanes are not grabbed). */
    val locked: Boolean = false,
) {
    val tiersBottom: Float get() = tiersTop + tierH * tierCount
    val audioTop: Float get() = min(waveTop, specTop)
    val audioBottom: Float get() = maxOf(waveBottom, specBottom, pitchBottom, powerBottom)
    /** Line between the waveform and the spectrogram lanes (whichever is on top). */
    val waveSpecLine: Float get() = if (specTop >= waveBottom) waveBottom else specBottom

    fun region(y: Float, grab: Float): Region? {
        if (y < ruler) return Region.Ruler
        if (!locked) for ((ly, a, b) in splits) if (abs(y - ly) < grab / 2) return Region.LaneSplit(a, b)
        if (tierCount > 0 && y >= tiersTop && y < tiersBottom) {
            return Region.Tier(((y - tiersTop) / tierH).toInt().coerceIn(0, tierCount - 1))
        }
        if (!locked) for ((ly, a, b) in splits) if (abs(y - ly) < grab / 2) return Region.LaneSplit(a, b)
        if (y in specTop..specBottom && specBottom > specTop) return Region.Spec
        if (y in waveTop..waveBottom) return Region.Wave
        if (y in pitchTop..pitchBottom && pitchBottom > pitchTop) return Region.Spec
        if (y in powerTop..powerBottom && powerBottom > powerTop) return Region.Spec
        return null
    }

    fun tierTop(k: Int) = tiersTop + k * tierH
}

/** Lane ids in their default order from the old switches (labels on top, spectrogram first). */
fun defaultLaneOrder(l: LayoutSettings): List<String> {
    val audio = if (l.spectrogramFirst) listOf("spec", "wave", "pitch", "power") else listOf("wave", "spec", "pitch", "power")
    return if (l.tiersOnTop) listOf("labels") + audio else audio + "labels"
}

/** The user's lane order, completed with lanes it lacks. */
fun laneOrder(l: LayoutSettings): List<String> {
    val all = defaultLaneOrder(l)
    val saved = l.laneOrder.filter { it in all }.distinct()
    return if (saved.isEmpty()) all else saved + all.filter { it !in saved }
}

/** Shares of the audio area for the shown audio lanes (summing to 1). */
fun laneShares(l: LayoutSettings, shown: List<String>): Map<String, Float> {
    val ps = if ("pitch" in shown) l.pitchShare.coerceIn(0.1f, 0.6f) else 0f
    val pw = if ("power" in shown) l.powerShare.coerceIn(0.08f, 0.5f) else 0f
    val main = (1f - ps - pw).coerceAtLeast(0.1f)
    val both = "wave" in shown && "spec" in shown
    fun default(id: String) = when (id) {
        "wave" -> if (both) main * l.waveShare.coerceIn(0.1f, 0.9f) else main
        "spec" -> if (both) main * (1 - l.waveShare.coerceIn(0.1f, 0.9f)) else main
        "pitch" -> ps
        else -> pw
    }
    val raw = shown.associateWith { (l.laneWeights[it] ?: default(it)).coerceAtLeast(0.03f) }
    val sum = raw.values.sum().coerceAtLeast(1e-6f)
    return raw.mapValues { it.value / sum }
}

private fun geom(size: IntSize, density: Float, layout: LayoutSettings, tiers: Int): Geom {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val ruler = 22f * density
    val tierH = max(layout.tierHeight.coerceIn(14f, 96f), layout.labelFontSize.coerceIn(8f, 48f) * 1.3f + 2f) * density * (if (Platform.isMobile) 1.15f else 1f)
    val minAudio = 48f * density
    val overlay = layout.overlay
    var tiersH = tierH * tiers
    val audioH = if (overlay) h - ruler else max(minAudio, h - ruler - tiersH)
    if (!overlay) tiersH = h - ruler - audioH else tiersH = min(tiersH, (h - ruler) * 0.6f)
    val tierHeight = if (tiers > 0) min(tierH, tiersH / tiers) else tierH
    val showW = layout.showWaveform
    val showS = layout.showSpectrogram
    val pitchOwn = layout.showPitch && !layout.pitchOverSpectrogram
    val common = { g: Geom -> g }
    if (overlay) {
        // one picture: the waveform over the spectrogram, labels over both; pitch and loudness lanes below it
        val audioTop = ruler
        val tiersTop = if (layout.tiersOnTop) ruler else h - tiersH
        val order = laneOrder(layout).filter { it == "pitch" && pitchOwn || it == "power" && layout.showPower }
        val shares = laneShares(layout, listOf("main") .let { listOf("wave", "spec").filter { id -> id == "wave" && showW || id == "spec" && showS }.ifEmpty { listOf("spec") } } + order)
        val mainShare = shares.filterKeys { it == "wave" || it == "spec" }.values.sum()
        val mainH = audioH * mainShare
        var y = audioTop + mainH
        val lanes = mutableMapOf<String, Pair<Float, Float>>()
        val splits = mutableListOf<Triple<Float, String, String>>()
        var prev = "main"
        for (id in order) {
            val hh = audioH * (shares[id] ?: 0f)
            splits += Triple(y, prev, id)
            lanes[id] = y to y + hh
            y += hh
            prev = id
        }
        // the top of the labels laid over the picture can be dragged too
        if (tiers > 0) splits += if (layout.tiersOnTop) Triple(tiersTop + tiersH, "labels", "main") else Triple(tiersTop, "main", "labels")
        val (pt, pb) = lanes["pitch"] ?: (0f to 0f)
        val (wt, wb) = lanes["power"] ?: (0f to 0f)
        return Geom(w, h, ruler, audioTop, if (showW) audioTop + mainH else audioTop, audioTop, if (showS) audioTop + mainH else audioTop,
            tiersTop, tierHeight, tiers, pt, pb, wt, wb, true, layout.tiersOnTop, layout.overlayDim.coerceIn(0f, 0.8f),
            layout.overlayWaveFillAlpha.coerceIn(0.05f, 1f), layout.namesOnAudio, layout.namesX.coerceIn(0f, 1f), layout.namesY.coerceIn(0f, 1f),
            splits, lanes + ("main" to (audioTop to audioTop + mainH)), audioH, layout.locked)
    }
    // stacked lanes in the user's order
    val visible = laneOrder(layout).filter { id ->
        when (id) {
            "wave" -> showW
            "spec" -> showS
            "pitch" -> pitchOwn
            "power" -> layout.showPower
            else -> tiers > 0
        }
    }.toMutableList()
    if (visible.none { it != "labels" }) visible.add(if (layout.tiersOnTop) visible.size else 0, "spec")
    val audioIds = visible.filter { it != "labels" }
    val shares = laneShares(layout, audioIds)
    var y = ruler
    val lanes = mutableMapOf<String, Pair<Float, Float>>()
    val splits = mutableListOf<Triple<Float, String, String>>()
    for ((k, id) in visible.withIndex()) {
        val hh = if (id == "labels") tiersH else audioH * (shares[id] ?: 0f)
        if (k > 0) splits += Triple(y, visible[k - 1], id)
        lanes[id] = y to y + hh
        y += hh
    }
    fun top(id: String) = lanes[id]?.first ?: 0f
    fun bottom(id: String) = lanes[id]?.second ?: 0f
    val tiersTop = lanes["labels"]?.first ?: (ruler + audioH)
    return Geom(w, h, ruler, top("wave"), bottom("wave"), top("spec"), bottom("spec"), tiersTop, tierHeight, tiers,
        top("pitch"), bottom("pitch"), top("power"), bottom("power"), false, layout.tiersOnTop, layout.overlayDim.coerceIn(0f, 0.8f),
        layout.overlayWaveFillAlpha.coerceIn(0.05f, 1f), layout.namesOnAudio, layout.namesX.coerceIn(0f, 1f), layout.namesY.coerceIn(0f, 1f),
        splits, lanes, audioH, layout.locked)
}

/** Colour lookup for spectrogram values 0..255 with brightness and contrast applied. */
private fun lut(colors: List<Color>, brightness: Float, contrast: Float): IntArray {
    val argb = colors.map { it.toArgb() }
    return IntArray(256) { v ->
        var x = ((v / 255f - 0.5f) * contrast + 0.5f + brightness).coerceIn(0f, 1f)
        val pos = x * (argb.size - 1)
        val i = floor(pos).toInt().coerceIn(0, argb.size - 2)
        val t = pos - i
        val a = argb[i]
        val b = argb[i + 1]
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        x = 0f
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

private fun renderSpectrogram(spec: Spectrogram, viewStart: Double, pps: Double, widthPx: Int, maxFreq: Float, lut: IntArray): ImageBitmap? {
    if (widthPx <= 0) return null
    val melTop = Spectrogram.hzToMel(min(maxFreq.toDouble(), spec.maxFreq)) / Spectrogram.hzToMel(spec.maxFreq)
    val bands = max(8, (spec.bands * melTop).toInt())
    val h = bands
    val pixels = IntArray(widthPx * h)
    val framesPerSec = spec.sampleRate.toDouble() / spec.hop
    val bg = lut[0]
    for (x in 0 until widthPx) {
        val t0 = viewStart + x / pps
        val t1 = viewStart + (x + 1) / pps
        var f0 = floor(t0 * framesPerSec).toInt()
        var f1 = floor(t1 * framesPerSec).toInt()
        if (f1 <= f0) f1 = f0 + 1
        if (f0 < 0 || f0 >= spec.ready) {
            for (y in 0 until h) pixels[y * widthPx + x] = bg
            continue
        }
        f1 = min(f1, spec.ready)
        if (f1 - f0 > 8) {
            // many frames per pixel: sample a few
            val step = (f1 - f0) / 8
            for (b in 0 until bands) {
                var m = 0
                var f = f0
                while (f < f1) {
                    val v = spec.value(f, b)
                    if (v > m) m = v
                    f += step
                }
                pixels[(h - 1 - b) * widthPx + x] = lut[m]
            }
        } else if (f1 - f0 == 1 && pps > framesPerSec) {
            // zoomed in past one column per pixel: blend the two nearest columns instead of repeating one
            val pos = ((t0 + t1) / 2) * framesPerSec - 0.5
            val a = floor(pos).toInt().coerceIn(0, spec.ready - 1)
            val b1 = min(a + 1, spec.ready - 1)
            val k = (pos - a).toFloat().coerceIn(0f, 1f)
            for (b in 0 until bands) {
                val v = (spec.value(a, b) * (1 - k) + spec.value(b1, b) * k).toInt().coerceIn(0, 255)
                pixels[(h - 1 - b) * widthPx + x] = lut[v]
            }
        } else {
            for (b in 0 until bands) {
                var m = 0
                for (f in f0 until f1) {
                    val v = spec.value(f, b)
                    if (v > m) m = v
                }
                pixels[(h - 1 - b) * widthPx + x] = lut[m]
            }
        }
        f0 = 0
    }
    return imageFromArgb(widthPx, h, pixels)
}

private fun niceStep(minSeconds: Double): Double {
    val steps = doubleArrayOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0)
    return steps.firstOrNull { it >= minSeconds } ?: 600.0
}

@Composable
fun Timeline(ed: EditorState, layout: LayoutSettings, view: ViewSettings, onLayout: (LayoutSettings) -> Unit, modifier: Modifier = Modifier) {
    val c = T.c
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val doc = ed.doc
    val tierCount = ed.laneTiers()
    val palette = Themes.palettes[view.palette] ?: c.spectrogram
    val colorLut = remember(palette, view.brightness, view.contrast) { lut(palette, view.brightness, view.contrast) }
    var hoverIcon by remember { mutableStateOf(PointerIcon.Default) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layoutState = rememberUpdatedState(layout)
    val onLayoutState = rememberUpdatedState(onLayout)
    val lastTap = remember { mutableStateOf(Triple(0L, Offset.Zero, 0)) }
    val cutByLastTap = remember { mutableStateOf(false) }
    val lastWheelStep = remember { mutableStateOf(0L) }

    // spectrogram image for the current view
    val spec = ed.spectrogram
    val specImage = remember(spec, ed.spectrogramProgress, ed.viewStart, ed.pixelsPerSecond, size.width, view.maxFreq, colorLut, layout.showSpectrogram) {
        if (spec == null || !layout.showSpectrogram) null
        else renderSpectrogram(spec, ed.viewStart, ed.pixelsPerSecond, size.width, view.maxFreq, colorLut)
    }

    BoxWithConstraints(modifier.background(c.laneBg)) {
        val tierStyle = TextStyle(fontSize = (layout.labelFontSize.coerceIn(8f, 48f) + if (Platform.isMobile) 2f else 0f).sp, color = c.tierText, fontFamily = T.font)
        val smallStyle = TextStyle(fontSize = 10.sp, color = c.muted, fontFamily = T.font)

        Canvas(
            Modifier.fillMaxSize()
                .clipToBounds()
                .pointerHoverIcon(hoverIcon)
                .pointerInput(ed) {
                    // hover, wheel
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: continue
                            when (e.type) {
                                PointerEventType.Move, PointerEventType.Enter -> if (ch.type == PointerType.Mouse && !ch.pressed) {
                                    val g = geom(this.size, density, layoutState.value, ed.laneTiers())
                                    val t = ed.viewStart + ch.position.x / ed.pixelsPerSecond
                                    ed.cursor = t
                                    val r = g.region(ch.position.y, 8 * density)
                                    hoverIcon = when {
                                        r is Region.LaneSplit -> PointerIcon.Hand
                                        hitBound(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        hitOto(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        else -> PointerIcon.Default
                                    }
                                }
                                PointerEventType.Exit -> if (ch.type == PointerType.Mouse) ed.cursor = null
                                PointerEventType.Scroll -> {
                                    val d = ch.scrollDelta
                                    val mods = e.keyboardModifiers
                                    val gw = geom(this.size, density, layoutState.value, ed.laneTiers())
                                    if (mods.isAltPressed && gw.waveBottom > gw.waveTop && ch.position.y in gw.waveTop..gw.waveBottom) {
                                        // the waveform: Alt+wheel zooms it vertically
                                        val l = layoutState.value
                                        val f = if (d.y < 0) 1.25f else 0.8f
                                        onLayoutState.value(l.copy(waveGain = (l.waveGain * f).coerceIn(1f, 64f).let { v -> if (abs(v - 1f) < 0.05f) 1f else v }))
                                    } else if (mods.isAltPressed && gw.pitchBottom > gw.pitchTop && ch.position.y in gw.pitchTop..gw.pitchBottom) {
                                        // the piano roll: Alt+wheel moves it up and down, Ctrl+Alt+wheel zooms it
                                        ed.scrollPitch(if (mods.isCtrlPressed) d.y.toDouble() else -d.y.toDouble() * 2, zoom = mods.isCtrlPressed)
                                    } else if (mods.isCtrlPressed || mods.isMetaPressed) {
                                        val anchor = ed.viewStart + ch.position.x / ed.pixelsPerSecond
                                        ed.zoom(1.15.pow(-d.y.toDouble()), anchor)
                                    } else if (ed.app.settings.mouse.wheel == "phonemes" && !mods.isShiftPressed && ed.mode == Mode.Labels && abs(d.y) >= abs(d.x)) {
                                        // the wheel walks through the phonemes (Shift+wheel still scrolls)
                                        val now = ch.uptimeMillis
                                        if (now - lastWheelStep.value > 60 && d.y != 0f) {
                                            lastWheelStep.value = now
                                            ed.stepInterval(if (d.y > 0) 1 else -1)
                                        }
                                    } else {
                                        val dx = if (abs(d.x) > abs(d.y)) d.x else d.y
                                        ed.scrollBy(dx * 60.0 * density)
                                    }
                                    ch.consume()
                                }
                            }
                        }
                    }
                }
                .pointerInput(ed) {
                    awaitEachGesture {
                        val down = awaitAnyButtonDown()
                        // a click anywhere on the picture finishes typing a label (focus leaves the field)
                        ed.requestFocus()
                        val first = currentEvent
                        val touch = down.type == PointerType.Touch || down.type == PointerType.Stylus
                        val g = geom(size, density, layoutState.value, ed.laneTiers())
                        val grab = (if (touch) 20f else 6f) * density
                        val region = g.region(down.position.y, (if (touch) 16f else 8f) * density)
                        val mods = first.keyboardModifiers
                        fun timeAt(x: Float) = ed.viewStart + x / ed.pixelsPerSecond
                        val downTime = timeAt(down.position.x)

                        // lines between lanes: the two lanes share their height differently (labels: their row height)
                        if (region is Region.LaneSplit) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.first()
                                if (!chg.pressed) break
                                val dy = chg.positionChange().y
                                chg.consume()
                                val l = layoutState.value
                                val (a, b) = region.above to region.below
                                if (a == "labels" || b == "labels") {
                                    if (g.tierCount > 0) {
                                        // the labels grow when the line moves away from them
                                        val grow = if (b == "labels") -dy else dy
                                        onLayoutState.value(l.copy(tierHeight = (l.tierHeight + grow / density / g.tierCount).coerceIn(14f, 96f)))
                                    }
                                } else {
                                    val shown = g.lanes.keys.filter { it != "labels" && it != "main" }
                                    val shares = laneShares(l, shown).toMutableMap()
                                    // in the overlaid picture "main" is the waveform and spectrogram together
                                    fun idsOf(x: String) = if (x == "main") listOf("wave", "spec").filter { it in shares } else listOf(x)
                                    val up = idsOf(a)
                                    val down = idsOf(b)
                                    val d = dy / g.audioArea.coerceAtLeast(1f)
                                    val upSum = up.sumOf { (shares[it] ?: 0f).toDouble() }.toFloat()
                                    val downSum = down.sumOf { (shares[it] ?: 0f).toDouble() }.toFloat()
                                    if (upSum + d >= 0.05f && downSum - d >= 0.05f) {
                                        for (id in up) shares[id] = (shares[id] ?: 0f) * (upSum + d) / upSum
                                        for (id in down) shares[id] = (shares[id] ?: 0f) * (downSum - d) / downSum
                                        onLayoutState.value(l.copy(laneWeights = l.laneWeights + shares))
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }

                        // the piano roll (pitch in its own lane): notes move up and down, the pencil draws f0
                        val pitchLane = g.pitchBottom > g.pitchTop && down.position.y in g.pitchTop..g.pitchBottom &&
                            ed.pitch != null && ed.mode == Mode.Labels && !first.buttons.isTertiaryPressed
                        if (pitchLane) {
                            val top = g.pitchTop
                            val bottom = g.pitchBottom
                            val rowH = ((bottom - top) / (ed.pitchHi - ed.pitchLo)).toFloat()
                            fun midiAt(yy: Float) = ed.pitchLo + (bottom - yy) / (bottom - top) * (ed.pitchHi - ed.pitchLo)
                            fun yOf(m: Double) = (bottom - (m - ed.pitchLo) / (ed.pitchHi - ed.pitchLo) * (bottom - top)).toFloat()
                            if (down.position.x < PIANO_KEYS * density) {
                                // a click on the keys fits the range to the singing
                                ed.fitPitchRange()
                                return@awaitEachGesture
                            }
                            if (ed.f0Pencil) {
                                val erase = first.buttons.isSecondaryPressed
                                ed.beginF0Stroke()
                                var lt = downTime
                                var lm = midiAt(down.position.y)
                                ed.drawF0(lt, if (erase) null else lm, lt, if (erase) null else lm)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!chg.pressed) break
                                    if (chg.positionChange() != Offset.Zero) {
                                        val t = timeAt(chg.position.x)
                                        val m = midiAt(chg.position.y.coerceIn(top, bottom))
                                        ed.drawF0(lt, if (erase) null else lm, t, if (erase) null else m)
                                        lt = t
                                        lm = m
                                        ed.cursor = t
                                        chg.consume()
                                    }
                                }
                                ed.endF0Stroke()
                                return@awaitEachGesture
                            }
                            val noteK = ed.doc?.tiers?.indexOfFirst { it is NoteTier } ?: -1
                            val nt = ed.doc?.tiers?.getOrNull(noteK) as? NoteTier
                            val hitI = if (nt == null || first.buttons.isSecondaryPressed) -1 else nt.notes.indexOfFirst { n ->
                                n.pitch != null && downTime in n.start..n.end && abs(yOf(n.pitch!!) - down.position.y) <= max(rowH / 2, 6 * density)
                            }
                            if (nt != null && hitI >= 0) {
                                val n = nt.notes[hitI]
                                val edge = when {
                                    abs(down.position.x - ((n.start - ed.viewStart) * ed.pixelsPerSecond).toFloat()) <= grab -> true
                                    abs(down.position.x - ((n.end - ed.viewStart) * ed.pixelsPerSecond).toFloat()) <= grab -> false
                                    else -> null
                                }
                                ed.selectNote(noteK, hitI)
                                ed.beginNoteDrag()
                                val base = n.pitch!!
                                var moved = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!chg.pressed) break
                                    if (chg.positionChange() != Offset.Zero) {
                                        moved = true
                                        if (edge != null) ed.noteDragTo(noteK, hitI, edge, timeAt(chg.position.x))
                                        else {
                                            val raw = base + (down.position.y - chg.position.y) / rowH
                                            // semitones; with Alt in cents
                                            ed.notePitchDragTo(noteK, hitI, if (ev.keyboardModifiers.isAltPressed) kotlin.math.round(raw * 100) / 100 else kotlin.math.round(raw))
                                        }
                                        chg.consume()
                                    }
                                }
                                ed.endNoteDrag()
                                if (!moved) ed.cursor = downTime
                                return@awaitEachGesture
                            }
                        }

                        // right click: what the mouse settings say (plays the part under the pointer by default)
                        if (!touch && first.buttons.isSecondaryPressed) {
                            if (ed.mode == Mode.Oto) ed.playOtoEntry()
                            else mouseAction(ed, region, downTime, mouseFor(ed, region, Gesture.Right))
                            return@awaitEachGesture
                        }

                        val otoMarker = hitOto(ed, g, region, down.position.x, grab)
                        if (otoMarker != null && !first.buttons.isTertiaryPressed) {
                            val e = ed.oto.current()!!
                            val offset = ed.oto.absolute(e).get(otoMarker) / 1000 - downTime
                            // with a mouse the marker goes right under the pointer; a finger would hide it, so it keeps its distance
                            val follow = if (touch) offset else 0.0
                            ed.oto.beginDrag()
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    moved = true
                                    ed.oto.dragTo(otoMarker, timeAt(chg.position.x) + follow, ev.keyboardModifiers.isShiftPressed)
                                    ed.cursor = timeAt(chg.position.x) + follow
                                    ed.previewAt(timeAt(chg.position.x) + follow)
                                    chg.consume()
                                }
                            }
                            if (moved) ed.oto.endDrag() else ed.oto.dragTo(otoMarker, downTime + offset, false).also { ed.oto.endDrag() }
                            return@awaitEachGesture
                        }
                        // borders of notes in a notes lane
                        val noteHit = if (region is Region.Tier) hitNote(ed, region.index, down.position.x, grab) else null
                        if (noteHit != null && !first.buttons.isTertiaryPressed) {
                            val (k, i, isStart) = noteHit
                            ed.selectNote(k, i)
                            ed.beginNoteDrag()
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    moved = true
                                    ed.noteDragTo(k, i, isStart, timeAt(chg.position.x))
                                    ed.cursor = timeAt(chg.position.x)
                                    chg.consume()
                                }
                            }
                            if (moved) ed.endNoteDrag() else ed.endNoteDrag()
                            return@awaitEachGesture
                        }
                        val bound = if (ed.mode == Mode.Oto) null else hitBound(ed, g, region, down.position.x, grab)
                        val panTool = ed.app.settings.edit.activeTool == "pan" && !touch
                        val pan = first.buttons.isTertiaryPressed || region == Region.Ruler || panTool

                        if (bound != null && !pan) {
                            // drag a boundary
                            val tier = ed.doc?.tiers?.get(bound.tier) as? IntervalTier
                            val offset = (tier?.bounds?.get(bound.bound) ?: downTime) - downTime
                            // with a mouse the boundary goes right under the pointer; a finger would hide it, so it keeps its distance
                            val follow = if (touch) offset else 0.0
                            ed.beginDrag(bound)
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    // a part selected by hand goes away as soon as a boundary is moved
                                    if (!moved) ed.range = null
                                    moved = true
                                    val m = ev.keyboardModifiers
                                    ed.dragTo(bound, timeAt(chg.position.x) + follow, m.isShiftPressed, m.isAltPressed)
                                    ed.cursor = timeAt(chg.position.x) + follow
                                    ed.previewAt(timeAt(chg.position.x) + follow)
                                    chg.consume()
                                }
                            }
                            if (moved) ed.endDrag(bound) else ed.cancelDrag(bound)
                            return@awaitEachGesture
                        }

                        // touch: tap, one-finger pan, pinch; long press in the audio area selects a range
                        // mouse: click, drag selects a range; middle button or ruler pans
                        var dragged = false
                        var selecting = !touch && !pan && (region == Region.Wave || region == Region.Spec)
                        var panning = pan
                        val slop = viewConfiguration.touchSlop
                        val pressTime = down.uptimeMillis
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // pinch and pan
                                dragged = true
                                selecting = false
                                val zoom = ev.calculateZoom()
                                val centroid = ev.calculateCentroid(useCurrent = true)
                                val prevCentroid = ev.calculateCentroid(useCurrent = false)
                                if (zoom != 1f) ed.zoom(zoom.toDouble(), ed.viewStart + centroid.x / ed.pixelsPerSecond)
                                ed.scrollBy((prevCentroid.x - centroid.x).toDouble())
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val chg = pressed.first()
                            val total = chg.position - down.position
                            if (!dragged && hypot(total.x, total.y) > slop) {
                                dragged = true
                                if (touch && !panning) {
                                    val long = chg.uptimeMillis - pressTime > viewConfiguration.longPressTimeoutMillis
                                    if (long && (region == Region.Wave || region == Region.Spec)) selecting = true else panning = true
                                }
                            }
                            if (dragged) {
                                if (panning) ed.scrollBy(-chg.positionChange().x.toDouble())
                                else if (selecting) {
                                    // a selected part replaces a selected phoneme: Delete and nudging act on the part
                                    if (ed.selection !is Selection.None && ed.mode == Mode.Labels) ed.selection = Selection.None
                                    val a = downTime
                                    val b = timeAt(chg.position.x).coerceIn(0.0, ed.duration)
                                    ed.range = min(a, b) to max(a, b)
                                    ed.cursor = b
                                }
                                chg.consume()
                            }
                        }
                        // the play tool plays what was just selected
                        if (dragged && selecting && ed.app.settings.edit.activeTool == "play") ed.range?.let { (a, b) -> ed.play(a, b) }
                        if (!dragged) {
                            // a tap or click
                            val (t0, p0, n0) = lastTap.value
                            val double = down.uptimeMillis - t0 < viewConfiguration.doubleTapTimeoutMillis &&
                                (down.position - p0).getDistance() < 24 * density
                            lastTap.value = Triple(down.uptimeMillis, down.position, if (double) n0 + 1 else 1)
                            val ctrl = if (Platform.isMac) mods.isMetaPressed else mods.isCtrlPressed
                            val labels = ed.mode == Mode.Labels && (region is Region.Tier || region == Region.Wave || region == Region.Spec)
                            when {
                                !labels -> onTap(ed, region, downTime, double, touch, mods.isShiftPressed)
                                first.buttons.isTertiaryPressed -> mouseAction(ed, region, downTime, mouseFor(ed, region, Gesture.Middle))
                                ctrl -> mouseAction(ed, region, downTime, mouseFor(ed, region, Gesture.Ctrl))
                                mods.isAltPressed -> mouseAction(ed, region, downTime, mouseFor(ed, region, Gesture.Alt))
                                // scissors: every click is a cut (two quick cuts are not a double click)
                                double && ed.app.settings.edit.activeTool != "cut" -> mouseAction(ed, region, downTime, mouseFor(ed, region, Gesture.Double))
                                ed.app.settings.edit.activeTool == "play" && !touch -> {
                                    if (region is Region.Tier || (region == Region.Wave || region == Region.Spec) && ed.range == null) playUnder(ed, region, downTime)
                                }
                                ed.app.settings.edit.activeTool == "cut" && !touch && !mods.isShiftPressed && isLabelLane(ed, region) &&
                                    (region !is Region.Tier || ed.app.settings.edit.cutOnLanes) -> {
                                    val e = ed.app.settings.edit
                                    ed.splitAt(downTime, laneOf(ed, region), askName = e.cutAskName, playLeft = e.cutPlay)
                                    cutByLastTap.value = true
                                    return@awaitEachGesture
                                }
                                // a plain click set to do something else than selecting (split, play…)
                                !touch && !mods.isShiftPressed && mouseFor(ed, region, Gesture.Click).let { it != null && it != MouseActions.SELECT } -> {
                                    val action = mouseFor(ed, region, Gesture.Click)!!
                                    // a click clears what was selected before, as a selecting click does (the part always; the
                                    // phoneme too when a click on the audio deselects); renaming and removing act on the phoneme
                                    if (action != MouseActions.RENAME && action != MouseActions.DELETE) {
                                        ed.range = null
                                        if ((region == Region.Wave || region == Region.Spec) && ed.app.settings.edit.audioClickDeselects &&
                                            ed.selection !is Selection.Note) ed.selection = Selection.None
                                        if (region == Region.Wave || region == Region.Spec) ed.cursor = downTime.coerceIn(0.0, ed.duration)
                                    }
                                    mouseAction(ed, region, downTime, action)
                                }
                                else -> onTap(ed, region, downTime, double && ed.app.settings.edit.activeTool != "cut", touch, mods.isShiftPressed)
                            }
                            cutByLastTap.value = false
                        }
                    }
                },
        ) {
            if (size != IntSize(this.size.width.toInt(), this.size.height.toInt())) {
                size = IntSize(this.size.width.toInt(), this.size.height.toInt())
            }
            ed.setViewWidth(this.size.width)
            val g = geom(size, density, layout, tierCount)
            drawTimeline(ed, g, c, doc, specImage, measurer, tierStyle, smallStyle)
        }
        // the cursor follows the mouse and the playhead moves while playing: a separate layer, so the picture
        // under them isn't drawn again for every mouse move (that is slow on long recordings seen whole)
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val g = geom(size, density, layout, tierCount)
            drawCursor(ed, g, c) { t -> ((t - ed.viewStart) * ed.pixelsPerSecond).toFloat() }
        }
        // the lock in the corner of the ruler fixes the sizes of lanes and side panels
        Box(Modifier.align(Alignment.TopEnd).padding(end = 2.dp)) {
            IconBtn(if (layout.locked) Icons.lock else Icons.unlock, Commands.lockLayout.title(), size = 22.dp, tint = if (layout.locked) c.accent else c.muted) {
                Commands.lockLayout.run(ed, ed.app)
            }
        }
        // piano roll controls in the corner of the pitch lane
        val gl = geom(size, LocalDensity.current.density, layout, tierCount)
        // arranging (View → Panels → Arrange): every lane gets its name and arrows to move it up or down
        if (ed.app.arrangePanels && !layout.overlay) {
            val d = LocalDensity.current
            val order = laneOrder(layout)
            for ((id, tb) in gl.lanes) {
                if (tb.second - tb.first < 24 * d.density) continue
                Row(
                    Modifier.offset { IntOffset((8 * d.density).toInt(), tb.first.toInt() + (4 * d.density).toInt()) }
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(c.radius)).background(c.accent.copy(alpha = 0.9f)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(LaneTitles.name(id), color = c.onAccent, fontSize = 12.sp)
                    fun move(step: Int) {
                        val o = order.toMutableList()
                        val i = o.indexOf(id)
                        val j = (i + step).coerceIn(0, o.size - 1)
                        o.removeAt(i); o.add(j, id)
                        ed.app.update { st -> st.copy(layout = st.layout.copy(laneOrder = o)) }
                    }
                    IconBtn(Icons.up, LaneTitles.up(), size = 24.dp, tint = c.onAccent) { move(-1) }
                    IconBtn(Icons.down, LaneTitles.down(), size = 24.dp, tint = c.onAccent) { move(1) }
                }
            }
        }
        if (layout.waveGain > 1.01f && gl.waveBottom - gl.waveTop > 30 * LocalDensity.current.density) {
            val d = LocalDensity.current
            Text("×" + (kotlin.math.round(layout.waveGain * 10) / 10).toString(), color = c.muted, fontSize = 11.sp,
                modifier = Modifier.offset { IntOffset((8 * d.density).toInt(), gl.waveTop.toInt() + (2 * d.density).toInt()) }
                    .clickable { ed.app.update { st -> st.copy(layout = st.layout.copy(waveGain = 1f)) } })
        }
        if (ed.mode == Mode.Labels && ed.pitch != null && gl.pitchBottom - gl.pitchTop > 40 * LocalDensity.current.density) {
            val d = LocalDensity.current
            Row(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(-(8 * d.density).toInt(), gl.pitchTop.toInt() + (4 * d.density).toInt()) }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(c.radius)).background(c.panel.copy(alpha = 0.85f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.edit, PianoTitles.pencil(), Commands.f0Pencil.keyLabel, active = ed.f0Pencil, size = 28.dp) { ed.f0Pencil = !ed.f0Pencil }
                IconBtn(Icons.undo, PianoTitles.undo(), enabled = ed.canUndoF0, size = 28.dp) { ed.undoF0() }
                IconBtn(Icons.trash, PianoTitles.reset(), enabled = ed.f0Edits != null, size = 28.dp) { ed.resetF0() }
                IconBtn(Icons.fit, PianoTitles.fit(), size = 28.dp) { ed.fitPitchRange() }
            }
        }

        // inline text editor
        val editing = ed.editingText
        val et = editing?.let { doc?.tiers?.getOrNull(it.tier) as? IntervalTier }
        if (editing != null && et != null && editing.index < et.size) {
            val g = geom(size, density, layout, tierCount)
            val x0 = ((et.startOf(editing.index) - ed.viewStart) * ed.pixelsPerSecond).toFloat()
            val x1 = ((et.endOf(editing.index) - ed.viewStart) * ed.pixelsPerSecond).toFloat()
            // a small field over the middle of the visible part of the interval
            val v0 = max(x0, 0f)
            val v1 = min(x1, size.width.toFloat())
            val w = (v1 - v0).coerceIn(64 * density, 150 * density).coerceAtMost(size.width.toFloat())
            val x = ((v0 + v1) / 2 - w / 2).coerceIn(0f, max(0f, size.width - w))
            InlineEditor(
                initial = et.texts[editing.index],
                offset = IntOffset(x.toInt(), g.tierTop(editing.tier).toInt()),
                widthPx = w.toInt(),
                heightPx = g.tierH.toInt(),
                onDraft = { ed.editingDraft = editing to it },
                onCommit = { text ->
                    // only if it wasn't already committed elsewhere (playing, another file)
                    if (ed.editingText == editing) {
                        ed.editingText = null
                        ed.setText(editing, text)
                    }
                },
                onCancel = { if (ed.editingText == editing) ed.editingText = null },
                // Enter/Esc give the keys back to the editor: Space plays the renamed phoneme
                onKeyFinish = { ed.requestFocus() },
            )
        }
    }
}

private fun hitBound(ed: EditorState, g: Geom, region: Region?, x: Float, grab: Float): BoundRef? {
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

private enum class Gesture { Click, Double, Right, Middle, Ctrl, Alt }

/** The configured action for [g] where the pointer is (label lane or audio). */
private fun mouseFor(ed: EditorState, region: Region?, g: Gesture): String {
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
private fun laneOf(ed: EditorState, region: Region?): Int = if (region is Region.Tier) region.index else ed.guideTier

private fun isLabelLane(ed: EditorState, region: Region?): Boolean =
    ed.doc?.tiers?.getOrNull(laneOf(ed, region)) is IntervalTier

private fun mouseAction(ed: EditorState, region: Region?, time: Double, action: String) {
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

private fun playUnder(ed: EditorState, region: Region?, time: Double) {
    if (ed.mode == Mode.Oto) return ed.playOtoEntry()
    val doc = ed.doc ?: return
    val k = if (region is Region.Tier) region.index else ed.guideTier
    val tier = doc.tiers.getOrNull(k) as? IntervalTier ?: return
    val i = tier.indexAt(time)
    if (i >= 0) ed.play(tier.startOf(i), tier.endOf(i), loop = false)
}

private fun onTap(ed: EditorState, region: Region?, time: Double, double: Boolean, touch: Boolean, shift: Boolean) {
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

private fun DrawScope.drawTimeline(
    ed: EditorState,
    g: Geom,
    c: Tokens,
    doc: LabelDoc?,
    specImage: ImageBitmap?,
    measurer: androidx.compose.ui.text.TextMeasurer,
    tierStyle: TextStyle,
    smallStyle: TextStyle,
) {
    val pps = ed.pixelsPerSecond
    val v0 = ed.viewStart
    fun x(t: Double) = ((t - v0) * pps).toFloat()
    val w = g.width
    val dur = ed.duration
    val px = density

    // outside the file
    val endX = x(dur)
    // ruler
    drawRect(c.panel, Offset(0f, 0f), Size(w, g.ruler))
    val minLabel = 70 * px / pps
    val step = niceStep(minLabel)
    val minor = step / 5
    var t = floor(v0 / minor) * minor
    val tEnd = v0 + w / pps
    while (t <= tEnd) {
        val xx = x(t)
        val major = abs(t / step - kotlin.math.round(t / step)) < 1e-6
        drawLine(c.muted.copy(alpha = if (major) 0.8f else 0.35f), Offset(xx, g.ruler - (if (major) 8 else 4) * px), Offset(xx, g.ruler), px)
        if (major && t >= 0) {
            val label = if (step < 1) formatTime(t) else formatTime(t, precise = false)
            safeText(measurer, label, Offset(xx + 3 * px, 2 * px), smallStyle)
        }
        t += minor
    }
    // overview of the whole file at the bottom of the ruler
    if (dur > 0) {
        val ox0 = (v0 / dur * w).toFloat().coerceIn(0f, w)
        val ox1 = ((v0 + w / pps) / dur * w).toFloat().coerceIn(0f, w)
        drawRect(c.accent.copy(alpha = 0.55f), Offset(ox0, g.ruler - 2 * px), Size(max(2 * px, ox1 - ox0), 2 * px))
    }
    drawLine(c.border, Offset(0f, g.ruler), Offset(w, g.ruler), px)

    // spectrogram
    if (g.specBottom > g.specTop) {
        if (specImage != null) {
            drawImage(
                specImage, srcOffset = IntOffset.Zero, srcSize = IntSize(specImage.width, specImage.height),
                dstOffset = IntOffset(0, g.specTop.toInt()), dstSize = IntSize(w.toInt(), (g.specBottom - g.specTop).toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        if (g.overlay) {
            val dim = g.dim
            if (dim > 0f) drawRect(c.bg.copy(alpha = dim), Offset(0f, g.specTop), Size(w, g.specBottom - g.specTop))
        }
    }

    // waveform
    val audio = ed.audio
    val peaks = ed.peaks
    if (g.waveBottom > g.waveTop) {
        val mid = (g.waveTop + g.waveBottom) / 2
        val half = (g.waveBottom - g.waveTop) / 2 * 0.92f
        // vertical zoom (Alt+wheel over the waveform); what doesn't fit is cut at the lane edges
        val amp = half * ed.app.settings.layout.waveGain.coerceIn(0.25f, 64f)
        drawLine(c.waveCenter, Offset(0f, mid), Offset(min(w, endX), mid), px)
        if (audio != null) {
            val sr = audio.sampleRate
            val samplesPerPx = sr / pps
            val path = Path()
            if (samplesPerPx < 1.5) {
                // zoomed in: connect samples
                val s0 = max(0, floor(v0 * sr).toInt())
                val s1 = min(audio.samples.size - 1, ceil((v0 + w / pps) * sr).toInt() + 1)
                var first = true
                for (s in s0..s1) {
                    val xx = x(s.toDouble() / sr)
                    val yy = (mid - audio.samples[s] * amp).coerceIn(mid - half, mid + half)
                    if (first) { path.moveTo(xx, yy); first = false } else path.lineTo(xx, yy)
                }
                // the same waveform in both views; over the spectrogram only its opacity differs
                drawPath(path, if (g.overlay) c.wave.copy(alpha = c.wave.alpha * g.waveFill) else c.wave, style = Stroke(1.2f * px))
            } else if (peaks != null) {
                val cols = min(w.toInt(), max(0, endX.toInt() + 1))
                for (col in max(0, x(0.0).toInt())..cols) {
                    val a = ((v0 + col / pps) * sr).toInt()
                    val b = ((v0 + (col + 1) / pps) * sr).toInt()
                    if (b <= 0 || a >= audio.samples.size) continue
                    val (lo, hi) = peaks.range(audio.samples, a, max(b, a + 1))
                    path.moveTo(col + 0.5f, (mid - hi * amp).coerceIn(mid - half, mid + half))
                    path.lineTo(col + 0.5f, (mid - lo * amp + 0.5f).coerceIn(mid - half, mid + half + 0.5f))
                }
                drawPath(path, if (g.overlay) c.wave.copy(alpha = c.wave.alpha * g.waveFill) else c.wave, style = Stroke(px))
            }
        }
    }
    // the lines between lanes can be dragged: a visible grip says so
    for ((ly, _, _) in g.splits) {
        if (!g.overlay) drawRect(c.border, Offset(0f, ly - px), Size(w, 2 * px))
        if (!g.locked) drawRoundRect(c.muted.copy(alpha = 0.8f), Offset(w / 2 - 18 * px, ly - 2 * px), Size(36 * px, 4 * px), androidx.compose.ui.geometry.CornerRadius(2 * px))
    }

    drawCurves(ed, g, c, measurer, smallStyle, ::x)

    // shade outside the file
    if (endX < w) drawRect(c.bg.copy(alpha = 0.6f), Offset(max(0f, endX), g.ruler), Size(w - max(0f, endX), g.height - g.ruler))
    val startX = x(0.0)
    if (startX > 0) drawRect(c.bg.copy(alpha = 0.6f), Offset(0f, g.ruler), Size(startX, g.height - g.ruler))

    // clicks found by the clean-up, marked across the audio
    val clicks = ed.cleanup.found
    if (clicks.isNotEmpty()) ed.audio?.let { au ->
        for (r in clicks) {
            val a = x(r.first.toDouble() / au.sampleRate)
            val b = x((r.last + 1).toDouble() / au.sampleRate)
            if (b < 0 || a > w) continue
            drawRect(c.danger.copy(alpha = 0.35f), Offset(a - 2 * px, g.audioTop), Size(max(b - a, 0f) + 4 * px, g.audioBottom - g.audioTop))
            drawRect(c.danger, Offset(a - 3 * px, g.audioTop), Size(max(b - a, 0f) + 6 * px, 4 * px))
        }
    }

    // range selection
    ed.range?.let { (a, b) ->
        drawRect(c.selectionRange, Offset(x(a), g.audioTop), Size(x(b) - x(a), g.audioBottom - g.audioTop))
    }

    if (ed.mode == Mode.Oto) {
        drawOto(ed, g, c, measurer, smallStyle, tierStyle, ::x)
    } else if (doc == null) return
    if (ed.mode == Mode.Oto || doc == null) return
    val sel = ed.selection
    val problemsByTier = ed.problems.groupBy { it.ref.tier }

    // guide lines of the active tier across the audio area
    val guide = doc.tiers.getOrNull(ed.guideTier) as? IntervalTier
    if (guide != null && g.audioBottom > g.audioTop) {
        val i0 = max(0, guide.indexAt(v0).let { if (it < 0) 0 else it })
        // zoomed far out boundaries would fill the picture: draw only those with some room around them
        var lastX = Float.NEGATIVE_INFINITY
        for (b in i0..guide.size) {
            val bt = guide.bounds[b]
            if (bt > tEnd) break
            val xx = x(bt)
            val selected = sel is Selection.Bound && sel.ref.tier == ed.guideTier && sel.ref.bound == b
            if (!selected && xx - lastX < 8 * px) continue
            lastX = xx
            if (g.overlay) {
                drawLine(Color.Black.copy(alpha = 0.3f), Offset(xx, g.audioTop), Offset(xx, g.audioBottom), (if (selected) 4f else 2.5f) * px)
                drawLine(if (selected) c.boundSelected else c.bound.copy(alpha = 0.85f), Offset(xx, g.audioTop), Offset(xx, g.audioBottom), if (selected) 2f * px else px)
            } else {
                // the theme decides how boundaries look over the audio
                val lw = c.boundWidth.coerceIn(0.5f, 6f) * px
                drawLine(
                    if (selected) c.boundSelected else if (c.boundLine != Color.Unspecified) c.boundLine else c.bound.copy(alpha = 0.55f),
                    Offset(xx, g.audioTop), Offset(xx, g.audioBottom),
                    if (selected) lw + px else lw,
                    pathEffect = when {
                        selected || c.boundStyle == "solid" -> null
                        c.boundStyle == "dot" -> PathEffect.dashPathEffect(floatArrayOf(lw, 2 * lw + px))
                        else -> PathEffect.dashPathEffect(floatArrayOf(4 * px + lw, 3 * px))
                    },
                )
            }
        }
        if (sel is Selection.Interval && sel.ref.tier == ed.guideTier && sel.ref.index < guide.size) {
            val a = x(guide.startOf(sel.ref.index))
            val b = x(guide.endOf(sel.ref.index))
            drawRect(c.intervalSelected.copy(alpha = c.intervalSelected.alpha * 0.45f), Offset(a, g.audioTop), Size(b - a, g.audioBottom - g.audioTop))
        }
    }

    // phoneme names over the audio, where the settings put them
    if (guide != null && g.namesOnAudio && g.audioBottom > g.audioTop) {
        val areas = if (g.overlay) listOf(min(g.waveTop, g.specTop) to max(g.waveBottom, g.specBottom))
        else listOfNotNull((g.waveTop to g.waveBottom).takeIf { it.second > it.first }, (g.specTop to g.specBottom).takeIf { it.second > it.first })
        val style = tierStyle.copy(fontSize = tierStyle.fontSize * 0.85f)
        val i0 = max(0, guide.indexAt(v0))
        for (i in i0 until guide.size) {
            val st = guide.startOf(i)
            if (st > tEnd) break
            val text = guide.texts[i]
            if (text.isEmpty()) continue
            val a = max(x(st), 0f)
            val b = min(x(guide.endOf(i)), w)
            if (b - a < 10 * px) continue
            val lay = measurer.measure(text, style, maxLines = 1, softWrap = false)
            val bw = lay.size.width + 8 * px
            val bh = lay.size.height + 2 * px
            if (bw > b - a) continue
            val bx = (a + (b - a) * g.namesX - bw / 2).coerceIn(a, b - bw)
            val selected = sel is Selection.Interval && sel.ref.tier == ed.guideTier && sel.ref.index == i
            for ((top, bottom) in areas) {
                if (bottom - top < bh + 4 * px) continue
                val by = (top + (bottom - top) * g.namesY - bh / 2).coerceIn(top + 2 * px, bottom - bh - 2 * px)
                drawRoundRect(if (selected) c.accent else c.panel.copy(alpha = 0.82f), Offset(bx, by), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(4 * px))
                drawText(lay, color = if (selected) c.onAccent else c.text, topLeft = Offset(bx + 4 * px, by + px))
            }
        }
    }

    // tiers
    for ((k, tier) in doc.tiers.withIndex()) {
        val top = g.tierTop(k)
        val bottom = top + g.tierH
        if (top >= g.height) break
        val active = k == ed.activeTier
        drawRect((if (active) c.panelAlt else c.panel).copy(alpha = if (g.overlay) 0.94f else 1f), Offset(0f, top), Size(w, g.tierH))
        drawLine(if (g.overlay) c.accent.copy(alpha = 0.6f) else c.border, Offset(0f, top), Offset(w, top), px)
        val tierColor = c.tierColors[k % c.tierColors.size]
        drawRect(tierColor, Offset(0f, top), Size(3 * px, g.tierH))
        clipRect(0f, top, w, bottom) {
            when (tier) {
                is IntervalTier -> drawIntervalTier(ed, k, tier, top, bottom, c, measurer, tierStyle, problemsByTier[k].orEmpty(), ::x, tEnd)
                is PointTier -> for (p in tier.points) {
                    if (p.time < v0 || p.time > tEnd) continue
                    val xx = x(p.time)
                    drawLine(c.bound, Offset(xx, top), Offset(xx, bottom), px)
                    safeText(measurer, p.text, Offset(xx + 3 * px, top + 4 * px), tierStyle)
                }
                is NoteTier -> for ((ni, n) in tier.notes.withIndex()) {
                    if (n.end < v0 || n.start > tEnd) continue
                    val a = x(n.start)
                    val b = x(n.end)
                    val selNote = (sel as? Selection.Note)?.let { it.tier == k && it.index == ni } == true
                    if (selNote) drawRect(c.intervalSelected, Offset(a, top), Size(b - a, g.tierH))
                    drawRect(tierColor.copy(alpha = if (n.pitch == null) 0.08f else 0.25f), Offset(a, top + 3 * px), Size(b - a, g.tierH - 6 * px))
                    drawLine(c.bound.copy(alpha = 0.5f), Offset(a, top), Offset(a, bottom), px)
                    val label = mlabeler.core.format.NoteNames.format(n.pitch) + if (n.slur) " ~" else ""
                    if (b - a > 24 * px) safeText(measurer, label, Offset(a + 4 * px, top + 4 * px), tierStyle.copy(fontSize = 11.sp))
                }
            }
            // tier name
            val name = tier.name
            val layoutName = measurer.measure(name, tierStyle.copy(fontSize = 10.sp, color = c.muted))
            drawRect(c.panel.copy(alpha = 0.85f), Offset(4 * px, bottom - layoutName.size.height - 3 * px), Size(layoutName.size.width + 6f * px, layoutName.size.height.toFloat() + 2 * px))
            drawText(layoutName, topLeft = Offset(7 * px, bottom - layoutName.size.height - 2 * px))
        }
    }
    if (doc.tiers.isNotEmpty()) drawLine(c.border, Offset(0f, g.tiersTop + g.tierH * doc.tiers.size), Offset(w, g.tiersTop + g.tierH * doc.tiers.size), px)
    drawReferences(ed, g, c, doc, measurer, tierStyle, ::x, tEnd)

    // the cursor and the playhead are drawn on their own layer (see TimelineCursor)
}

private fun DrawScope.drawIntervalTier(
    ed: EditorState,
    k: Int,
    tier: IntervalTier,
    top: Float,
    bottom: Float,
    c: Tokens,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    problems: List<Problem>,
    x: (Double) -> Float,
    tEnd: Double,
) {
    val px = density
    val sel = ed.selection
    val first = tier.indexAt(ed.viewStart).let { if (it < 0) 0 else it }
    val h = bottom - top
    val problemIdx = problems.associateBy { it.ref.index }
    // zoomed far out short phonemes are everywhere: marks are thinned out like the boundaries
    var lastMark = Float.NEGATIVE_INFINITY
    for (i in first until tier.size) {
        val s = tier.startOf(i)
        if (s > tEnd) break
        val a = x(s)
        val b = x(tier.endOf(i))
        val selected = sel is Selection.Interval && sel.ref.tier == k && sel.ref.index == i
        if (selected) {
            drawRect(c.intervalSelected, Offset(a, top), Size(b - a, h))
            // a clear frame, so the selected phoneme is easy to see
            drawRect(c.accent, Offset(a + px, top + px), Size(max(0f, b - a - 2 * px), h - 2 * px), style = Stroke(2 * px))
        }
        val text = tier.texts[i]
        if (text.isEmpty()) {
            drawRect(c.text.copy(alpha = 0.03f), Offset(a, top), Size(b - a, h))
        }
        // a phoneme squeezed between two boundaries that look like one line: a small mark shows it's there
        if (b - a < 5 * px && tier.durationOf(i) < 0.03 && (a + b) / 2 - lastMark > 12 * px) {
            val cx = (a + b) / 2
            lastMark = cx
            val tri = Path().apply { moveTo(cx - 4 * px, top); lineTo(cx + 4 * px, top); lineTo(cx, top + 6 * px); close() }
            drawPath(tri, c.warn)
            val tri2 = Path().apply { moveTo(cx - 4 * px, bottom); lineTo(cx + 4 * px, bottom); lineTo(cx, bottom - 6 * px); close() }
            drawPath(tri2, c.warn)
        }
        val p = problemIdx[i]
        if (p != null) {
            val col = if (p.severity == Severity.Error) c.danger else c.warn
            drawRect(col, Offset(a + px, bottom - 3 * px), Size(max(0f, b - a - 2 * px), 2 * px))
        }
        val conf = tier.confidenceOf(i)
        if (conf != null && p?.kind != Problem.Kind.LowConfidence) {
            drawRect(c.ok.copy(alpha = 0.5f), Offset(a + px, bottom - 2 * px), Size(max(0f, (b - a - 2 * px) * conf), px))
        }
        // when zoomed in an interval can be far wider than the screen: centre the text in its visible part
        val va = max(a, 0f)
        val vb = min(b, size.width)
        val width = vb - va - 6 * px
        if (text.isNotEmpty() && width > 6 * px) {
            val layout = measurer.measure(
                text, style, overflow = TextOverflow.Clip, maxLines = 1, softWrap = false,
                constraints = Constraints(maxWidth = width.toInt().coerceIn(1, 100_000)),
            )
            val tx = va + (vb - va - layout.size.width) / 2
            drawText(layout, topLeft = Offset(max(va + 3 * px, tx), top + (h - layout.size.height) / 2 - 2 * px))
        }
    }
    // boundaries (dense ones thinned out when zoomed far out)
    var lastX = Float.NEGATIVE_INFINITY
    val last = tier.bounds.size - 1
    for (bIdx in first..last) {
        val t = tier.bounds[bIdx]
        if (t > tEnd) break
        val xx = x(t)
        val selected = sel is Selection.Bound && sel.ref.tier == k && sel.ref.bound == bIdx
        if (!selected && xx - lastX < 3 * px) continue
        lastX = xx
        drawLine(if (selected) c.boundSelected else c.bound, Offset(xx, top), Offset(xx, bottom), if (selected) 3 * px else px)
    }
}

@Composable
private fun InlineEditor(initial: String, offset: IntOffset, widthPx: Int, heightPx: Int, onDraft: (String) -> Unit, onCommit: (String) -> Unit, onCancel: () -> Unit, onKeyFinish: () -> Unit = {}) {
    val c = T.c
    val density = LocalDensity.current
    var value by remember(initial) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun finish(commit: Boolean) {
        if (done) return
        done = true
        if (commit) onCommit(value.text.trim()) else onCancel()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) { onDraft(initial) }
    with(density) {
        BasicTextField(
            value = value,
            onValueChange = { value = it; onDraft(it.text) },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = T.font),
            cursorBrush = SolidColor(c.accent),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { finish(true); onKeyFinish() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier
                .offset { offset }
                .size(widthPx.toDp(), heightPx.toDp())
                .background(c.bg)
                .border(2.dp, c.accent)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (focused && !it.isFocused) finish(true)
                    focused = it.isFocused
                }
                .onPreviewKeyEvent {
                    when (it.key) {
                        Key.Escape -> { finish(false); onKeyFinish(); true }
                        Key.Enter, Key.NumPadEnter -> { finish(true); onKeyFinish(); true }
                        else -> false
                    }
                },
        )
    }
}

@Suppress("unused")
private fun dbLabel(v: Double) = "${(20 * log10(v)).toInt()} dB"

/**
 * Draws one line of text at [topLeft]. Measures without width limits first: drawText(measurer, String, …)
 * derives constraints from the space left in the canvas and throws when the text starts past its right edge.
 */
private fun DrawScope.safeText(measurer: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (text.isEmpty() || topLeft.x >= size.width || topLeft.y >= size.height) return
    val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
    drawText(layout, topLeft = topLeft)
}

private fun EditorState.laneTiers() = if (mode == Mode.Oto) 0 else (doc?.tiers?.size ?: 0) + referenceTiers.size

private fun DrawScope.drawCursor(ed: EditorState, g: Geom, c: Tokens, x: (Double) -> Float) {
    val px = density
    ed.cursor?.let { cur -> val xx = x(cur); if (xx in 0f..size.width) drawLine(c.cursor, Offset(xx, g.ruler), Offset(xx, g.height), px) }
    ed.playhead?.let { p -> val xx = x(p); if (xx in 0f..size.width) drawLine(c.playhead, Offset(xx, 0f), Offset(xx, g.height), 2 * px) }
}

/** Marker colours follow the usual oto editor convention. */
private val otoColors = mapOf(
    mlabeler.core.format.OtoMarker.Left to Color(0xFF4F8DFF),
    mlabeler.core.format.OtoMarker.Overlap to Color(0xFF3CC47C),
    mlabeler.core.format.OtoMarker.Preutterance to Color(0xFFFF4D5E),
    mlabeler.core.format.OtoMarker.Consonant to Color(0xFFFF8FD0),
    mlabeler.core.format.OtoMarker.Right to Color(0xFF4F8DFF),
)

private fun hitOto(ed: EditorState, g: Geom, region: Region?, x: Float, grab: Float): mlabeler.core.format.OtoMarker? {
    if (ed.mode != Mode.Oto || (region != Region.Wave && region != Region.Spec)) return null
    val e = ed.oto.current() ?: return null
    val a = ed.oto.absolute(e)
    // preutterance first: it is the one people grab most
    val order = listOf(mlabeler.core.format.OtoMarker.Preutterance, mlabeler.core.format.OtoMarker.Overlap, mlabeler.core.format.OtoMarker.Consonant,
        mlabeler.core.format.OtoMarker.Left, mlabeler.core.format.OtoMarker.Right)
    return order.minByOrNull { m -> abs(((a.get(m) / 1000 - ed.viewStart) * ed.pixelsPerSecond).toFloat() - x) }
        ?.takeIf { m -> abs(((a.get(m) / 1000 - ed.viewStart) * ed.pixelsPerSecond).toFloat() - x) <= grab }
}

private fun DrawScope.drawOto(
    ed: EditorState, g: Geom, c: Tokens, measurer: androidx.compose.ui.text.TextMeasurer,
    small: TextStyle, big: TextStyle, x: (Double) -> Float,
) {
    val px = density
    val top = g.ruler
    val bottom = g.specBottom
    val h = bottom - top
    val sel = ed.oto.selected
    // other entries of this file: a faint preutterance line with the alias
    for ((i, e) in ed.oto.entriesOfItem()) {
        if (i == sel) continue
        val a = ed.oto.absolute(e)
        val xx = x(a.preutterance / 1000)
        if (xx < -50 || xx > size.width + 50) continue
        drawLine(c.muted.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, bottom), px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * px, 4 * px)))
        safeText(measurer, e.alias, Offset(xx + 3 * px, bottom - 16 * px), small)
    }
    val e = ed.oto.current() ?: return
    val a = ed.oto.dragPreview ?: ed.oto.absolute(e)
    // the same entry from the compared oto.ini, dashed
    ed.oto.referenceFor(e)?.let { r ->
        val ra = ed.oto.absolute(r)
        for ((m, col) in otoColors) {
            val xx = x(ra.get(m) / 1000)
            drawLine(col.copy(alpha = 0.8f), Offset(xx, top), Offset(xx, bottom), 1.5f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * px, 5 * px)))
        }
    }
    val shade = Color.Black.copy(alpha = if (c.dark) 0.5f else 0.25f)
    val l = x(a.left / 1000)
    val r = x(a.right / 1000)
    val k = x(a.consonant / 1000)
    drawRect(shade, Offset(0f, top), Size(max(0f, l), h))
    drawRect(shade, Offset(r, top), Size(max(0f, size.width - r), h))
    drawRect(otoColors.getValue(mlabeler.core.format.OtoMarker.Consonant).copy(alpha = 0.16f), Offset(l, top), Size(max(0f, k - l), h))
    // each marker named at its own height, so close markers stay readable
    val names = listOf(
        mlabeler.core.format.OtoMarker.Left to "Offset", mlabeler.core.format.OtoMarker.Overlap to "Ovl",
        mlabeler.core.format.OtoMarker.Preutterance to "Preu", mlabeler.core.format.OtoMarker.Consonant to "Fixed",
        mlabeler.core.format.OtoMarker.Right to "Cutoff",
    )
    val labelTop = top + 28 * px
    for ((n, pair) in names.withIndex()) {
        val (m, name) = pair
        val col = otoColors.getValue(m)
        val xx = x(a.get(m) / 1000)
        val ly = labelTop + (n % 5) * 16 * px
        drawLine(col, Offset(xx, ly + 14 * px), Offset(xx, bottom), if (m == mlabeler.core.format.OtoMarker.Preutterance) 2.5f * px else 1.5f * px)
        val lay = measurer.measure(name, small.copy(color = col, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        val tx = if (m == mlabeler.core.format.OtoMarker.Right) xx - lay.size.width - 3 * px else xx + 3 * px
        drawRect(c.bg.copy(alpha = 0.7f), Offset(tx - 2 * px, ly), Size(lay.size.width + 4f * px, lay.size.height.toFloat()))
        drawText(lay, topLeft = Offset(tx, ly))
    }
    safeText(measurer, e.alias, Offset(l + 6 * px, top + 6 * px), big.copy(color = c.text))
}

private val pitchColor = Color(0xFF39E1FF)

/** Pitch (own lane or over the spectrogram) and power curves. */
private fun DrawScope.drawCurves(ed: EditorState, g: Geom, c: Tokens, measurer: androidx.compose.ui.text.TextMeasurer, small: TextStyle, x: (Double) -> Float) {
    val layout = ed.app.settings.layout
    val px = density
    val w = size.width
    val v0 = ed.viewStart
    val v1 = v0 + w / ed.pixelsPerSecond
    val pitch = ed.pitchCurve
    if (layout.showPitch && pitch != null) {
        val over = layout.pitchOverSpectrogram && g.specBottom > g.specTop
        val top = if (over) g.specTop else g.pitchTop
        val bottom = if (over) g.specBottom else g.pitchBottom
        if (bottom > top) {
            val y: (Double) -> Float
            if (over) {
                val spec = ed.spectrogram
                val maxF = min(ed.app.settings.view.maxFreq.toDouble(), spec?.maxFreq ?: 8000.0)
                val melTop = Spectrogram.hzToMel(maxF)
                y = { hz -> (bottom - Spectrogram.hzToMel(hz) / melTop * (bottom - top)).toFloat() }
            } else {
                drawRect(c.laneBg, Offset(0f, top), Size(w, bottom - top))
                // a piano roll: one row per semitone in the sung range, keys on the left
                val lo = ed.pitchLo
                val hi = ed.pitchHi
                val row = ((bottom - top) / (hi - lo)).toFloat()
                y = { hz -> (bottom - (mlabeler.core.dsp.Pitch.hzToMidi(hz) - lo) / (hi - lo) * (bottom - top)).toFloat() }
                val keyW = PIANO_KEYS * px
                drawRect(Color(0xFFE6E6EA), Offset(0f, top), Size(keyW, bottom - top))
                clipRect(0f, top, w, bottom) {
                for (m in kotlin.math.floor(lo).toInt()..kotlin.math.ceil(hi).toInt()) {
                    val yc = (bottom - (m - lo) / (hi - lo) * (bottom - top)).toFloat()
                    val black = (m % 12) in setOf(1, 3, 6, 8, 10)
                    if (black && row >= 3 * px) drawRect(c.text.copy(alpha = 0.035f), Offset(keyW, yc - row / 2), Size(w - keyW, row))
                    val octave = m % 12 == 0
                    if (octave || row >= 5 * px) drawLine(c.text.copy(alpha = if (octave) 0.16f else 0.04f), Offset(keyW, yc + row / 2), Offset(w, yc + row / 2), px)
                    // the keys: black ones shorter, a thin line between white ones
                    if (black && row >= 2 * px) drawRect(Color(0xFF26262C), Offset(0f, yc - row / 2), Size(keyW * 0.62f, row))
                    else if (row >= 3 * px && (m % 12 == 4 || m % 12 == 11)) drawLine(Color(0xFF9A9AA2), Offset(0f, yc - row / 2), Offset(keyW, yc - row / 2), px)
                    if (octave && row * 3 >= 11 * px) safeText(measurer, "C${m / 12 - 1}", Offset(keyW + 3 * px, yc - 7 * px), small)
                }
                }
                drawLine(c.border, Offset(keyW, top), Offset(keyW, bottom), px)
                drawLine(c.border, Offset(0f, top), Offset(w, top), px)
            }
            val path = Path()
            var pen = false
            val i0 = max(0, (v0 / pitch.hop).toInt() - 1)
            val i1 = min(pitch.values.size - 1, (v1 / pitch.hop).toInt() + 1)
            for (i in i0..i1) {
                val f = pitch.values[i]
                if (f <= 0f || f.isNaN()) { pen = false; continue }
                val xx = x(i * pitch.hop)
                val yy = y(f.toDouble()).coerceIn(top, bottom)
                if (pen) path.lineTo(xx, yy) else path.moveTo(xx, yy)
                pen = true
            }
            if (over) drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(3.5f * px))
            drawPath(path, pitchColor, style = Stroke(1.8f * px))
            // the hand-drawn parts stand out; the analysed curve under them stays visible, faint
            val edits = ed.f0Edits
            val analysed = ed.pitch
            if (edits != null && analysed != null) {
                val under = Path()
                val drawn = Path()
                var penU = false
                var penD = false
                for (i in i0..i1) {
                    val e = edits.getOrNull(i) ?: Float.NaN
                    val xx = x(i * pitch.hop)
                    if (e.isNaN()) { penU = false; penD = false; continue }
                    val f = analysed.values.getOrNull(i) ?: 0f
                    if (f > 0f) { val yy = y(f.toDouble()).coerceIn(top, bottom); if (penU) under.lineTo(xx, yy) else under.moveTo(xx, yy); penU = true } else penU = false
                    if (e > 0f) { val yy = y(e.toDouble()).coerceIn(top, bottom); if (penD) drawn.lineTo(xx, yy) else drawn.moveTo(xx, yy); penD = true } else penD = false
                }
                drawPath(under, pitchColor.copy(alpha = 0.35f), style = Stroke(px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * px, 3 * px))))
                drawPath(drawn, c.accent, style = Stroke(2.4f * px))
            }
            // notes as bars at their pitch, so they can be checked against the curve
            val notes = ed.doc?.tiers?.firstOrNull { it is NoteTier } as? NoteTier
            if (notes != null) {
                val selNote = ed.selection as? Selection.Note
                val noteK = ed.doc?.tiers?.indexOf(notes) ?: -1
                for ((ni, n) in notes.notes.withIndex()) {
                    val m = n.pitch ?: continue
                    if (n.end < v0 || n.start > v1) continue
                    val yy = y(440.0 * kotlin.math.exp((m - 69) / 12.0 * kotlin.math.ln(2.0)))
                    if (yy < top || yy > bottom) continue
                    val isSel = selNote != null && selNote.tier == noteK && selNote.index == ni
                    val col = if (isSel) c.boundSelected else Color(0xFFFFFFFF)
                    // in the piano roll a note is one semitone high
                    val bh = if (over) 6 * px else max(6 * px, ((bottom - top) / (ed.pitchHi - ed.pitchLo)).toFloat() - 2 * px)
                    drawRoundRect(col.copy(alpha = if (isSel) 0.9f else 0.5f), Offset(x(n.start), yy - bh / 2), Size(x(n.end) - x(n.start), bh),
                        androidx.compose.ui.geometry.CornerRadius(2 * px))
                    safeText(measurer, mlabeler.core.format.NoteNames.format(m), Offset(x(n.start) + 2 * px, yy - 18 * px), small.copy(color = col))
                }
            }
        }
    }
    val power = ed.power
    if (layout.showPower && power != null && g.powerBottom > g.powerTop) {
        val top = g.powerTop
        val bottom = g.powerBottom
        drawRect(c.laneBg, Offset(0f, top), Size(w, bottom - top))
        drawLine(c.border, Offset(0f, top), Offset(w, top), px)
        val path = Path()
        val i0 = max(0, (v0 / power.hop).toInt() - 1)
        val i1 = min(power.values.size - 1, (v1 / power.hop).toInt() + 1)
        path.moveTo(x(i0 * power.hop), bottom)
        for (i in i0..i1) {
            val db = power.values[i].coerceIn(-60f, 0f)
            path.lineTo(x(i * power.hop), bottom - (db + 60f) / 60f * (bottom - top))
        }
        path.lineTo(x(i1 * power.hop), bottom)
        path.close()
        drawPath(path, c.wave.copy(alpha = 0.45f))
        safeText(measurer, "dB", Offset(4 * px, top + 2 * px), small)
    }
}

/** Reference tiers: read-only, boundaries coloured by how far they are from the edited ones. */
private fun DrawScope.drawReferences(
    ed: EditorState, g: Geom, c: Tokens, doc: LabelDoc, measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle, x: (Double) -> Float, tEnd: Double,
) {
    val px = density
    val w = size.width
    for ((n, pair) in ed.referenceTiers.withIndex()) {
        val (ri, ref) = pair
        val k = doc.tiers.size + n
        val top = g.tierTop(k)
        val bottom = top + g.tierH
        if (top >= size.height) break
        drawRect(c.bg.copy(alpha = if (g.overlay) 0.94f else 1f), Offset(0f, top), Size(w, g.tierH))
        drawLine(c.border, Offset(0f, top), Offset(w, top), px)
        val main = mlabeler.core.check.Compare.counterpart(doc, ref)
        val deltas = main?.let { mlabeler.core.check.Compare.boundDeltas(it, ref) }
        val mism = main?.let { mlabeler.core.check.Compare.textMismatch(it, ref) }
        clipRect(0f, top, w, bottom) {
            val first = ref.indexAt(ed.viewStart).let { if (it < 0) 0 else it }
            for (i in first until ref.size) {
                if (ref.startOf(i) > tEnd) break
                val a = x(ref.startOf(i))
                val b = x(ref.endOf(i))
                if (mism != null && mism[i]) drawRect(c.danger.copy(alpha = 0.16f), Offset(a, top), Size(b - a, g.tierH))
                val t = ref.texts[i]
                val va = max(a, 0f)
                val vb = min(b, size.width)
                if (t.isNotEmpty() && vb - va > 8 * px) {
                    val layout = measurer.measure(t, style.copy(color = c.muted), maxLines = 1, softWrap = false,
                        constraints = Constraints(maxWidth = (vb - va - 6 * px).toInt().coerceIn(1, 100_000)))
                    drawText(layout, topLeft = Offset(va + (vb - va - layout.size.width) / 2, top + (g.tierH - layout.size.height) / 2 - 2 * px))
                }
            }
            var lastX = Float.NEGATIVE_INFINITY
            for (bi in first..ref.size) {
                val t = ref.bounds[bi]
                if (t > tEnd) break
                if (x(t) - lastX < 3 * px) continue
                lastX = x(t)
                val d = deltas?.getOrNull(bi)?.times(1000) ?: 0.0
                val col = when {
                    deltas == null -> c.muted
                    d < 10 -> c.ok
                    d < 30 -> c.warn
                    else -> c.danger
                }
                drawLine(col, Offset(x(t), top), Offset(x(t), bottom), 1.5f * px)
            }
            val label = ed.references[ri].name + " · " + ref.name
            val lay = measurer.measure(label, style.copy(fontSize = 10.sp, color = c.muted))
            drawRect(c.bg.copy(alpha = 0.85f), Offset(4 * px, bottom - lay.size.height - 3 * px), Size(lay.size.width + 6 * px, lay.size.height + 2 * px))
            drawText(lay, topLeft = Offset(7 * px, bottom - lay.size.height - 2 * px))
        }
    }
}

/** A note border near [x] in the notes tier [k]: (tier, note, is it the start). */
private fun hitNote(ed: EditorState, k: Int, x: Float, grab: Float): Triple<Int, Int, Boolean>? {
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
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitAnyButtonDown(): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
        if (ch != null) return ch
    }
}


object PianoTitles {
    val pencil = mlabeler.app.i18n.L("Draw the pitch: left button draws, right button erases the drawing",
        "Рисовать высоту тона: левая кнопка рисует, правая стирает нарисованное")
    val undo = mlabeler.app.i18n.L("Undo the last stroke", "Отменить последний штрих")
    val reset = mlabeler.app.i18n.L("Back to the pitch of the recording", "Вернуть высоту тона записи")
    val fit = mlabeler.app.i18n.L("Fit to the singing (Alt+wheel moves, Ctrl+Alt+wheel zooms)", "Подогнать под пение (Alt+колесо — сдвиг, Ctrl+Alt+колесо — масштаб)")
}

object LaneTitles {
    private val names = mapOf(
        "wave" to mlabeler.app.i18n.L("Waveform", "Волна"),
        "spec" to mlabeler.app.i18n.L("Spectrogram", "Спектрограмма"),
        "pitch" to mlabeler.app.i18n.L("Pitch", "Высота тона"),
        "power" to mlabeler.app.i18n.L("Loudness", "Громкость"),
        "labels" to mlabeler.app.i18n.L("Labels", "Разметка"),
    )
    fun name(id: String) = names[id]?.invoke() ?: id
    val up = mlabeler.app.i18n.L("Move the lane up", "Полосу выше")
    val down = mlabeler.app.i18n.L("Move the lane down", "Полосу ниже")
}
