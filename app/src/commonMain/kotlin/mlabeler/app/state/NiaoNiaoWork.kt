package mlabeler.app.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.format.LabelFormat
import mlabeler.core.format.NiaoNiao
import mlabeler.core.io.Item
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs

// NiaoNiao voicebanks: the marks of each recording in its .inf, measuring, packing into voice.d and inf.d, oto.ini.

private suspend fun EditorState.readAudio(f: Item): Audio = withContext(Dispatchers.Default) {
    val bytes = workspace.fs.read(f.audioPath)
    if (Wav.isWav(bytes)) Wav.decode(bytes) else Platform.decodeAudio(f.audioPath) ?: error(S.unsupportedAudio())
}

private fun EditorState.infPath(f: Item) = Paths.withExt(f.audioPath, "inf")

private fun EditorState.readInf(f: Item): NiaoNiao.Inf? =
    runCatching { NiaoNiao.read(workspace.fs.read(infPath(f)).decodeToString()) }.getOrNull()

/** Saves the open file first, so its marks are what is in its .inf. */
private fun EditorState.saveOpen() {
    finishEditing()
    if (labelsDirty) saveLabels(quiet = true)
}

/** The WAV recordings of the folder (the open recording's folder): NiaoNiao reads only WAV. */
fun EditorState.niaoFiles(): List<Item> {
    val dir = item?.let { Paths.parent(it.audioPath) } ?: workspace.root
    return items.filter { Paths.parent(it.audioPath) == dir && Paths.ext(it.audioPath).equals("wav", true) }
}

