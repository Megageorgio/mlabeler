package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import mlabeler.core.io.decodeGuess
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.AudioOut
import mlabeler.app.Platform
import mlabeler.app.i18n.S
import mlabeler.app.i18n.L
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.check.Checks
import mlabeler.core.check.Problem
import mlabeler.core.dsp.Peaks
import mlabeler.core.dsp.Spectrogram
import mlabeler.core.edit.BoundRef
import mlabeler.core.edit.Edits
import mlabeler.core.edit.History
import mlabeler.core.edit.IntervalRef
import mlabeler.core.edit.MoveOptions
import mlabeler.core.io.Item
import mlabeler.core.io.ItemMarks
import mlabeler.core.io.Paths
import mlabeler.core.io.AUDIO_EXTENSIONS
import mlabeler.core.io.ALL_AUDIO_EXTENSIONS
import mlabeler.core.io.Workspace
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.math.max
import kotlin.math.min

// Work of the open folder done by the toolkit: autolabel of a part or of many files, refinement, resynthesis.

/**
 * Plays the selection (or the phoneme, or what is on screen) sung with the pitch as it is now — the analysed
 * f0 with what was drawn — through the toolkit: "world" (quick) or "nsf" (the vocoder of DiffSinger).
 */
fun EditorState.playResynth(method: String) {
    val a = audio ?: return
    val curve = pitchCurve ?: return app.message(S.pitchNotReady())
    val (from, to) = range ?: selectedSpan() ?: (viewStart to viewStart + visibleDuration)
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        val client = app.toolkit.client()
        var serverJob: String? = null
        try {
            beginToolkitWork(mlabeler.app.toolkit.ToolkitManager.starting())
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            toolkitBusy = S.uploading()
            val s0 = (from.coerceAtLeast(0.0) * a.sampleRate).toInt().coerceIn(0, a.samples.size)
            val s1 = (to * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
            val wav = withContext(Dispatchers.Default) { Wav.encode16(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))) }
            val k0 = (s0.toDouble() / a.sampleRate / curve.hop).toInt()
            val k1 = (s1.toDouble() / a.sampleRate / curve.hop).toInt() + 1
            val f0 = FloatArray((k1 - k0).coerceAtLeast(1)) { i -> curve.values.getOrNull(k0 + i)?.takeIf { v -> !v.isNaN() } ?: 0f }
            val fileId = client.upload((item?.name ?: "part") + "_resynth.wav", wav)
            val job = client.resynth(fileId, f0, curve.hop, method)
            serverJob = job
            val result = client.await(job, { toolkitDetail = it }) { p, stage -> toolkitProgress = p; toolkitBusy = stage.ifEmpty { S.toolkit() } }
            val path = (result["file"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw mlabeler.app.toolkit.ToolkitException("no file in the result")
            val bytes = client.download(path)
            val out = withContext(Dispatchers.Default) { Wav.decode(bytes) }
            playBuffer(out)
        } catch (e: kotlinx.coroutines.CancellationException) {
            serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { client.cancel(id) } }
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}

/**
 * Aligns [from]..[to] with [model]; the result replaces that part of the tiers ([replace]) or is shown
 * as a comparison tier named after the model.
 */
fun EditorState.autolabel(from: Double, to: Double, model: String, language: String?, text: String, phonemes: Boolean, replace: Boolean, recognize: Boolean = false, whisper: Boolean = false,
              extraLanguages: List<String> = emptyList()) {
    val a = audio ?: return
    val it = item ?: return
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        val client = app.toolkit.client()
        var serverJob: String? = null
        try {
            toolkitBusySince = now()
            toolkitSteps.clear()
            toolkitDetail = null
            toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            toolkitBusy = S.uploading()
            val s0 = (from * a.sampleRate).toInt().coerceIn(0, a.samples.size)
            val s1 = (to * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
            val wav = withContext(Dispatchers.Default) { Wav.encode16(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))) }
            val fileId = client.upload(it.name + "_part.wav", wav)
            val job = if (recognize) {
                client.segment(fileId, model, language, text.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }, settings.toolkit.wfl, refineAfter())
            } else client.align(fileId, model, language, text, phonemes, whisper, refineAfter(), extraLanguages, settings.toolkit.whisperModel)
            serverJob = job
            val result = client.await(job, { toolkitDetail = it }) { p, stage ->
                toolkitProgress = p
                val st = stage.ifEmpty { S.toolkit() }
                // a new step (not the same one with new numbers) goes to the list of steps
                val key = st.substringBefore(':').substringBefore('(').trim()
                val prev = toolkitBusy
                if (prev != null && prev.substringBefore(':').substringBefore('(').trim() != key) { toolkitSteps.add(prev); while (toolkitSteps.size > 6) toolkitSteps.removeAt(0) }
                toolkitBusy = st
            }
            val part = mlabeler.app.toolkit.ToolkitClient.labelOf(result, s0.toDouble() / a.sampleRate, (s1 - s0).toDouble() / a.sampleRate)
            if (replace) {
                updateDocShowingChanges { d ->
                    var out = d
                    for (pt in part.tiers.filterIsInstance<IntervalTier>()) {
                        val k = out.tierIndex(pt.name).takeIf { k -> k >= 0 } ?: if (pt.name == "phones") out.phonemeTierIndex() else -1
                        if (k >= 0) out = out.replace(k, mlabeler.core.edit.RangeEdits.replace(out.tiers[k] as IntervalTier, from, to, pt))
                        else out = out.copy(tiers = listOf(mlabeler.core.edit.RangeEdits.replace(IntervalTier.empty(pt.name, duration), from, to, pt)) + out.tiers)
                    }
                    out
                }
            } else {
                modelResults[it.id] = modelResults[it.id].orEmpty() + EditorState.Reference(model, "", part, from to to)
                loadReferences()
            }
            val check = mlabeler.app.toolkit.ToolkitClient.diagnosisOf(result)?.let { "\n" + selfCheckText(it) }.orEmpty()
            app.message((if (replace) S.autolabelDone() else S.autolabelCompareDone()) + check)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // stop the job on the toolkit side too
            serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { client.cancel(id) } }
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}

