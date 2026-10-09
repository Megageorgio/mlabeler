package mlabeler.app.ui

// Drawing of the timeline: lanes, tiers, references, curves, oto markers.

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

internal fun DrawScope.drawTimeline(
    ed: EditorState,
    g: Geom,
    c: Tokens,
    doc: LabelDoc?,
    specImage: ImageBitmap?,
    measurer: androidx.compose.ui.text.TextMeasurer,
    tierStyle: TextStyle,
    smallStyle: TextStyle,
    specAlpha: Float = 1f,
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
                alpha = c.laneAlpha.coerceIn(0.1f, 1f) * specAlpha.coerceIn(0f, 1f),
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
    val lay = ed.app.settings.layout
    val soundMode = ed.soundMode
    // sound editing: the parts between pauses as bars along the top of the sound
    if (soundMode && lay.soundPhrases && guide != null && g.audioBottom > g.audioTop) {
        val pauses = ed.app.settings.checks.pauses
        var i = 0
        var n = 0
        while (i < guide.size) {
            if (guide.texts[i].isEmpty() || guide.texts[i] in pauses) { i++; continue }
            var j = i
            while (j + 1 < guide.size && guide.texts[j + 1].isNotEmpty() && guide.texts[j + 1] !in pauses) j++
            n++
            val a = x(guide.startOf(i)); val b = x(guide.endOf(j))
            if (b > 0 && a < size.width) {
                drawRect(c.accent.copy(alpha = 0.10f), Offset(a, g.audioTop), Size(b - a, g.audioBottom - g.audioTop))
                drawRect(c.accent.copy(alpha = 0.75f), Offset(a, g.audioTop), Size(b - a, 3 * px))
                safeText(measurer, n.toString(), Offset(a + 3 * px, g.audioTop + 4 * px), smallStyle.copy(color = c.accent))
            }
            i = j + 1
        }
    }
    if (guide != null && g.audioBottom > g.audioTop && !(soundMode && !lay.soundShowLabels)) {
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
                    pathEffect = (if (soundMode) lay.soundLabelStyle else c.boundStyle).let { style -> when {
                        selected || style == "solid" -> null
                        style == "dot" -> PathEffect.dashPathEffect(floatArrayOf(lw, 2 * lw + px))
                        else -> PathEffect.dashPathEffect(floatArrayOf(4 * px + lw, 3 * px))
                    } },
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
                is IntervalTier -> {
                    drawIntervalTier(ed, k, tier, top, bottom, c, measurer, tierStyle, problemsByTier[k].orEmpty(), ::x, tEnd)
                    // what autolabel, a plugin or undo has just changed, fading out
                    val glow = ed.changeGlow
                    if (glow > 0f) ed.changedSpans[k]?.forEach { (a, b) ->
                        if (b >= v0 && a <= tEnd) {
                            val xa = x(a)
                            drawRect(c.accent.copy(alpha = 0.32f * glow), Offset(xa, top), Size(max(x(b) - xa, 2 * px), g.tierH))
                        }
                    }
                    ed.hoverBound?.takeIf { it.tier == k && it.bound < tier.bounds.size }?.let { hb ->
                        val xx = x(tier.bounds[hb.bound])
                        drawLine(c.accent, Offset(xx, top), Offset(xx, bottom), 3 * px)
                    }
                }
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

internal fun DrawScope.drawIntervalTier(
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
    // a light tint by the kind of phoneme, in the theme's own lane colours
    val tint = ed.app.settings.layout.phonemeColors && (k == ed.doc?.phonemeTierIndex() || tier.name == mlabeler.core.format.SegUnits.TIER)
    val tintAlpha = if (c.dark) 0.11f else 0.09f
    val vowelTint = c.tierColors.getOrElse(0) { c.accent }.copy(alpha = tintAlpha)
    val consonantTint = c.tierColors.getOrElse(1) { c.accent }.copy(alpha = tintAlpha * 0.8f)
    val pauseTint = c.muted.copy(alpha = tintAlpha * 0.5f)
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
        } else if (tint && !selected) {
            val col = when (mlabeler.core.model.PhonemeKinds.of(text)) {
                mlabeler.core.model.PhonemeKinds.Kind.Vowel -> vowelTint
                mlabeler.core.model.PhonemeKinds.Kind.Consonant -> consonantTint
                mlabeler.core.model.PhonemeKinds.Kind.Pause -> pauseTint
            }
            drawRect(col, Offset(a, top), Size(b - a, h))
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

@Suppress("unused")
internal fun dbLabel(v: Double) = "${(20 * log10(v)).toInt()} dB"

/**
 * Draws one line of text at [topLeft]. Measures without width limits first: drawText(measurer, String, …)
 * derives constraints from the space left in the canvas and throws when the text starts past its right edge.
 */
internal fun DrawScope.safeText(measurer: androidx.compose.ui.text.TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (text.isEmpty() || topLeft.x >= size.width || topLeft.y >= size.height) return
    val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
    drawText(layout, topLeft = topLeft)
}

internal fun EditorState.laneTiers() = if (mode == Mode.Oto) 0 else (doc?.tiers?.size ?: 0) + referenceTiers.size

internal fun DrawScope.drawCursor(ed: EditorState, g: Geom, c: Tokens, x: (Double) -> Float) {
    val px = density
    ed.cursor?.let { cur -> val xx = x(cur); if (xx in 0f..size.width) drawLine(c.cursor, Offset(xx, g.ruler), Offset(xx, g.height), px) }
    ed.playhead?.let { p -> val xx = x(p); if (xx in 0f..size.width) drawLine(c.playhead, Offset(xx, 0f), Offset(xx, g.height), 2 * px) }
}

/** Marker colours follow the usual oto editor convention. */
internal val otoColors = mapOf(
    mlabeler.core.format.OtoMarker.Left to Color(0xFF4F8DFF),
    mlabeler.core.format.OtoMarker.Overlap to Color(0xFF3CC47C),
    mlabeler.core.format.OtoMarker.Preutterance to Color(0xFFFF4D5E),
    mlabeler.core.format.OtoMarker.Consonant to Color(0xFFFF8FD0),
    mlabeler.core.format.OtoMarker.Right to Color(0xFF4F8DFF),
)

internal fun DrawScope.drawOto(
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

internal val pitchColor = Color(0xFF39E1FF)

/** Pitch (own lane or over the spectrogram) and power curves. */
internal fun DrawScope.drawCurves(ed: EditorState, g: Geom, c: Tokens, measurer: androidx.compose.ui.text.TextMeasurer, small: TextStyle, x: (Double) -> Float) {
    val layout = ed.app.settings.layout
    val px = density
    val w = size.width
    val v0 = ed.viewStart
    val v1 = v0 + w / ed.pixelsPerSecond
    // formants: F1 F2 F3 as small squares over the spectrogram
    val fm = ed.formants
    if (layout.showFormants && fm != null && g.specBottom > g.specTop) {
        val spec = ed.spectrogram
        val maxF = min(ed.app.settings.view.maxFreq.toDouble(), spec?.maxFreq ?: 8000.0)
        val melTop = Spectrogram.hzToMel(maxF)
        val colors = listOf(Color(0xFFFF4D4D), Color(0xFFFFB020), Color(0xFF7CFF6B))
        val d = 2.5f * px
        val i0 = max(0, (v0 / fm.hop).toInt() - 1)
        for ((k, track) in fm.f.withIndex()) {
            val i1 = min(track.size - 1, (v1 / fm.hop).toInt() + 1)
            for (i in i0..i1) {
                val f = track[i]
                if (f.isNaN() || f > maxF) continue
                val yy = (g.specBottom - Spectrogram.hzToMel(f.toDouble()) / melTop * (g.specBottom - g.specTop)).toFloat()
                drawRect(colors[k], Offset(x(i * fm.hop) - d / 2, yy - d / 2), Size(d, d))
            }
        }
    }
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
internal fun DrawScope.drawReferences(
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
