package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import mlabeler.app.i18n.S
import mlabeler.core.edit.History
import mlabeler.core.format.OtoAbsolute
import mlabeler.core.format.OtoEdits
import mlabeler.core.format.OtoEntry
import mlabeler.core.format.OtoIni
import mlabeler.core.format.OtoMarker
import mlabeler.core.io.Item
import mlabeler.core.io.Paths
import mlabeler.core.io.decodeGuess
import mlabeler.core.io.encodeText

/** One oto.ini: its entries with undo, and the charset it was read with. */
class OtoBook(val path: String, val charset: String, entries: List<OtoEntry>) {
    val history = History(entries)
    val entries: List<OtoEntry> get() = history.current
}

class OtoState(private val ed: EditorState, private val app: AppState) {
    private val books = mutableMapOf<String, OtoBook>()
    private val backedUp = mutableSetOf<String>()

    /** Index of the selected entry in the current book. */
    var selected by mutableStateOf<Int?>(null)
    /** Bumped on every change so the UI redraws. */
    var version by mutableIntStateOf(0)
        private set
    private var dragBase: OtoAbsolute? = null
    private var pendingSelect: Int? = null
    var dragPreview by mutableStateOf<OtoAbsolute?>(null)
        private set

    /** Another oto.ini shown for comparison (its entries and where it is). */
    var reference by mutableStateOf<Pair<String, List<OtoEntry>>?>(null)

    fun loadReference(folder: String) {
        val path = Paths.join(folder, "oto.ini")
        if (!ed.workspace.fs.exists(path)) { app.message(S.noOtoThere()); return }
        reference = folder to OtoIni.read(decodeGuess(ed.workspace.fs.read(path), "Shift_JIS").first)
        version++
    }

    fun referenceFor(e: OtoEntry): OtoEntry? = reference?.second?.firstOrNull { it.sample.equals(e.sample, true) && it.alias == e.alias }

    /** Entry [index] takes the values of the compared oto.ini (one undo step). */
    fun takeReference(index: Int) {
        val e = entries.getOrNull(index) ?: return
        val o = referenceFor(e) ?: return
        replace(index, o.copy(sample = e.sample, alias = e.alias))
    }

    /** Adds the entries only the compared oto.ini has, each after the last entry of its sample (one undo step). */
    fun addMissingFromReference(): Int {
        val other = reference?.second ?: return 0
        val list = entries.toMutableList()
        val have = list.map { it.sample.lowercase() + "|" + it.alias }.toMutableSet()
        var n = 0
        for (o in other) {
            if (!have.add(o.sample.lowercase() + "|" + o.alias)) continue
            val at = list.indexOfLast { it.sample.equals(o.sample, true) }.let { if (it < 0) list.size else it + 1 }
            list.add(at, o)
            n++
        }
        if (n > 0) commit(list)
        return n
    }

    /**
     * Copies every entry whose alias [pattern] matches, the copy named by [replacement] ($1… for groups), right after
     * it; copies whose alias the sample already has are left out. One undo step; returns how many were added.
     */
    fun duplicateMatching(pattern: Regex, replacement: String): Int {
        val list = mutableListOf<OtoEntry>()
        val have = entries.map { it.sample.lowercase() + "|" + it.alias }.toMutableSet()
        var n = 0
        for (e in entries) {
            list += e
            if (!pattern.containsMatchIn(e.alias)) continue
            val alias = pattern.replace(e.alias, replacement)
            if (alias == e.alias || !have.add(e.sample.lowercase() + "|" + alias)) continue
            list += e.copy(alias = alias)
            n++
        }
        if (n > 0) commit(list)
        return n
    }

    /** What the copies of [duplicateMatching] would be: alias → alias of the copy. */
    fun previewDuplicates(pattern: Regex, replacement: String): List<Pair<String, String>> {
        val have = entries.map { it.sample.lowercase() + "|" + it.alias }.toMutableSet()
        return entries.mapNotNull { e ->
            if (!pattern.containsMatchIn(e.alias)) return@mapNotNull null
            val alias = pattern.replace(e.alias, replacement)
            if (alias == e.alias || !have.add(e.sample.lowercase() + "|" + alias)) null else e.alias to alias
        }
    }

