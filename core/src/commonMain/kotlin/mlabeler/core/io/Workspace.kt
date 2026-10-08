package mlabeler.core.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mlabeler.core.edit.Edits
import mlabeler.core.format.AudacityLabels
import mlabeler.core.format.FormatException
import mlabeler.core.format.HtkLab
import mlabeler.core.format.LabelFormat
import mlabeler.core.format.TextGridFormat
import mlabeler.core.model.LabelDoc

val ALL_AUDIO_EXTENSIONS = setOf("wav", "flac", "mp3", "ogg", "m4a", "aac", "opus", "aif", "aiff")

/** Audio files that are listed; WAV only unless the user turns the others on. */
object AudioFormats {
    var accepted: Set<String> = setOf("wav")
}

val AUDIO_EXTENSIONS: Set<String> get() = AudioFormats.accepted

@Serializable
data class ItemMarks(val done: Boolean = false, val star: Boolean = false, val tag: String = "")

@Serializable
data class ItemState(
    val marks: ItemMarks = ItemMarks(),
    /** Last view start and zoom. */
    val viewStart: Double = 0.0,
    val pixelsPerSecond: Double = 0.0,
)

@Serializable
data class WorkspaceState(
    val items: Map<String, ItemState> = emptyMap(),
    val lastItem: String? = null,
    /** Format for files that have no labels yet. */
    val defaultFormat: LabelFormat = LabelFormat.Lab,
    /** Folders with labels relative to the workspace, searched in addition to the audio folder. */
    val labelFolders: List<String> = emptyList(),
    /** Folders (absolute) whose labels are shown next to these for comparison. */
    val compareFolders: List<String> = emptyList(),
    /** What is labelled in this folder: "labels" (lab/TextGrid tiers) or "oto"; null = detect. */
    val kind: String? = null,
    /** Phoneme dictionary used to group phonemes into notes ("" = guess from letters). */
    val dictionary: String = "",
    /** Last DiffSinger export folder. */
    val exportFolder: String = "",
    /** The file list grouped by subfolder (otherwise one flat list), and the folded subfolders. */
    val tree: Boolean = false,
    val folded: Set<String> = emptySet(),
)

/** One audio file and where its labels are. [id] is the audio path relative to the workspace root. */
data class Item(val id: String, val audioPath: String, val labelPath: String?, val labelFormat: LabelFormat?) {
    val name: String get() = Paths.stem(audioPath)
}

class Workspace(val root: String, val fs: FileSystem = PlatformFs) {
    val metaDir = Paths.join(root, ".mlabeler")
    private val statePath = Paths.join(metaDir, "workspace.json")
    private val backedUp = mutableSetOf<String>()

    var state: WorkspaceState = loadState()
        private set

    var items: List<Item> = emptyList()
        private set

    private fun loadState(): WorkspaceState = try {
        if (fs.exists(statePath)) json.decodeFromString(WorkspaceState.serializer(), fs.read(statePath).decodeToString()) else WorkspaceState()
    } catch (e: Exception) {
        WorkspaceState()
    }

    fun updateState(transform: (WorkspaceState) -> WorkspaceState) {
        state = transform(state)
        try {
            fs.write(statePath, json.encodeToString(WorkspaceState.serializer(), state).encodeToByteArray())
        } catch (_: Exception) {
            // read-only folders still work, the state is just not kept
        }
    }

    fun updateItem(id: String, transform: (ItemState) -> ItemState) =
        updateState { it.copy(items = it.items + (id to transform(it.items[id] ?: ItemState()))) }

    fun itemState(id: String) = state.items[id] ?: ItemState()

    /** Scans the folder (three levels deep, skipping hidden folders). */
    fun scan(): List<Item> {
        val audio = listAudio()
        loadCsvs()
        items = audio.map { path ->
            val (label, fmt) = findLabels(path)
            Item(relative(path), path, label, fmt)
        }.sortedWith(compareBy(naturalOrder()) { it.id.lowercase() })
        return items
    }

    /** True when audio files were added, removed or renamed since the last [scan] (only lists folders). */
    fun audioChanged(): Boolean {
        val now = listAudio().mapTo(HashSet()) { relative(it) }
        return now.size != items.size || items.any { it.id !in now }
    }