/**
 * Labels many files one after another and saves each (the open one through its history, so it can be undone).
 * Stopping keeps what is done; running again on files without labels goes on from there.
 */
fun EditorState.autolabelFiles(
    files: List<Item>, model: String, language: String?, recognize: Boolean, source: EditorState.BatchText,
    phonemes: Boolean, whisper: Boolean, extraLanguages: List<String> = emptyList(),
) {
    if (files.isEmpty()) return
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        val client = app.toolkit.client()
        var serverJob: String? = null
        val failed = mutableListOf<String>()
        val unsure = mutableListOf<String>()
        var done = 0
        try {
            toolkitBusySince = now()
            toolkitSteps.clear()
            toolkitDetail = null
            toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            for ((n, f) in files.withIndex()) {
                val head = batchFile.format(n + 1, files.size, f.name)
                toolkitBusy = head
                toolkitDetail = null
                toolkitProgress = n.toDouble() / files.size
                try {
                    val bytes = withContext(Dispatchers.Default) { workspace.fs.read(f.audioPath) }
                    val a = withContext(Dispatchers.Default) {
                        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
                    }
                    val current = if (f.id == item?.id) committed else runCatching { workspace.readLabels(f, a.duration) }.getOrNull()
                    val known: List<String> = when (source) {
                        EditorState.BatchText.Labels -> current?.let { d -> (d.tiers.getOrNull(d.phonemeTierIndex()) as? IntervalTier)?.texts?.filter { it.isNotEmpty() } }.orEmpty()
                        else -> emptyList()
                    }
                    val txt: String? = if (source == EditorState.BatchText.TxtNextToIt) {
                        val p = Paths.join(Paths.parent(f.audioPath), Paths.stem(f.audioPath) + ".txt")
                        runCatching { workspace.fs.read(p).decodeToString() }.getOrNull()
                    } else null
                    val upload = if (Wav.isWav(bytes)) bytes else withContext(Dispatchers.Default) { Wav.encode16(a) }
                    val fileId = client.upload(Paths.stem(f.audioPath) + ".wav", upload)
                    val job = if (recognize) client.segment(fileId, model, language, known, settings.toolkit.wfl, refineAfter())
                    else {
                        val asPhonemes = source == EditorState.BatchText.Labels || (txt != null && (phonemes || mlabeler.core.format.TextImport.looksLikeLab(txt)))
                        val text = when {
                            source == EditorState.BatchText.Labels -> known.joinToString(" ")
                            txt != null -> mlabeler.core.format.TextImport.clean(txt, asPhonemes)
                            else -> ""
                        }
                        if (text.isBlank() && !whisper) error(noText())
                        client.align(fileId, model, language, text, asPhonemes && text.isNotBlank(), whisper, refineAfter(), extraLanguages, settings.toolkit.whisperModel)
                    }
                    serverJob = job
                    val result = client.await(job, { toolkitDetail = it }) { p, stage ->
                        toolkitProgress = (n + p) / files.size
                        toolkitBusy = head + " · " + stage.ifEmpty { S.toolkit() }
                    }
                    serverJob = null
                    mlabeler.app.toolkit.ToolkitClient.diagnosisOf(result)?.takeIf { it.weak }?.let { unsure += f.name + " · " + selfCheckShort(it) }
                    val part = mlabeler.app.toolkit.ToolkitClient.labelOf(result, 0.0, a.duration)
                    fun merged(d: LabelDoc): LabelDoc {
                        var out = d
                        for (pt in part.tiers.filterIsInstance<IntervalTier>()) {
                            val k = out.tierIndex(pt.name).takeIf { k -> k >= 0 } ?: if (pt.name == "phones") out.phonemeTierIndex() else -1
                            out = if (k >= 0 && out.tiers[k] is IntervalTier) out.replace(k, pt.copy(name = (out.tiers[k] as IntervalTier).name))
                            else out.copy(tiers = listOf(pt) + out.tiers)
                        }
                        return out
                    }
                    if (f.id == item?.id) {
                        updateDocShowingChanges { d -> merged(d) }
                        saveLabels(quiet = true)
                    } else {
                        writeOtherLabels(f, merged(current ?: LabelDoc.empty(a.duration)), a.duration,
                            if (f.labelFormat == null) workspace.state.defaultFormat else null)
                    }
                    done++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += f.name + ": " + (e.message ?: e.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    toolkitSteps.add(failed.last()); while (toolkitSteps.size > 6) toolkitSteps.removeAt(0)
                }
            }
            labelIndex = null
            docVersion++
            val unsureText = if (unsure.isEmpty()) "" else "\n" + worthChecking() + "\n" + unsure.joinToString("\n")
            if (failed.isEmpty()) app.message(batchDone.format(done) + unsureText)
            else app.message(batchDoneWithErrors.format(done, failed.size) + "\n" + failed.joinToString("\n") + unsureText, error = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            serverJob?.let { id -> withContext(kotlinx.coroutines.NonCancellable) { client.cancel(id) } }
            withContext(kotlinx.coroutines.NonCancellable) { if (done > 0) app.message(batchStopped.format(done, files.size)) }
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}

/** The refinement done after autolabelling, when it's on in the settings. */
internal fun EditorState.refineAfter(): RefineSettings? = settings.toolkit.refine.takeIf { it.enabled && it.model.isNotBlank() }

/**
 * Refines the boundaries of the phoneme tier of [doc] within [from]..[to] (all of it by default) with the refiner
 * model: the part of the recording is uploaded with its phonemes; returns the labelling with the boundaries moved.
 */
internal suspend fun EditorState.refinedDoc(
    client: mlabeler.app.toolkit.ToolkitClient, name: String, a: Audio, doc: LabelDoc, from: Double, to: Double,
    r: RefineSettings, progress: (Double, String) -> Unit,
): Pair<LabelDoc, Int> {
    val k = doc.phonemeTierIndex()
    val tier = doc.tiers.getOrNull(k) as? IntervalTier ?: return doc to 0
    val range = mlabeler.core.edit.BoundaryMoves.inside(tier, from, to)
    if (range.count() < 2) return doc to 0
    val t0 = tier.startOf(range.first)
    val t1 = tier.endOf(range.last)
    val s0 = (t0 * a.sampleRate).toInt().coerceIn(0, a.samples.size)
    val s1 = (t1 * a.sampleRate).toInt().coerceIn(s0, a.samples.size)
    val wav = withContext(Dispatchers.Default) { Wav.encode16(Audio(a.sampleRate, a.samples.copyOfRange(s0, s1))) }
    val fileId = client.upload("${name}_refine.wav", wav)
    val offset = s0.toDouble() / a.sampleRate
    val segments = range.map { i -> Triple(tier.startOf(i) - offset, tier.endOf(i) - offset, tier.texts[i]) }
    val job = client.refine(fileId, segments, r.model, r.mode)
    val result = try { client.await(job, { toolkitDetail = it }, progress) } catch (e: kotlinx.coroutines.CancellationException) {
        withContext(kotlinx.coroutines.NonCancellable) { client.cancel(job) }
        throw e
    }
    val got = mlabeler.app.toolkit.ToolkitClient.labelOf(result, offset, (s1 - s0).toDouble() / a.sampleRate)
    val phones = got.tiers.filterIsInstance<IntervalTier>().firstOrNull { it.name == "phones" } ?: got.tiers.filterIsInstance<IntervalTier>().first()
    // the toolkit may close gaps or round: take the start of each sent interval, in order
    val starts = (0 until phones.size).map { phones.startOf(it) }
    if (starts.size != range.count()) return doc to 0
    val out = mlabeler.core.edit.BoundaryMoves.apply(doc, k, range, starts)
    val moved = range.drop(1).count { i -> kotlin.math.abs((out.tiers[k] as IntervalTier).startOf(i) - tier.startOf(i)) > 0.0005 }
    return out to moved
}

/** Refines the phoneme boundaries of the open file within [from]..[to] (undoable). */
fun EditorState.refineBoundaries(from: Double, to: Double, r: RefineSettings) {
    val a = audio ?: return
    val it = item ?: return
    val d = doc ?: return
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        val client = app.toolkit.client()
        try {
            toolkitBusySince = now()
            toolkitSteps.clear()
            toolkitDetail = null
            toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            toolkitBusy = S.uploading()
            val (out, moved) = refinedDoc(client, it.name, a, d, from, to, r) { p, stage -> toolkitProgress = p; toolkitBusy = stage.ifEmpty { S.toolkit() } }
            if (moved > 0) updateDocShowingChanges { out }
            app.message(refinedT.format(moved))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}

/** Refines the phoneme boundaries of the labels of [files] and saves them (the open one through its history). */
fun EditorState.refineFiles(files: List<Item>, r: RefineSettings) {
    if (files.isEmpty()) return
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        val client = app.toolkit.client()
        val failed = mutableListOf<String>()
        var done = 0
        var moved = 0
        try {
            toolkitBusySince = now()
            toolkitSteps.clear()
            toolkitDetail = null
            toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
            if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
            for ((n, f) in files.withIndex()) {
                val head = batchFile.format(n + 1, files.size, f.name)
                toolkitBusy = head
                toolkitDetail = null
                toolkitProgress = n.toDouble() / files.size
                try {
                    val bytes = withContext(Dispatchers.Default) { workspace.fs.read(f.audioPath) }
                    val a = withContext(Dispatchers.Default) {
                        if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
                    }
                    val current = (if (f.id == item?.id) doc else workspace.readLabels(f, a.duration)) ?: continue
                    val (out, m) = refinedDoc(client, Paths.stem(f.audioPath), a, current, 0.0, a.duration, r) { p, stage ->
                        toolkitProgress = (n + p) / files.size
                        toolkitBusy = head + " · " + stage.ifEmpty { S.toolkit() }
                    }
                    moved += m
                    if (m > 0) {
                        if (f.id == item?.id) {
                            updateDocShowingChanges { out }
                            saveLabels(quiet = true)
                        } else writeOtherLabels(f, out, a.duration)
                    }
                    done++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += f.name + ": " + (e.message ?: e.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    toolkitSteps.add(failed.last()); while (toolkitSteps.size > 6) toolkitSteps.removeAt(0)
                }
            }
            labelIndex = null
            docVersion++
            val text = refinedFilesT.format(done, moved)
            if (failed.isEmpty()) app.message(text) else app.message(text + "\n" + failed.joinToString("\n"), error = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { if (done > 0) app.message(refinedFilesT.format(done, moved)) }
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
        }
    }
}

private val refinedT = L("Boundaries moved: {0}", "Сдвинуто границ: {0}")
private val refinedFilesT = L("Refined {0} files, boundaries moved: {1}", "Уточнено файлов: {0}, сдвинуто границ: {1}")
private val batchFile = L("File {0} of {1}: {2}", "Файл {0} из {1}: {2}")
private val batchDone = L("Labelled {0} files", "Размечено файлов: {0}")
private val selfCheck = L("Self-check: the text fits the sound by {0}%, the phoneme spans by {1}%", "Самопроверка: текст совпадает со звуком на {0}%, участки фонем — на {1}%")
private val selfCheckSkipped = L("{0} phonemes found no room, they are 1 ms long", "Фонем без места: {0}, у них длина 1 мс")
private val selfCheckBrief = L("text {0}%, spans {1}%", "текст {0}%, участки {1}%")
private val worthChecking = L("Worth checking, the aligner is unsure:", "Стоит проверить, выравниватель не уверен:")

/** Low scores of the aligner's self-check: the text may not match the recording, or the boundaries may be off. */
private val mlabeler.app.toolkit.ToolkitClient.Diagnosis.weak: Boolean
    get() = (agreement ?: 1.0) < 0.8 || (confidence ?: 1.0) < 0.4 || skipped > 0

private fun pct(v: Double?) = v?.let { kotlin.math.round(it * 100).toInt().toString() } ?: "—"

private fun selfCheckText(d: mlabeler.app.toolkit.ToolkitClient.Diagnosis): String =
    selfCheck.format(pct(d.agreement), pct(d.confidence)) + if (d.skipped > 0) "\n" + selfCheckSkipped.format(d.skipped) else ""

private fun selfCheckShort(d: mlabeler.app.toolkit.ToolkitClient.Diagnosis): String =
    selfCheckBrief.format(pct(d.agreement), pct(d.confidence)) + if (d.skipped > 0) " · " + selfCheckSkipped.format(d.skipped) else ""
private val batchDoneWithErrors = L("Labelled {0} files, {1} with errors:", "Размечено файлов: {0}, с ошибками: {1}:")
private val batchStopped = L("Stopped: {0} of {1} files labelled and saved", "Остановлено: размечено и сохранено {0} из {1}")
private val noText = L("no text (a .txt with the same name, or Whisper)", "нет текста (.txt с тем же именем или Whisper)")