    /**
     * After a marker was moved by hand: the next entry, or the entry marked done, or both, as set in the settings
     * (only for the marker chosen there, when one is).
     */
    private fun afterEdit(m: OtoMarker, index: Int) {
        val s = app.settings.edit
        if (s.otoAfterEdit == "none") return
        if (s.otoAfterMarker.isNotEmpty() && s.otoAfterMarker != m.name) return
        val e = entries.getOrNull(index) ?: return
        if (s.otoAfterEdit == "done" || s.otoAfterEdit == "done-next") setMarks(e) { it.copy(done = true) }
        if (s.otoAfterEdit == "next" || s.otoAfterEdit == "done-next") if (index + 1 < entries.size) select(index + 1)
    }
    private var dragMarker: OtoMarker? = null

    /** Forgets loaded oto.ini files (after they were written by something else). */
    fun invalidate() {
        books.clear()
        selected = null
        version++
    }

    fun bookPath(item: Item) = Paths.join(Paths.parent(item.audioPath), "oto.ini")

    fun hasOto(item: Item) = ed.workspace.fs.exists(bookPath(item))

    /** The oto.ini of the current item's folder, read on first use; empty when the file does not exist yet. */
    fun book(): OtoBook? {
        val item = ed.item ?: return null
        return bookAt(bookPath(item))
    }

    private fun bookAt(path: String): OtoBook {
        return books.getOrPut(path) {
            val fs = ed.workspace.fs
            if (fs.exists(path)) {
                val bytes = fs.read(path)
                // an empty oto.ini gets the encoding UTAU expects
                val (text, cs) = if (bytes.isEmpty()) "" to "Shift_JIS" else decodeGuess(bytes, "Shift_JIS")
                OtoBook(path, cs, runCatching { OtoIni.read(text) }.getOrElse {
                    app.message(S.labelsUnreadable.format(it.message ?: ""), error = true)
                    emptyList()
                })
            } else {
                OtoBook(path, "Shift_JIS", emptyList())
            }
        }
    }

    val entries: List<OtoEntry> get() { version; return book()?.entries ?: emptyList() }

    /** Entries of the open audio file, with their indexes in the book. */
    fun entriesOfItem(): List<Pair<Int, OtoEntry>> {
        val item = ed.item ?: return emptyList()
        val name = Paths.name(item.audioPath)
        return entries.withIndex().filter { it.value.sample.equals(name, ignoreCase = true) }.map { it.index to it.value }
    }

    val dirty: Boolean get() { version; return books.values.any { it.history.dirty } }
    val canUndo: Boolean get() { version; return book()?.history?.canUndo == true }
    val canRedo: Boolean get() { version; return book()?.history?.canRedo == true }

    private val lengthMs get() = (ed.audio?.durationMs) ?: 0.0

    fun current(): OtoEntry? = selected?.let { entries.getOrNull(it) }

    fun absolute(e: OtoEntry): OtoAbsolute = e.absolute(lengthMs)

    /** Selects an entry; opens its audio file when it belongs to another one. */
    fun select(index: Int) {
        val e = entries.getOrNull(index) ?: return
        val item = ed.item
        if (item == null || !Paths.name(item.audioPath).equals(e.sample, ignoreCase = true)) {
            val dir = item?.let { Paths.parent(it.audioPath) }
            val target = ed.items.indexOfFirst { Paths.name(it.audioPath).equals(e.sample, ignoreCase = true) && (dir == null || Paths.parent(it.audioPath) == dir) }
            if (target >= 0 && target != ed.index) {
                pendingSelect = index
                selected = index
                ed.open(target)
                return
            }
        }
        selected = index
        version++
        val a = absolute(e)
        ed.reveal(a.left / 1000, a.right / 1000)
    }

    /** After a file is opened: select its first entry. */
    fun onItemOpened() {
        val p = pendingSelect
        pendingSelect = null
        selected = p ?: entriesOfItem().firstOrNull()?.first
        version++
        current()?.let { e -> val a = absolute(e); ed.reveal(a.left / 1000, a.right / 1000) }
    }

    // ----- marks per entry (done, star, tag), kept in the workspace state -----

    private fun markKey(e: OtoEntry) = "oto:" + Paths.parent(ed.workspace.relative(book()?.path ?: "")) + "/" + e.sample + "|" + e.alias

    fun marks(e: OtoEntry): mlabeler.core.io.ItemMarks { ed.marksVersion; return ed.workspace.itemState(markKey(e)).marks }

    fun setMarks(e: OtoEntry, transform: (mlabeler.core.io.ItemMarks) -> mlabeler.core.io.ItemMarks) {
        ed.workspace.updateItem(markKey(e)) { it.copy(marks = transform(it.marks)) }
        ed.bumpMarks()
    }