    /** Audio files of the folder, three levels deep, hidden folders skipped. */
    fun listAudio(): List<String> {
        val audio = mutableListOf<String>()
        fun walk(dir: String, depth: Int) {
            val children = try { fs.list(dir) } catch (_: Exception) { emptyList() }
            for (c in children.sorted()) {
                val n = Paths.name(c)
                if (n.startsWith(".")) continue
                if (fs.isDirectory(c)) {
                    if (depth < 3) walk(c, depth + 1)
                } else if (Paths.ext(c) in AUDIO_EXTENSIONS) {
                    audio += c
                }
            }
        }
        walk(root, 0)
        return audio
    }

    fun relative(path: String): String = path.removePrefix(root).trimStart('/', '\\').replace('\\', '/')

    private fun candidates(audioPath: String): List<String> {
        val dir = Paths.parent(audioPath)
        val stem = Paths.stem(audioPath)
        val dirs = mutableListOf(dir)
        // common layouts: wav/ + lab/ (or TextGrid/) side by side
        val parent = Paths.parent(dir)
        if (Paths.name(dir).lowercase() in setOf("wav", "wavs", "audio")) {
            for (n in listOf("lab", "labs", "label", "labels", "TextGrid", "textgrid", "textgrids")) dirs += Paths.join(parent, n)
        }
        for (f in state.labelFolders) dirs += Paths.join(root, f)
        return dirs.flatMap { d -> listOf("TextGrid", "textgrid", "lab", "ds", "txt").map { Paths.join(d, "$stem.$it") } }
    }

    /** transcriptions.csv files of the folder (root and three levels down), parsed. */
    private val csvRows = mutableMapOf<String, MutableList<mlabeler.core.format.DsCsv.Row>>()
    /** The same files as read, cell by cell, so writing one row leaves every other row and column untouched. */
    private val csvLines = mutableMapOf<String, List<List<String>>>()

    private fun loadCsvs() {
        csvRows.clear()
        csvLines.clear()
        fun walk(dir: String, depth: Int) {
            val children = try { fs.list(dir) } catch (_: Exception) { emptyList() }
            for (c in children) {
                if (Paths.name(c).startsWith(".")) continue
                if (fs.isDirectory(c)) { if (depth < 3) walk(c, depth + 1) }
                else if (Paths.name(c).equals("transcriptions.csv", ignoreCase = true)) {
                    runCatching {
                        val text = decodeGuess(fs.read(c), "UTF-8").first
                        csvRows[c] = mlabeler.core.format.DsCsv.read(text).toMutableList()
                        csvLines[c] = mlabeler.core.format.Csv.parse(text)
                    }
                }
            }
        }
        walk(root, 0)
    }

    /** The csv whose folder (or the folder above the audio's) lists [stem]. */
    private fun csvFor(audioPath: String, stem: String): String? {
        val dir = Paths.parent(audioPath)
        return csvRows.entries.firstOrNull { (path, rows) ->
            val d = Paths.parent(path)
            (d == dir || d == Paths.parent(dir)) && rows.any { it.name == stem }
        }?.key
    }

    private fun findLabels(audioPath: String): Pair<String?, LabelFormat?> {
        for (c in candidates(audioPath)) {
            if (!fs.exists(c)) continue
            when (Paths.ext(c)) {
                "textgrid" -> return c to LabelFormat.TextGrid
                "ds" -> return c to LabelFormat.Ds
                "lab" -> {
                    val text = try { decodeGuess(fs.read(c), "UTF-8").first } catch (_: Exception) { "" }
                    if (text.isBlank() || HtkLab.isTimed(text)) return c to LabelFormat.Lab
                }
                "txt" -> {
                    val text = try { decodeGuess(fs.read(c), "UTF-8").first } catch (_: Exception) { "" }
                    if (AudacityLabels.looksLike(text)) return c to LabelFormat.Audacity
                }
            }
        }
        csvFor(audioPath, Paths.stem(audioPath))?.let { return it to LabelFormat.DsCsv }
        return null to null
    }

