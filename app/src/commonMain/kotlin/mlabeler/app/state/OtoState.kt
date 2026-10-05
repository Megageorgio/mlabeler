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

    fun bookPath(item: Item) = Paths.join(Paths.parent(item.audioPath), "oto.ini")

    fun hasOto(item: Item) = ed.workspace.fs.exists(bookPath(item))

    /** The oto.ini of the current item's folder, read on first use; empty when the file does not exist yet. */
    fun book(): OtoBook? {
        val item = ed.item ?: return null
        val path = bookPath(item)
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
        dragPreview = OtoEdits.move(base, m, timeSec * 1000, lengthMs, locked(m) != invertLock)
    }

    fun endDrag() {
        val p = dragPreview
        val i = selected
        val e = current()
        dragPreview = null
        dragBase = null
        if (p != null && i != null && e != null) replace(i, OtoEdits.set(e, p, lengthMs))
    }

    /** Preutterance drags the whole set, like in most oto editors; Shift switches it. */
    fun locked(m: OtoMarker) = m == OtoMarker.Preutterance && app.settings.edit.otoLockedDrag

    fun setMarker(m: OtoMarker, timeSec: Double) {
        val i = selected ?: return
        val e = current() ?: return
        replace(i, OtoEdits.set(e, OtoEdits.move(absolute(e), m, timeSec * 1000, lengthMs, false), lengthMs))
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
                for ((k, it) in targets.withIndex()) {
                    ed.toolkitBusy = "oto ${k + 1}/${targets.size}"
                    val name = Paths.name(it.audioPath)
                    val syl = mlabeler.core.oto.Syllables.fromName(Paths.stem(it.audioPath))
                    if (syl.isEmpty()) { failed++; continue }
                    val audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val bytes = ed.workspace.fs.read(it.audioPath)
                        if (mlabeler.core.audio.Wav.isWav(bytes)) mlabeler.core.audio.Wav.decode(bytes) else mlabeler.app.Platform.decodeAudio(it.audioPath)
                    } ?: run { failed++; null } ?: continue
                    val timings = if (aligner != null) {
                        val t = app.settings.toolkit
                        val client = mlabeler.app.toolkit.ToolkitClient(t.url, t.token)
                        val id = client.upload(name, mlabeler.core.audio.Wav.encode16(audio))
                        val job = client.align(id, aligner, language, mlabeler.core.oto.AutoOto.phonemesFor(syl).joinToString(" "), phonemes = true)
                        val res = client.await(job) { _, _ -> }
                        val doc = mlabeler.app.toolkit.ToolkitClient.labelOf(res, 0.0, audio.duration)
                        val phones = doc.tiers[doc.phonemeTierIndex()] as mlabeler.core.model.IntervalTier
                        mlabeler.core.oto.AutoOto.fromPhonemes(phones, syl)
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            mlabeler.core.oto.AutoOto.segment(audio.samples, audio.sampleRate, syl, settings.bpm)
                        }
                    }
                    made[name.lowercase()] = mlabeler.core.oto.AutoOto.entries(name, timings, audio.durationMs, settings)
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