    fun step(delta: Int) {
        val n = entries.size
        if (n == 0) return
        select(((selected ?: -1) + delta).coerceIn(0, n - 1))
    }

    private fun commit(list: List<OtoEntry>) {
        book()?.history?.push(list)
        version++
    }

    private fun replace(index: Int, e: OtoEntry) = commit(entries.toMutableList().also { it[index] = e })

    // ----- markers -----

    fun beginDrag() {
        dragBase = current()?.let { absolute(it) }
    }

    fun dragTo(m: OtoMarker, timeSec: Double, invertLock: Boolean) {
        val base = dragBase ?: return
        dragMarker = m
        dragPreview = OtoEdits.move(base, m, timeSec * 1000, lengthMs, locked(m) != invertLock)
    }

    fun endDrag() {
        val p = dragPreview
        val i = selected
        val e = current()
        val m = dragMarker
        dragPreview = null
        dragBase = null
        dragMarker = null
        if (p != null && i != null && e != null) {
            replace(i, OtoEdits.set(e, p, lengthMs))
            if (m != null) afterEdit(m, i)
        }
    }

    /** Preutterance drags the whole set, like in most oto editors; Shift switches it. */
    fun locked(m: OtoMarker) = m == OtoMarker.Preutterance && app.settings.edit.otoLockedDrag

    fun setMarker(m: OtoMarker, timeSec: Double) {
        val i = selected ?: return
        val e = current() ?: return
        replace(i, OtoEdits.set(e, OtoEdits.move(absolute(e), m, timeSec * 1000, lengthMs, false), lengthMs))
        afterEdit(m, i)
    }

    /** Sets a value as written in oto.ini (relative ms). */
    fun setValue(field: OtoMarker, value: Double) {
        val i = selected ?: return
        val e = current() ?: return
        replace(
            i,
            when (field) {
                OtoMarker.Left -> e.copy(offset = value)
                OtoMarker.Overlap -> e.copy(overlap = value)
                OtoMarker.Preutterance -> e.copy(preutterance = value)
                OtoMarker.Consonant -> e.copy(consonant = value)
                OtoMarker.Right -> e.copy(cutoff = value)
            },
        )
    }

    fun rename(alias: String) {
        val i = selected ?: return
        val e = current() ?: return
        if (e.alias != alias) replace(i, e.copy(alias = alias))
    }

    fun duplicate() {
        val i = selected ?: return
        val e = current() ?: return
        val names = entries.map { it.alias }.toSet()
        var k = 2
        while ("${e.alias}_$k" in names) k++
        commit(entries.toMutableList().also { it.add(i + 1, e.copy(alias = "${e.alias}_$k")) })
        selected = i + 1
    }

    fun delete() {
        val i = selected ?: return
        commit(entries.toMutableList().also { it.removeAt(i) })
        selected = entries.indices.lastOrNull()?.let { minOf(i, it) }
    }

    /** New entry for the open file, around the cursor or at its start. */
    fun add() {
        val item = ed.item ?: return
        val t = (ed.cursor ?: ed.viewStart) * 1000
        val left = t.coerceIn(0.0, maxOf(0.0, lengthMs - 300))
        val e = OtoEntry(Paths.name(item.audioPath), Paths.stem(item.audioPath), left, 80.0, -250.0, 60.0, 20.0)
        val at = (entriesOfItem().lastOrNull()?.first ?: (entries.size - 1)) + 1
        commit(entries.toMutableList().also { it.add(at, e) })
        selected = at
    }

    /** Every oto.ini of the folder (one per subfolder with recordings) whose aliases [rename] changes: path, old → new. */
    fun previewEverywhere(rename: (String) -> String): List<Pair<String, String>> =
        ed.items.map { bookPath(it) }.distinct().filter { ed.workspace.fs.exists(it) }.flatMap { p ->
            bookAt(p).entries.mapNotNull { e -> rename(e.alias).takeIf { it != e.alias }?.let { e.alias to it } }
        }

    /** Renames aliases in every oto.ini of the folder (each file one undo step, saved with the others); returns how many. */
    fun renameEverywhere(rename: (String) -> String): Int {
        var n = 0
        for (p in ed.items.map { bookPath(it) }.distinct().filter { ed.workspace.fs.exists(it) }) {
            val b = bookAt(p)
            var k = 0
            val list = b.entries.map { e -> rename(e.alias).let { a -> if (a != e.alias) { k++; e.copy(alias = a) } else e } }
            if (k > 0) { b.history.push(list); n += k }
        }
        version++
        return n
    }

