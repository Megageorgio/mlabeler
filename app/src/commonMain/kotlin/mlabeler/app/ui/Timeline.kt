package mlabeler.app.ui

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

private sealed interface Region {
    data object Ruler : Region
    data object Wave : Region
    data object Spec : Region
    data class Tier(val index: Int) : Region
    data object WaveSpecSplit : Region
    data object TierSplit : Region
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
) {
    fun region(y: Float, grab: Float): Region? {
        if (y < ruler) return Region.Ruler
        if (waveBottom > waveTop && specBottom > specTop && abs(y - waveBottom) < grab / 2) return Region.WaveSpecSplit
        if (tierCount > 0 && specBottom > ruler && abs(y - tiersTop) < grab / 2) return Region.TierSplit
        if (y in waveTop..waveBottom) return Region.Wave
        if (y in specTop..specBottom) return Region.Spec
        if (y >= tiersTop) {
            val k = ((y - tiersTop) / tierH).toInt()
            if (k in 0 until tierCount) return Region.Tier(k)
        }
        return null
    }

    fun tierTop(k: Int) = tiersTop + k * tierH
}

private fun geom(size: IntSize, density: Float, layout: LayoutSettings, tiers: Int): Geom {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val ruler = 22f * density
    val tierH = layout.tierHeight.coerceIn(28f, 96f) * density * (if (Platform.isMobile) 1.15f else 1f)
    var tiersH = tierH * tiers
    val minAudio = 48f * density
    val audioH = max(minAudio, h - ruler - tiersH)
    tiersH = h - ruler - audioH
    val tierHeight = if (tiers > 0) min(tierH, tiersH / tiers) else tierH
    val showW = layout.showWaveform
    val showS = layout.showSpectrogram
    val waveH = when {
        showW && showS -> audioH * layout.waveShare.coerceIn(0.1f, 0.9f)
        showW -> audioH
        else -> 0f
    }
    val waveTop = ruler
    val waveBottom = ruler + waveH
    val specTop = waveBottom
    val specBottom = if (showS || !showW) ruler + audioH else waveBottom
    return Geom(w, h, ruler, waveTop, waveBottom, specTop, specBottom, ruler + audioH, tierHeight, tiers)
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

    // spectrogram image for the current view
    val spec = ed.spectrogram
    val specImage = remember(spec, ed.spectrogramProgress, ed.viewStart, ed.pixelsPerSecond, size.width, view.maxFreq, colorLut, layout.showSpectrogram) {
        if (spec == null || !layout.showSpectrogram) null
        else renderSpectrogram(spec, ed.viewStart, ed.pixelsPerSecond, size.width, view.maxFreq, colorLut)
    }

    BoxWithConstraints(modifier.background(c.laneBg)) {
        val tierStyle = TextStyle(fontSize = if (Platform.isMobile) 15.sp else 13.sp, color = c.tierText, fontFamily = if (c.mono) FontFamily.Monospace else FontFamily.Default)
        val smallStyle = TextStyle(fontSize = 10.sp, color = c.muted, fontFamily = if (c.mono) FontFamily.Monospace else FontFamily.Default)

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
                                        r == Region.WaveSpecSplit || r == Region.TierSplit -> PointerIcon.Hand
                                        hitBound(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        hitOto(ed, g, r, ch.position.x, 6 * density) != null -> resizeHorizontalIcon
                                        else -> PointerIcon.Default
                                    }
                                }
                                PointerEventType.Exit -> if (ch.type == PointerType.Mouse) ed.cursor = null
                                PointerEventType.Scroll -> {
                                    val d = ch.scrollDelta
                                    val mods = e.keyboardModifiers
                                    if (mods.isCtrlPressed || mods.isMetaPressed) {
                                        val anchor = ed.viewStart + ch.position.x / ed.pixelsPerSecond
                                        ed.zoom(1.15.pow(-d.y.toDouble()), anchor)
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
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (ed.editingText == null) ed.requestFocus()
                        val first = currentEvent
                        val touch = down.type == PointerType.Touch || down.type == PointerType.Stylus
                        val g = geom(size, density, layoutState.value, ed.laneTiers())
                        val grab = (if (touch) 20f else 6f) * density
                        val region = g.region(down.position.y, (if (touch) 16f else 8f) * density)
                        val mods = first.keyboardModifiers
                        fun timeAt(x: Float) = ed.viewStart + x / ed.pixelsPerSecond
                        val downTime = timeAt(down.position.x)

                        // splitters
                        if (region == Region.WaveSpecSplit || region == Region.TierSplit) {
                            val audioH = g.specBottom - g.waveTop
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.first()
                                if (!chg.pressed) break
                                val dy = chg.positionChange().y
                                chg.consume()
                                val l = layoutState.value
                                if (region == Region.WaveSpecSplit) {
                                    onLayoutState.value(l.copy(waveShare = (l.waveShare + dy / audioH).coerceIn(0.1f, 0.9f)))
                                } else if (g.tierCount > 0) {
                                    onLayoutState.value(l.copy(tierHeight = (l.tierHeight - dy / density / g.tierCount).coerceIn(28f, 96f)))
                                }
                            }
                            return@awaitEachGesture
                        }

                        // right click: play the part under the pointer
                        if (!touch && first.buttons.isSecondaryPressed) {
                            playUnder(ed, region, downTime)
                            return@awaitEachGesture
                        }

                        val otoMarker = hitOto(ed, g, region, down.position.x, grab)
                        if (otoMarker != null && !first.buttons.isTertiaryPressed) {
                            val e = ed.oto.current()!!
                            val offset = ed.oto.absolute(e).get(otoMarker) / 1000 - downTime
                            ed.oto.beginDrag()
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    moved = true
                                    ed.oto.dragTo(otoMarker, timeAt(chg.position.x) + offset, ev.keyboardModifiers.isShiftPressed)
                                    ed.cursor = timeAt(chg.position.x)
                                    ed.previewAt(timeAt(chg.position.x) + offset)
                                    chg.consume()
                                }
                            }
                            if (moved) ed.oto.endDrag() else ed.oto.dragTo(otoMarker, downTime + offset, false).also { ed.oto.endDrag() }
                            return@awaitEachGesture
                        }
                        val bound = if (ed.mode == Mode.Oto) null else hitBound(ed, g, region, down.position.x, grab)
                        val pan = first.buttons.isTertiaryPressed || region == Region.Ruler

                        if (bound != null && !pan) {
                            // drag a boundary
                            val tier = ed.doc?.tiers?.get(bound.tier) as? IntervalTier
                            val offset = (tier?.bounds?.get(bound.bound) ?: downTime) - downTime
                            ed.beginDrag(bound)
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!chg.pressed) break
                                if (chg.positionChange() != Offset.Zero) {
                                    moved = true
                                    val m = ev.keyboardModifiers
                                    ed.dragTo(bound, timeAt(chg.position.x) + offset, m.isShiftPressed, m.isAltPressed)
                                    ed.cursor = timeAt(chg.position.x)
                                    ed.previewAt(timeAt(chg.position.x) + offset)
                                    chg.consume()
                                }
                            }
                            if (moved) ed.endDrag() else ed.cancelDrag()
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
                                    val a = downTime
                                    val b = timeAt(chg.position.x).coerceIn(0.0, ed.duration)
                                    ed.range = min(a, b) to max(a, b)
                                    ed.cursor = b
                                }
                                chg.consume()
                            }
                        }
                        if (!dragged) {
                            // a tap or click
                            val (t0, p0, n0) = lastTap.value
                            val double = down.uptimeMillis - t0 < viewConfiguration.doubleTapTimeoutMillis &&
                                (down.position - p0).getDistance() < 24 * density
                            lastTap.value = Triple(down.uptimeMillis, down.position, if (double) n0 + 1 else 1)
                            onTap(ed, region, downTime, double, touch, mods.isShiftPressed)
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

        // inline text editor
        val editing = ed.editingText
        val et = editing?.let { doc?.tiers?.getOrNull(it.tier) as? IntervalTier }
        if (editing != null && et != null && editing.index < et.size) {
            val g = geom(size, density, layout, tierCount)
            val x0 = ((et.startOf(editing.index) - ed.viewStart) * ed.pixelsPerSecond).toFloat()
            val x1 = ((et.endOf(editing.index) - ed.viewStart) * ed.pixelsPerSecond).toFloat()
            val w = max(x1 - x0, 90 * density).coerceAtMost(size.width.toFloat())
            val x = x0.coerceIn(0f, max(0f, size.width - w))
            InlineEditor(
                initial = et.texts[editing.index],
                offset = IntOffset(x.toInt(), g.tierTop(editing.tier).toInt()),
                widthPx = w.toInt(),
                heightPx = g.tierH.toInt(),
                onCommit = { text ->
                    ed.editingText = null
                    ed.setText(editing, text)
                },
                onCancel = { ed.editingText = null },
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
            val tier = doc.tiers.getOrNull(region.index)
            if (tier is IntervalTier) {
                val i = tier.indexAt(time)
                if (i >= 0) {
                    val ref = IntervalRef(region.index, i)
                    ed.selectInterval(ref, reveal = false)
                    if (double) ed.editingText = ref
                }
            } else {
                ed.activeTier = region.index
            }
            if (touch) ed.cursor = time
        }
        Region.Wave, Region.Spec -> {
            ed.range = null
            ed.cursor = time.coerceIn(0.0, ed.duration)
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
    }

    // waveform
    val audio = ed.audio
    val peaks = ed.peaks
    if (g.waveBottom > g.waveTop) {
        val mid = (g.waveTop + g.waveBottom) / 2
        val amp = (g.waveBottom - g.waveTop) / 2 * 0.92f
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
                    val yy = mid - audio.samples[s] * amp
                    if (first) { path.moveTo(xx, yy); first = false } else path.lineTo(xx, yy)
                }
                drawPath(path, c.wave, style = Stroke(1.2f * px))
            } else if (peaks != null) {
                val cols = min(w.toInt(), max(0, endX.toInt() + 1))
                for (col in max(0, x(0.0).toInt())..cols) {
                    val a = ((v0 + col / pps) * sr).toInt()
                    val b = ((v0 + (col + 1) / pps) * sr).toInt()
                    if (b <= 0 || a >= audio.samples.size) continue
                    val (lo, hi) = peaks.range(audio.samples, a, max(b, a + 1))
                    path.moveTo(col + 0.5f, mid - hi * amp)
                    path.lineTo(col + 0.5f, mid - lo * amp + 0.5f)
                }
                drawPath(path, c.wave, style = Stroke(px))
            }
        }
    }
    if (g.specBottom > g.specTop && g.waveBottom > g.waveTop) drawLine(c.border, Offset(0f, g.waveBottom), Offset(w, g.waveBottom), px)

    // shade outside the file
    if (endX < w) drawRect(c.bg.copy(alpha = 0.6f), Offset(max(0f, endX), g.ruler), Size(w - max(0f, endX), g.height - g.ruler))
    val startX = x(0.0)
    if (startX > 0) drawRect(c.bg.copy(alpha = 0.6f), Offset(0f, g.ruler), Size(startX, g.height - g.ruler))

    // range selection
    ed.range?.let { (a, b) ->
        drawRect(c.selectionRange, Offset(x(a), g.ruler), Size(x(b) - x(a), g.tiersTop - g.ruler))
    }

    if (ed.mode == Mode.Oto) {
        drawOto(ed, g, c, measurer, smallStyle, tierStyle, ::x)
    } else if (doc == null) return
    if (ed.mode == Mode.Oto || doc == null) {
        drawCursor(ed, g, c, ::x)
        return
    }
    val sel = ed.selection
    val problemsByTier = ed.problems.groupBy { it.ref.tier }

    // guide lines of the active tier across the audio area
    val guide = doc.tiers.getOrNull(ed.guideTier) as? IntervalTier
    if (guide != null && g.tiersTop > g.ruler) {
        val i0 = max(0, guide.indexAt(v0).let { if (it < 0) 0 else it })
        for (b in i0..guide.size) {
            val bt = guide.bounds[b]
            if (bt > tEnd) break
            val xx = x(bt)
            val selected = sel is Selection.Bound && sel.ref.tier == ed.guideTier && sel.ref.bound == b
            drawLine(
                if (selected) c.boundSelected else c.bound.copy(alpha = 0.45f), Offset(xx, g.ruler), Offset(xx, g.tiersTop),
                if (selected) 2 * px else px,
                pathEffect = if (selected) null else PathEffect.dashPathEffect(floatArrayOf(4 * px, 3 * px)),
            )
        }
        if (sel is Selection.Interval && sel.ref.tier == ed.guideTier && sel.ref.index < guide.size) {
            val a = x(guide.startOf(sel.ref.index))
            val b = x(guide.endOf(sel.ref.index))
            drawRect(c.intervalSelected.copy(alpha = c.intervalSelected.alpha * 0.45f), Offset(a, g.ruler), Size(b - a, g.tiersTop - g.ruler))
        }
    }

    // tiers
    for ((k, tier) in doc.tiers.withIndex()) {
        val top = g.tierTop(k)
        val bottom = top + g.tierH
        if (top >= g.height) break
        val active = k == ed.activeTier
        drawRect(if (active) c.panelAlt else c.panel, Offset(0f, top), Size(w, g.tierH))
        drawLine(c.border, Offset(0f, top), Offset(w, top), px)
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
                is NoteTier -> for (n in tier.notes) {
                    if (n.end < v0 || n.start > tEnd) continue
                    val a = x(n.start)
                    val b = x(n.end)
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

    // cursor and playhead
    ed.cursor?.let { cur ->
        val xx = x(cur)
        if (xx in 0f..w) drawLine(c.cursor, Offset(xx, g.ruler), Offset(xx, g.height), px)
    }
    ed.playhead?.let { p ->
        val xx = x(p)
        if (xx in 0f..w) drawLine(c.playhead, Offset(xx, 0f), Offset(xx, g.height), 2 * px)
    }
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
    for (i in first until tier.size) {
        val s = tier.startOf(i)
        if (s > tEnd) break
        val a = x(s)
        val b = x(tier.endOf(i))
        val selected = sel is Selection.Interval && sel.ref.tier == k && sel.ref.index == i
        if (selected) drawRect(c.intervalSelected, Offset(a, top), Size(b - a, h))
        val text = tier.texts[i]
        if (text.isEmpty()) {
            drawRect(c.text.copy(alpha = 0.03f), Offset(a, top), Size(b - a, h))
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
        val width = b - a - 6 * px
        if (text.isNotEmpty() && width > 6 * px) {
            val layout = measurer.measure(
                text, style, overflow = TextOverflow.Clip, maxLines = 1, softWrap = false,
                constraints = Constraints(maxWidth = max(1, width.toInt())),
            )
            val tx = a + (b - a - layout.size.width) / 2
            drawText(layout, topLeft = Offset(max(a + 3 * px, tx), top + (h - layout.size.height) / 2 - 2 * px))
        }
    }
    // boundaries
    val last = tier.bounds.size - 1
    for (bIdx in first..last) {
        val t = tier.bounds[bIdx]
        if (t > tEnd) break
        val xx = x(t)
        val selected = sel is Selection.Bound && sel.ref.tier == k && sel.ref.bound == bIdx
        drawLine(if (selected) c.boundSelected else c.bound, Offset(xx, top), Offset(xx, bottom), if (selected) 3 * px else px)
    }
}

@Composable
private fun InlineEditor(initial: String, offset: IntOffset, widthPx: Int, heightPx: Int, onCommit: (String) -> Unit, onCancel: () -> Unit) {
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
    with(density) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = if (c.mono) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(c.accent),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { finish(true) }),
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
                        Key.Escape -> { finish(false); true }
                        Key.Enter, Key.NumPadEnter -> { finish(true); true }
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

private fun EditorState.laneTiers() = if (mode == Mode.Oto) 0 else doc?.tiers?.size ?: 0

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
    val shade = Color.Black.copy(alpha = if (c.dark) 0.5f else 0.25f)
    val l = x(a.left / 1000)
    val r = x(a.right / 1000)
    val k = x(a.consonant / 1000)
    drawRect(shade, Offset(0f, top), Size(max(0f, l), h))
    drawRect(shade, Offset(r, top), Size(max(0f, size.width - r), h))
    drawRect(otoColors.getValue(mlabeler.core.format.OtoMarker.Consonant).copy(alpha = 0.16f), Offset(l, top), Size(max(0f, k - l), h))
    for ((m, col) in otoColors) {
        val xx = x(a.get(m) / 1000)
        drawLine(col, Offset(xx, top), Offset(xx, bottom), if (m == mlabeler.core.format.OtoMarker.Preutterance) 2.5f * px else 1.5f * px)
    }
    safeText(measurer, e.alias, Offset(l + 6 * px, top + 6 * px), big.copy(color = c.text))
}
