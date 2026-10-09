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

// NiaoNiao voicebanks: the marks of each recording in its .inf, measuring, packing into voice.d and inf.d, unpacking.

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

/** The recordings of the folder (the open recording's folder) that are not 44.1 kHz mono: NiaoNiao reads only those. */
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

/** Unpacks the bank in [bank] (voice.d and inf.d) into [out]: a .wav and an .inf per sound. Returns how many. */
suspend fun unpackNiaoBank(bank: String, out: String): Int = withContext(Dispatchers.Default) {
    val sounds = NiaoNiao.unpack(PlatformFs.read(Paths.join(bank, "inf.d")).decodeToString(), PlatformFs.read(Paths.join(bank, "voice.d")))
    PlatformFs.mkdirs(out)
    for (s in sounds) {
        PlatformFs.write(Paths.join(out, s.name + ".wav"), NiaoNiao.wav(s.samples))
        PlatformFs.write(Paths.join(out, s.name + ".inf"), s.inf.write().encodeToByteArray())
    }
    for ((from, to) in listOf("readme.txt" to "readme.txt", "charactor.txt" to "charactor.txt", "head.d" to "head.png")) {
        val src = Paths.join(bank, from)
        if (PlatformFs.exists(src)) runCatching { PlatformFs.copy(src, Paths.join(out, to)) }
    }
    sounds.size
}

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