    /** Renames aliases with a regex in one undo step; returns how many changed. */
    fun renameAll(indexes: List<Int>, rename: (String) -> String): Int {
        var n = 0
        val list = entries.toMutableList()
        for (i in indexes) {
            val e = list.getOrNull(i) ?: continue
            val a = rename(e.alias)
            if (a != e.alias) { list[i] = e.copy(alias = a); n++ }
        }
        if (n > 0) commit(list)
        return n
    }

    /**
     * Writes entries for the samples of this folder automatically. [which]: 0 = the open file,
     * 1 = files without entries, 2 = every file. With [aligner] (a toolkit model id) syllables are placed by
     * forced alignment, otherwise from loudness and voicing.
     */
    fun autoOto(which: Int, settings: mlabeler.core.oto.AutoOtoSettings, replace: Boolean, aligner: String?, language: String?) {
        val item = ed.item ?: return
        val dir = Paths.parent(item.audioPath)
        val have = entries.map { it.sample.lowercase() }.toSet()
        val targets = when (which) {
            0 -> listOf(item)
            1 -> ed.items.filter { Paths.parent(it.audioPath) == dir && Paths.name(it.audioPath).lowercase() !in have }
            else -> ed.items.filter { Paths.parent(it.audioPath) == dir }
        }
        if (targets.isEmpty()) { app.message(S.nothingToDo()); return }
        ed.toolkitJob?.cancel()
        ed.toolkitJob = ed.workScope.launch {
            val made = mutableMapOf<String, List<OtoEntry>>()
            var failed = 0
            try {
                ed.beginToolkitWork("oto 0/${targets.size}")
                // read every sample first; with an aligner they all go to the toolkit as one job (a job per file
                // spends most of its time starting and finishing, not aligning)
                // numbered samples ("00117.wav") take their phonemes from the reclist they were recorded from
                val reclist = runCatching {
                    val f = ed.workspace.fs.list(dir).filter { p -> Paths.ext(p).equals("txt", true) && Paths.name(p).contains("reclist", true) }.minOrNull()
                    f?.let { p -> mlabeler.core.oto.Syllables.reclist(mlabeler.core.io.decodeGuess(ed.workspace.fs.read(p), "UTF-8").first) }
                }.getOrNull() ?: emptyList()
                val zeroBased = ed.items.any { x -> Paths.parent(x.audioPath) == dir && Paths.stem(x.audioPath).all { c -> c.isDigit() } && Paths.stem(x.audioPath).toIntOrNull() == 0 }
                // romaji names of a Japanese bank give kana aliases, as such banks have them
                val kana = mlabeler.core.oto.Syllables.romajiJapanese(ed.items.filter { x -> Paths.parent(x.audioPath) == dir }.map { x -> Paths.stem(x.audioPath) })
                class Sample(val name: String, val syl: List<mlabeler.core.oto.Syllable>, val audio: mlabeler.core.audio.Audio)
                val samples = mutableListOf<Sample>()
                for ((k, it) in targets.withIndex()) {
                    ed.toolkitBusy = readingT.format(k + 1, targets.size)
                    ed.toolkitProgress = if (aligner != null) 0.1 * k / targets.size else k.toDouble() / targets.size
                    val name = Paths.name(it.audioPath)
                    val stem = Paths.stem(it.audioPath)
                    val syl = mlabeler.core.oto.Syllables.fromName(mlabeler.core.oto.Syllables.reclistLine(stem, reclist, zeroBased) ?: stem, kana)
                    if (syl.isEmpty()) { failed++; continue }
                    val audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val bytes = ed.workspace.fs.read(it.audioPath)
                        if (mlabeler.core.audio.Wav.isWav(bytes)) mlabeler.core.audio.Wav.decode(bytes) else mlabeler.app.Platform.decodeAudio(it.audioPath)
                    } ?: run { failed++; null } ?: continue
                    if (aligner == null) {
                        val timings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            mlabeler.core.oto.AutoOto.segment(audio.samples, audio.sampleRate, syl, settings.bpm)
                        }
                        made[name.lowercase()] = mlabeler.core.oto.AutoOto.entries(name, timings, audio.durationMs, settings)
                    } else samples += Sample(name, syl, audio)
                }
                if (aligner != null && samples.isNotEmpty()) {
                    ed.toolkitBusy = mlabeler.app.toolkit.ToolkitManager.starting()
                    if (!app.toolkit.ensure()) throw mlabeler.app.toolkit.ToolkitException(app.toolkit.statusText())
                    val client = app.toolkit.client()
                    val ids = mutableListOf<Pair<String, List<String>>>()
                    for ((k, smp) in samples.withIndex()) {
                        ed.toolkitBusy = uploadingT.format(k + 1, samples.size)
                        ed.toolkitProgress = 0.1 + 0.2 * k / samples.size
                        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mlabeler.core.audio.Wav.encode16(smp.audio) }
                        ids += client.upload(smp.name, bytes) to mlabeler.core.oto.AutoOto.phonemesFor(smp.syl)
                    }
                    val job = client.alignPhonemes(ids, aligner, language)
                    val res = client.await(job, { ed.toolkitDetail = it }) { p, stage ->
                        ed.toolkitProgress = 0.3 + 0.7 * p
                        // the toolkit's own words: loading the model, aligning a file…
                        if (stage.isNotEmpty() && stage != ed.toolkitBusy) {
                            ed.toolkitBusy?.let { prev -> if (prev.substringBefore(' ') != stage.substringBefore(' ')) { ed.toolkitSteps.add(prev); while (ed.toolkitSteps.size > 6) ed.toolkitSteps.removeAt(0) } }
                            ed.toolkitBusy = stage
                        }
                    }
                    for ((k, smp) in samples.withIndex()) {
                        try {
                            val doc = mlabeler.app.toolkit.ToolkitClient.labelOf(res, 0.0, smp.audio.duration, k)
                            val phones = doc.tiers[doc.phonemeTierIndex()] as mlabeler.core.model.IntervalTier
                            val timings = mlabeler.core.oto.AutoOto.fromPhonemes(phones, smp.syl)
                            made[smp.name.lowercase()] = mlabeler.core.oto.AutoOto.entries(smp.name, timings, smp.audio.durationMs, settings)
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                }
                // one undo step for everything
                val list = entries.toMutableList()
                for ((sample, new) in made) {
                    val existing = list.filter { e -> e.sample.lowercase() == sample }
                    if (existing.isNotEmpty() && !replace) {
                        val aliases = existing.map { e -> e.alias }.toSet()
                        list += new.filter { e -> e.alias !in aliases }
                    } else {
                        val at = list.indexOfFirst { e -> e.sample.lowercase() == sample }
                        list.removeAll { e -> e.sample.lowercase() == sample }
                        list.addAll(if (at >= 0) at.coerceAtMost(list.size) else list.size, new)
                    }
                }
                commit(list)
                onItemOpened()
                app.message(S.autoOtoDone.format(made.values.sumOf { it.size }, made.size, failed))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.message(e.message ?: e.toString(), error = true)
            } finally {
                ed.toolkitBusy = null
                ed.toolkitProgress = null
            }
        }
    }

    /** Replaces all entries in one undo step (plugins). */
    fun replaceAll(list: List<OtoEntry>) {
        commit(list)
        if ((selected ?: 0) >= list.size) selected = list.indices.lastOrNull()
    }

    fun undo() { if (book()?.history?.undo() == true) version++ }
    fun redo() { if (book()?.history?.redo() == true) version++ }

    /** Forgets the oto.ini files read so far (they changed on disk); they are read again when needed. */
    fun forget() {
        books.clear()
        selected = null
        version++
        onItemOpened()
    }

    fun save(quiet: Boolean = false) {
        val fs = ed.workspace.fs
        for (b in books.values) {
            if (!b.history.dirty) continue
            try {
                if (fs.exists(b.path) && b.path !in backedUp) {
                    val rel = ed.workspace.relative(b.path).replace('/', '_')
                    fs.copy(b.path, Paths.join(Paths.join(ed.workspace.metaDir, "backup"), rel + "." + mlabeler.core.io.Workspace.timestamp()))
                    backedUp += b.path
                }
                fs.write(b.path, encodeText(OtoIni.write(b.entries), b.charset))
                b.history.markSaved()
                if (!quiet) app.message(S.saved.format(ed.workspace.relative(b.path)))
            } catch (e: Exception) {
                app.message(S.cannotSave.format(e.message ?: e.toString()), error = true)
            }
        }
        version++
    }
}

private val readingT = mlabeler.app.i18n.L("Reading {0} of {1}", "Чтение: {0} из {1}")
private val uploadingT = mlabeler.app.i18n.L("Sending {0} of {1} to the toolkit", "Передача тулкиту: {0} из {1}")