private fun EditorState.runNiao(title: String, block: suspend () -> String) {
    toolkitJob?.cancel()
    toolkitJob = scope.launch {
        try {
            beginToolkitWork(title)
            app.message(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.message(e.message ?: e.toString(), error = true)
        } finally {
            toolkitBusy = null
            toolkitProgress = null
            labelsChangedOnDisk()
            rescan()
            reloadOpenLabels()
        }
    }
}

/**
 * Places the marks of [files] from the loudness and measures their pitch and levels; with [keep] the files that
 * have an .inf keep it.
 */
fun EditorState.niaoAutoMarks(files: List<Item>, keep: Boolean) {
    saveOpen()
    // new labels of the folder are marks too
    workspace.updateState { it.copy(defaultFormat = LabelFormat.Inf) }
    runNiao(autoT()) {
        var done = 0
        val failed = mutableListOf<String>()
        for ((k, f) in files.withIndex()) {
            toolkitBusy = progressT.format(k + 1, files.size, f.name)
            toolkitProgress = k.toDouble() / files.size
            if (keep && workspace.fs.exists(infPath(f))) continue
            try {
                val a = readAudio(f)
                val inf = withContext(Dispatchers.Default) { NiaoNiao.auto(a.samples, a.sampleRate)?.let { NiaoNiao.measure(it, a.samples, a.sampleRate) } }
                    ?: error(silentT())
                workspace.fs.write(infPath(f), inf.write().encodeToByteArray())
                histories.remove(f.id)
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += f.name + ": " + (e.message ?: e.toString())
            }
        }
        autoDoneT.format(done) + if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n")
    }
}

/** Measures the pitch and the levels of every .inf of [files] again from its recording. */
fun EditorState.niaoMeasure(files: List<Item>) {
    saveOpen()
    runNiao(measureT()) {
        var done = 0
        for ((k, f) in files.withIndex()) {
            toolkitBusy = progressT.format(k + 1, files.size, f.name)
            toolkitProgress = k.toDouble() / files.size
            val inf = readInf(f) ?: continue
            val a = readAudio(f)
            val m = withContext(Dispatchers.Default) { NiaoNiao.measure(inf, a.samples, a.sampleRate) }
            workspace.fs.write(infPath(f), m.write().encodeToByteArray())
            done++
        }
        measuredT.format(done)
    }
}

/**
 * Builds the bank of [files] into [out]: voice.d and inf.d (pitch and levels measured again when [measure]), and
 * readme.txt, charactor.txt and head.d (from head.png) found in the folder or above it.
 */
fun EditorState.niaoPack(files: List<Item>, out: String, measure: Boolean, version: Int) {
    saveOpen()
    runNiao(packT()) {
        val sounds = mutableListOf<NiaoNiao.Sound>()
        val skipped = mutableListOf<String>()
        for ((k, f) in files.withIndex()) {
            toolkitBusy = progressT.format(k + 1, files.size, f.name)
            toolkitProgress = 0.9 * k / files.size
            val inf0 = readInf(f) ?: run { skipped += f.name + ": " + noInfT(); null } ?: continue
            val a = readAudio(f)
            if (a.sampleRate != NiaoNiao.SAMPLE_RATE) { skipped += f.name + ": " + rateT.format(a.sampleRate); continue }
            val inf = if (measure) withContext(Dispatchers.Default) { NiaoNiao.measure(inf0, a.samples, a.sampleRate) } else inf0
            val s = inf.start.coerceIn(0, a.samples.size)
            val e = inf.end.coerceIn(s, a.samples.size)
            sounds += NiaoNiao.Sound(Paths.stem(f.audioPath), inf.copy(start = s, end = e), NiaoNiao.toInt16(a.samples, s, e))
            if (measure && inf != inf0) workspace.fs.write(infPath(f), inf.write().encodeToByteArray())
        }
        if (sounds.isEmpty()) error(nothingT())
        val (voice, infD) = withContext(Dispatchers.Default) { NiaoNiao.pack(sounds, version) }
        val fs = workspace.fs
        fs.mkdirs(out)
        fs.write(Paths.join(out, "voice.d"), voice)
        fs.write(Paths.join(out, "inf.d"), infD.encodeToByteArray())
        // the texts and the picture of the bank, from the folder or the one above
        val dir = Paths.parent(files.first().audioPath)
        val extras = mutableListOf<String>()
        for ((from, to) in listOf("readme.txt" to "readme.txt", "charactor.txt" to "charactor.txt", "character.txt" to "charactor.txt",
            "head.d" to "head.d", "head.png" to "head.d")) {
            val src = listOf(dir, Paths.parent(dir)).map { Paths.join(it, from) }.firstOrNull { fs.exists(it) } ?: continue
            if (to in extras) continue
            runCatching { fs.copy(src, Paths.join(out, to)); extras += to }
        }
        packedT.format(sounds.size, out) + (if (extras.isEmpty()) "\n" + noExtrasT() else "") +
            if (skipped.isEmpty()) "" else "\n" + skippedT() + "\n" + skipped.joinToString("\n")
    }
}

/**
 * Sings every recording of [files] that has marks again on one pitch through the toolkit (WORLD), so the bank sounds
 * steady: [target] Hz for all, or with null each its own (the pitch of its .inf). The recordings are replaced, the
 * old ones go to .mlabeler/backup.
 */
fun EditorState.niaoFlatten(files: List<Item>, target: Double?) {
    saveOpen()
    runNiao(flattenT()) {
        val client = app.toolkit.client()
        if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
        val folder = "niaoniao-tone-" + workspace.stamp()
        var done = 0
        val failed = mutableListOf<String>()
        for ((k, f) in files.withIndex()) {
            val inf = readInf(f) ?: continue
            toolkitBusy = progressT.format(k + 1, files.size, f.name)
            toolkitProgress = k.toDouble() / files.size
            try {
                val a = readAudio(f)
                val curve = withContext(Dispatchers.Default) { mlabeler.core.dsp.Pitch.yin(a.samples, a.sampleRate) }
                val hz = (target ?: if (inf.pitch > 0) inf.pitch else NiaoNiao.measure(inf, a.samples, a.sampleRate, curve).pitch).toFloat()
                if (hz <= 0f) error(noPitchT())
                // voiced frames on the one pitch, unvoiced ones stay unvoiced
                val f0 = FloatArray(curve.values.size) { i -> if (curve.values[i] > 0f) hz else 0f }
                val id = client.upload(Paths.stem(f.audioPath) + ".wav", withContext(Dispatchers.Default) { Wav.encode16(a) })
                val job = client.resynth(id, f0, curve.hop, "world")
                val res = try { client.await(job) { _, _ -> } } catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) { client.cancel(job) }
                    throw e
                }
                val path = (res["file"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("no file in the result")
                val bytes = client.download(path)
                workspace.moveToBackup(f.audioPath, folder)
                workspace.fs.write(f.audioPath, bytes)
                val b = withContext(Dispatchers.Default) { Wav.decode(bytes) }
                workspace.fs.write(infPath(f), NiaoNiao.measure(inf, b.samples, b.sampleRate).write().encodeToByteArray())
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += f.name + ": " + (e.message ?: e.toString())
            }
        }
        if (files.any { it.id == item?.id }) reloadAudio()
        flattenedT.format(done) + if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n")
    }
}

/** The pitches of the .inf of [files] by sound name (0 where there is none). */
fun EditorState.niaoPitches(files: List<Item>): Map<String, Double> =
    files.associate { f -> Paths.stem(f.audioPath) to (readInf(f)?.pitch ?: 0.0) }

/** The full set of syllables: the file of the settings, else pinyin. */
fun EditorState.niaoSyllables(): List<String> {
    val path = app.settings.niao.syllables.trim()
    if (path.isEmpty()) return NiaoNiao.PINYIN
    val text = runCatching { mlabeler.core.io.decodeGuess(PlatformFs.read(path), "UTF-8").first }.getOrNull() ?: return NiaoNiao.PINYIN
    return text.split(Regex("[\\s,;、，]+")).filter { it.isNotBlank() }
}

/** Checks the marks of [files]: syllables of the full set with no sound, pitches out of the range of the settings. */
fun EditorState.niaoCheck(files: List<Item>): NiaoNiao.Report {
    val n = app.settings.niao
    return NiaoNiao.check(niaoPitches(files), niaoSyllables(), mlabeler.core.format.NoteNames.parse(n.low),
        mlabeler.core.format.NoteNames.parse(n.high), n.spread.toDouble())
}

private fun EditorState.otoPath(files: List<Item>) = Paths.join(Paths.parent(files.first().audioPath), "oto.ini")

/** The entries of the oto.ini next to [files], with its encoding. */
private fun EditorState.readOto(files: List<Item>): Pair<List<mlabeler.core.format.OtoEntry>, String>? {
    val path = otoPath(files)
    if (!workspace.fs.exists(path)) return null
    val (text, cs) = mlabeler.core.io.decodeGuess(workspace.fs.read(path), "Shift_JIS")
    return mlabeler.core.format.OtoIni.read(text) to cs
}

/** True when an oto.ini lies next to [files]. */
fun EditorState.niaoHasOto(files: List<Item>): Boolean = files.isNotEmpty() && workspace.fs.exists(otoPath(files))

/** Saves what is open: the labels, or oto.ini in oto mode. */
private fun EditorState.saveAll() {
    saveOpen()
    if (oto.dirty) oto.save(quiet = true)
}

/**
 * Makes the .inf of [files] from the folder's oto.ini (the entry named as the file, else its first one); with [keep]
 * the files that have an .inf keep it.
 */
fun EditorState.niaoFromOto(files: List<Item>, keep: Boolean) {
    saveAll()
    workspace.updateState { it.copy(defaultFormat = LabelFormat.Inf) }
    runNiao(fromOtoT()) {
        val entries = readOto(files)?.first ?: error(noOtoT())
        val bySample = entries.groupBy { it.sample.lowercase() }
        var done = 0
        val failed = mutableListOf<String>()
        for ((k, f) in files.withIndex()) {
            toolkitBusy = progressT.format(k + 1, files.size, f.name)
            toolkitProgress = k.toDouble() / files.size
            if (keep && workspace.fs.exists(infPath(f))) continue
            val list = bySample[Paths.name(f.audioPath).lowercase()]
            if (list.isNullOrEmpty()) { failed += f.name + ": " + noEntryT(); continue }
            val e = list.firstOrNull { it.alias == Paths.stem(f.audioPath) } ?: list.first()
            try {
                val a = readAudio(f)
                val inf = withContext(Dispatchers.Default) { NiaoNiao.measure(NiaoNiao.fromOto(e, a.samples, a.sampleRate), a.samples, a.sampleRate) }
                workspace.fs.write(infPath(f), inf.write().encodeToByteArray())
                histories.remove(f.id)
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += f.name + ": " + (e.message ?: e.toString())
            }
        }
        fromOtoDoneT.format(done) + if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n")
    }
}

/**
 * Writes an oto.ini CV entry for every .inf of [files], named as the file: an entry of the same name and file is
 * replaced, the others stay. The old oto.ini is copied to .mlabeler/backup.
 */
fun EditorState.niaoToOto(files: List<Item>) {
    saveAll()
    runNiao(toOtoT()) {
        val path = otoPath(files)
        val (old, cs0) = readOto(files) ?: (emptyList<mlabeler.core.format.OtoEntry>() to "Shift_JIS")
        val made = mutableListOf<mlabeler.core.format.OtoEntry>()
        for (f in files) {
            val inf = readInf(f) ?: continue
            val rate = runCatching { readAudio(f).sampleRate }.getOrDefault(NiaoNiao.SAMPLE_RATE)
            made += NiaoNiao.toOto(Paths.name(f.audioPath), Paths.stem(f.audioPath), inf, rate)
        }
        if (made.isEmpty()) error(nothingT())
        val keys = made.map { it.sample.lowercase() to it.alias }.toSet()
        val entries = old.filter { (it.sample.lowercase() to it.alias) !in keys } + made
        val text = mlabeler.core.format.OtoIni.write(entries)
        // Chinese names do not fit Shift_JIS
        val cs = if (runCatching { mlabeler.core.io.decodeText(mlabeler.core.io.encodeText(text, cs0), cs0) == text }.getOrDefault(false)) cs0 else "UTF-8"
        if (workspace.fs.exists(path)) workspace.backupCopy(path)
        workspace.fs.write(path, mlabeler.core.io.encodeText(text, cs))
        oto.forget()
        toOtoDoneT.format(made.size)
    }
}

/** The NiaoNiao tools are offered: the folder has .inf marks, or the settings show them everywhere. */
fun EditorState.niaoShown(): Boolean = app.settings.niao.always || isNiaoFolder()

/** A recording labelled in NiaoNiao marks: new labels of the folder are .inf too. */
fun EditorState.isNiaoFolder(): Boolean = items.any { it.labelFormat == LabelFormat.Inf }

private val autoT = L("NiaoNiao marks", "Метки NiaoNiao")
private val measureT = L("Measuring pitch and levels", "Измерение высоты и громкости")
private val packT = L("Packing the voicebank", "Упаковка банка")
private val progressT = L("{0} of {1}: {2}", "{0} из {1}: {2}")
private val silentT = L("no sound found", "звук не найден")
private val autoDoneT = L("Marks placed in {0} recordings", "Метки расставлены в записях: {0}")
private val measuredT = L("Pitch and levels measured in {0} recordings", "Высота и громкость измерены в записях: {0}")
private val noInfT = L("no .inf", "нет .inf")
private val rateT = L("{0} Hz, NiaoNiao needs 44100 Hz", "{0} Гц, NiaoNiao нужны 44100 Гц")
private val nothingT = L("No recording has its marks yet", "Ни у одной записи ещё нет меток")
private val packedT = L("Packed {0} sounds into {1}", "Упаковано звуков: {0} в {1}")
private val noExtrasT = L("No readme.txt, charactor.txt or head.png next to the recordings: add them to the bank by hand.",
    "Рядом с записями нет readme.txt, charactor.txt или head.png — добавьте их в банк вручную.")
private val skippedT = L("Left out:", "Пропущены:")
private val flattenT = L("One pitch for every sound", "Один тон для каждого звука")
private val noPitchT = L("no pitch found", "высота не найдена")
private val flattenedT = L("{0} recordings sung again on one pitch; the old ones are in .mlabeler/backup", "Записей, заново спетых на одной высоте: {0}; старые лежат в .mlabeler/backup")
private val fromOtoT = L("Marks from oto.ini", "Метки из oto.ini")
private val noOtoT = L("No oto.ini next to the recordings", "Рядом с записями нет oto.ini")
private val noEntryT = L("no entry in oto.ini", "нет строки в oto.ini")
private val fromOtoDoneT = L("Marks taken from oto.ini for {0} recordings", "Метки из oto.ini перенесены в записи: {0}")
private val toOtoT = L("Writing oto.ini", "Запись oto.ini")
private val toOtoDoneT = L("{0} entries written to oto.ini; the old file is in .mlabeler/backup", "В oto.ini записано строк: {0}; старый файл лежит в .mlabeler/backup")