    /** Reads labels of [item]; files without labels get one empty phoneme tier. */
    fun readLabels(item: Item, duration: Double): LabelDoc {
        val path = item.labelPath ?: return LabelDoc.empty(duration)
        val text = decodeGuess(fs.read(path), "UTF-8").first
        val doc = when (item.labelFormat) {
            LabelFormat.TextGrid -> TextGridFormat.read(text)
            LabelFormat.Lab -> if (text.isBlank()) LabelDoc.empty(duration) else HtkLab.read(text, duration = duration)
            LabelFormat.Audacity -> AudacityLabels.read(text, "phones", duration)
            LabelFormat.Ds -> mlabeler.core.format.DsFile.read(text, duration)
            LabelFormat.DsCsv -> csvRows[path]?.firstOrNull { it.name == item.name }?.doc ?: LabelDoc.empty(duration)
            null -> throw FormatException("Unknown label format")
        }
        return Edits.fitToDuration(doc, duration)
    }

    /** Labels for [stem] in [dir] (TextGrid, lab or Audacity txt), or null. */
    fun readLabelsIn(dir: String, stem: String, duration: Double): LabelDoc? {
        for (ext in listOf("TextGrid", "textgrid", "lab", "txt")) {
            val p = Paths.join(dir, "$stem.$ext")
            if (!fs.exists(p)) continue
            val text = runCatching { decodeGuess(fs.read(p), "UTF-8").first }.getOrNull() ?: continue
            val doc = runCatching {
                when (ext.lowercase()) {
                    "textgrid" -> TextGridFormat.read(text)
                    "lab" -> if (HtkLab.isTimed(text)) HtkLab.read(text, duration = duration) else null
                    else -> if (AudacityLabels.looksLike(text)) AudacityLabels.read(text, "phones", duration) else null
                }
            }.getOrNull() ?: continue
            return Edits.fitToDuration(doc, duration)
        }
        return null
    }

    /**
     * Writes labels in the item's format (or [format]) and returns the updated item.
     * The previous file is copied to .mlabeler/backup once per session.
     */
    fun writeLabels(item: Item, doc: LabelDoc, duration: Double, format: LabelFormat? = null): Item {
        val fmt = (format ?: item.labelFormat ?: state.defaultFormat).let { f ->
            if (f == LabelFormat.DsCsv && item.labelFormat != LabelFormat.DsCsv) LabelFormat.Lab else f
        }
        val path = if (item.labelPath != null && fmt == item.labelFormat) item.labelPath else Paths.withExt(item.audioPath, fmt.extension)
        if (fs.exists(path) && path !in backedUp) {
            val stamp = timestamp()
            val name = item.id.replace('/', '_').substringBeforeLast('.') + ".$stamp." + Paths.ext(path)
            try {
                fs.copy(path, Paths.join(Paths.join(metaDir, "backup"), name))
                backedUp += path
            } catch (_: Exception) {
            }
        }
        val text = when (fmt) {
            LabelFormat.Lab -> HtkLab.write(doc)
            LabelFormat.TextGrid -> TextGridFormat.write(doc, duration)
            LabelFormat.Audacity -> AudacityLabels.write(doc)
            LabelFormat.Ds -> mlabeler.core.format.DsFile.write(doc, if (fs.exists(path)) runCatching { fs.read(path).decodeToString() }.getOrNull() else null)
            LabelFormat.DsCsv -> {
                // replace this recording's row, keep the others as they were
                val rows = csvRows.getOrPut(path) { mutableListOf() }
                val k = rows.indexOfFirst { it.name == item.name }
                val row = mlabeler.core.format.DsCsv.Row(item.name, doc)
                if (k >= 0) rows[k] = row else rows += row
                val lines = mlabeler.core.format.DsCsv.update(csvLines[path] ?: emptyList(), item.name, doc)
                csvLines[path] = lines
                mlabeler.core.format.Csv.write(lines)
            }
        }
        fs.write(path, text.encodeToByteArray())
        val updated = item.copy(labelPath = path, labelFormat = fmt)
        items = items.map { if (it.id == item.id) updated else it }
        return updated
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
        var timestamp: () -> String = { kotlin.time.Clock.System.now().toEpochMilliseconds().toString() }
    }
}

/** Natural order: "a2" < "a10". */
fun naturalOrder(): Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return@Comparator na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return@Comparator c
        } else {
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i++
            j++
        }
    }
    (a.length - i) - (b.length - j)
}
