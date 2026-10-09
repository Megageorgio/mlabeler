package mlabeler.app.ui

import androidx.compose.ui.graphics.graphicsLayer

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
internal const val PIANO_KEYS = 22f

internal sealed interface Region {
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
internal class Geom(
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

internal fun geom(size: IntSize, density: Float, layout: LayoutSettings, tiers: Int): Geom {
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
internal fun lut(colors: List<Color>, brightness: Float, contrast: Float): IntArray {
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

internal fun renderSpectrogram(spec: Spectrogram, viewStart: Double, pps: Double, widthPx: Int, maxFreq: Float, lut: IntArray): ImageBitmap? {
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

internal fun niceStep(minSeconds: Double): Double {
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

    // a new file fades in instead of popping, and its spectrogram fades in once it is there (Settings → Interface →
    // Animations; at once when they are off)
    val motionOn = Motion.on()
    val fadeMs = Motion.ms(180)
    val fileAppear = remember(ed.item?.id) { androidx.compose.animation.core.Animatable(if (motionOn) 0.35f else 1f) }
    androidx.compose.runtime.LaunchedEffect(ed.item?.id) { fileAppear.animateTo(1f, androidx.compose.animation.core.tween(fadeMs)) }
    val specAppear = remember(spec != null, ed.item?.id) { androidx.compose.animation.core.Animatable(if (motionOn && spec == null) 0f else 1f) }
    val specMs = Motion.ms(260)
    androidx.compose.runtime.LaunchedEffect(spec != null, ed.item?.id) { if (spec != null) specAppear.animateTo(1f, androidx.compose.animation.core.tween(specMs)) }

    BoxWithConstraints(modifier.background(c.laneBg).graphicsLayer { alpha = fileAppear.value }) {
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
                                    val hb = if (ed.app.settings.hoverBoundary && ed.mode == Mode.Labels) hitBound(ed, g, r, ch.position.x, 6 * density) else null
                                    if (hb != ed.hoverBound) ed.hoverBound = hb
                                    hoverIcon = when {
                                        r is Region.LaneSplit -> PointerIcon.Hand
                                        hitBound(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        hitOto(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        // the selected part can be dragged with its boundaries
                                        (r == Region.Wave || r == Region.Spec) && ed.mode == Mode.Labels &&
                                            ed.range?.let { (a, b) -> t > a && t < b } == true -> PointerIcon.Hand
                                        else -> PointerIcon.Default
                                    }
                                }
                                PointerEventType.Exit -> if (ch.type == PointerType.Mouse) { ed.cursor = null; ed.hoverBound = null }
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

                        // the loudness lane with the pencil: the drawn line is the new loudness, right button erases
                        if (ed.dynPencil && ed.power != null && ed.mode == Mode.Labels && g.powerBottom > g.powerTop &&
                            down.position.y in g.powerTop..g.powerBottom && !first.buttons.isTertiaryPressed) {
                            val top = g.powerTop
                            val bottom = g.powerBottom
                            fun dbAt(yy: Float) = (bottom - yy.coerceIn(top, bottom)) / (bottom - top) * 60f - 60f
                            val erase = first.buttons.isSecondaryPressed
                            ed.beginGainStroke()
                            var lt = downTime
                            var ld = dbAt(down.position.y)
                            ed.drawGain(lt, if (erase) null else ld, lt, if (erase) null else ld)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    val t = timeAt(chg.position.x)
                                    val d = dbAt(chg.position.y)
                                    ed.drawGain(lt, if (erase) null else ld, t, if (erase) null else d)
                                    lt = t
                                    ld = d
                                    ed.cursor = t
                                    chg.consume()
                                }
                            }
                            ed.endGainStroke()
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
                            if (ed.vuvTool) {
                                // left button: unvoiced, right button: voiced again
                                val voiced = first.buttons.isSecondaryPressed
                                ed.beginF0Stroke()
                                var lt = downTime
                                ed.setVoicing(lt, lt, voiced)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!chg.pressed) break
                                    if (chg.positionChange() != Offset.Zero) {
                                        val t = timeAt(chg.position.x)
                                        ed.setVoicing(lt, t, voiced)
                                        lt = t
                                        ed.cursor = t
                                        chg.consume()
                                    }
                                }
                                ed.endF0Stroke()
                                return@awaitEachGesture
                            }
                            if (ed.f0Pencil) {
                                val erase = first.buttons.isSecondaryPressed
                                ed.beginF0Stroke()
                                // Shift: the drawn line keeps to the notes of the song's key
                                val key = ed.detectedKey()
                                fun snap(m: Double, shift: Boolean) = if (shift && key != null) ed.snapToKey(m, key) else m
                                var lt = downTime
                                var lm = snap(midiAt(down.position.y), mods.isShiftPressed)
                                ed.drawF0(lt, if (erase) null else lm, lt, if (erase) null else lm)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!chg.pressed) break
                                    if (chg.positionChange() != Offset.Zero) {
                                        val t = timeAt(chg.position.x)
                                        val m = snap(midiAt(chg.position.y.coerceIn(top, bottom)), ev.keyboardModifiers.isShiftPressed)
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
                            val hitI = if (nt == null) -1 else nt.notes.indexOfFirst { n ->
                                n.pitch != null && downTime in n.start..n.end && abs(yOf(n.pitch!!) - down.position.y) <= max(rowH / 2, 6 * density)
                            }
                            if (nt != null && hitI >= 0) {
                                val n = nt.notes[hitI]
                                val edge = when {
                                    abs(down.position.x - ((n.start - ed.viewStart) * ed.pixelsPerSecond).toFloat()) <= grab -> true
                                    abs(down.position.x - ((n.end - ed.viewStart) * ed.pixelsPerSecond).toFloat()) <= grab -> false
                                    else -> null
                                }
                                // right click: on a border joins the two notes, inside puts the pitch back to the sung one
                                if (!touch && first.buttons.isSecondaryPressed) {
                                    when (edge) {
                                        true -> ed.mergeNotes(noteK, hitI - 1)
                                        false -> ed.mergeNotes(noteK, hitI)
                                        null -> ed.restoreNotePitch(noteK, hitI)
                                    }
                                    return@awaitEachGesture
                                }
                                // Ctrl+click inside a note cuts it there (the second part is a slur)
                                val ctrlDown = if (Platform.isMac) mods.isMetaPressed else mods.isCtrlPressed
                                if (!touch && ctrlDown && edge == null) {
                                    ed.splitNoteAt(noteK, downTime)
                                    return@awaitEachGesture
                                }
                                val key = ed.detectedKey()
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
                                            // semitones; with Alt in cents; with Shift only the notes of the key
                                            val km = ev.keyboardModifiers
                                            ed.notePitchDragTo(noteK, hitI, when {
                                                km.isAltPressed -> kotlin.math.round(raw * 100) / 100
                                                km.isShiftPressed && key != null -> ed.snapToKey(raw, key)
                                                else -> kotlin.math.round(raw)
                                            })
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

                        // a press inside the selected part (on the audio) drags all boundaries in it together
                        val inRange = ed.range?.let { (a, b) -> downTime > a && downTime < b } == true
                        if (!touch && !pan && inRange && ed.mode == Mode.Labels && (region == Region.Wave || region == Region.Spec) &&
                            !mods.isShiftPressed && ed.beginGroupDrag()) {
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (!moved && hypot((chg.position - down.position).x, (chg.position - down.position).y) <= viewConfiguration.touchSlop) continue
                                moved = true
                                ed.groupDragBy(timeAt(chg.position.x) - downTime)
                                ed.cursor = timeAt(chg.position.x)
                                chg.consume()
                            }
                            ed.endGroupDrag()
                            if (moved) return@awaitEachGesture
                            // a click without moving: as any click (clears the part, then does what a click is set to do)
                            ed.range = null
                            val act = mouseFor(ed, region, Gesture.Click)
                            if (act != null && act != MouseActions.SELECT && act != MouseActions.RENAME && act != MouseActions.DELETE) {
                                ed.cursor = downTime.coerceIn(0.0, ed.duration)
                                if (ed.app.settings.edit.audioClickDeselects && ed.selection !is Selection.Note) ed.selection = Selection.None
                                mouseAction(ed, region, downTime, act)
                            } else onTap(ed, region, downTime, false, false, false)
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
            drawTimeline(ed, g, c, doc, specImage, measurer, tierStyle, smallStyle, specAppear.value)
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
                        .clip(mlabeler.app.theme.RoundedCornerShape(c.radius)).background(c.accent.copy(alpha = 0.9f)).padding(horizontal = 6.dp),
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
                    .clip(mlabeler.app.theme.RoundedCornerShape(c.radius)).background(c.panel.copy(alpha = 0.85f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.edit, PianoTitles.pencil(), Commands.f0Pencil.keyLabel, active = ed.f0Pencil, size = 28.dp) { ed.f0Pencil = !ed.f0Pencil; ed.vuvTool = false }
                Tip(PianoTitles.vuv()) {
                    Text("V/UV", color = if (ed.vuvTool) c.onAccent else c.text, fontSize = 11.sp,
                        modifier = Modifier.clip(mlabeler.app.theme.RoundedCornerShape(c.radius)).background(if (ed.vuvTool) c.accent else Color.Transparent)
                            .clickable { ed.vuvTool = !ed.vuvTool; ed.f0Pencil = false }.padding(horizontal = 6.dp, vertical = 6.dp))
                }
                IconBtn(Icons.undo, PianoTitles.undo(), enabled = ed.canUndoF0, size = 28.dp) { ed.undoF0() }
                IconBtn(Icons.trash, PianoTitles.reset(), enabled = ed.f0Edits != null, size = 28.dp) { ed.resetF0() }
                IconBtn(Icons.fit, PianoTitles.fit(), size = 28.dp) { ed.fitPitchRange() }
            }
        }
        if (ed.mode == Mode.Labels && ed.power != null && gl.powerBottom - gl.powerTop > 40 * LocalDensity.current.density) {
            val d = LocalDensity.current
            Row(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(-(8 * d.density).toInt(), gl.powerTop.toInt() + (4 * d.density).toInt()) }
                    .clip(mlabeler.app.theme.RoundedCornerShape(c.radius)).background(c.panel.copy(alpha = 0.85f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.edit, PianoTitles.dynPencil(), active = ed.dynPencil, size = 28.dp) { ed.dynPencil = !ed.dynPencil }
                IconBtn(Icons.undo, PianoTitles.undo(), enabled = ed.canUndoGain, size = 28.dp) { ed.undoGain() }
                IconBtn(Icons.trash, PianoTitles.dynReset(), enabled = ed.gainEdits != null, size = 28.dp) { ed.resetGain() }
                IconBtn(Icons.play, PianoTitles.dynListen(), enabled = ed.gainEdits != null, size = 28.dp) {
                    ed.cleanup.previewDrawnLoudness(ed.range ?: (ed.viewStart to ed.viewStart + ed.visibleDuration))
                }
                IconBtn(Icons.check, PianoTitles.dynApply(), enabled = ed.gainEdits != null && !ed.cleanup.busy, size = 28.dp) { ed.cleanup.applyDrawnLoudness() }
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


@Composable
internal fun InlineEditor(initial: String, offset: IntOffset, widthPx: Int, heightPx: Int, onDraft: (String) -> Unit, onCommit: (String) -> Unit, onCancel: () -> Unit, onKeyFinish: () -> Unit = {}) {
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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

object PianoTitles {
    val pencil = mlabeler.app.i18n.L("Draw the pitch: left button draws, right button erases the drawing",
        "Рисовать высоту тона: левая кнопка рисует, правая стирает нарисованное")
    val vuv = mlabeler.app.i18n.L("Voiced or not: the left button marks a part unvoiced, the right button voiced again",
        "Звонкое или глухое: левая кнопка делает участок глухим, правая — снова звонким")
    val dynPencil = mlabeler.app.i18n.L("Draw the loudness: the line is the new loudness, the right button erases the drawing",
        "Рисовать громкость: линия — новая громкость, правая кнопка стирает нарисованное")
    val dynReset = mlabeler.app.i18n.L("Forget the drawn loudness", "Убрать нарисованную громкость")
    val dynListen = mlabeler.app.i18n.L("Listen with the drawn loudness (the selection or what is on screen)", "Прослушать с нарисованной громкостью (выделенное или видимое)")
    val dynApply = mlabeler.app.i18n.L("Write the drawn loudness into the recording (Ctrl+Z undoes it)", "Записать нарисованную громкость в звук (отмена — Ctrl+Z)")
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
