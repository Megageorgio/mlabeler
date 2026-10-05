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

val AUDIO_EXTENSIONS = setOf("wav", "flac", "mp3", "ogg", "m4a", "aac", "opus", "aif", "aiff")

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
    /** What is labelled in this folder: "labels" (lab/TextGrid tiers) or "oto"; null = detect. */
    val kind: String? = null,
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

    /** Scans the folder (two levels deep, skipping hidden folders). */
    fun scan(): List<Item> {
        val audio = mutableListOf<String>()
        fun walk(dir: String, depth: Int) {
            val children = try { fs.list(dir) } catch (_: Exception) { emptyList() }
            for (c in children.sorted()) {
                val n = Paths.name(c)
                if (n.startsWith(".")) continue
                if (fs.isDirectory(c)) {
                    if (depth < 2) walk(c, depth + 1)
                } else if (Paths.ext(c) in AUDIO_EXTENSIONS) {
                    audio += c
                }
            }
        }
        walk(root, 0)
        items = audio.map { path ->
            val (label, fmt) = findLabels(path)
            Item(relative(path), path, label, fmt)
        }.sortedWith(compareBy(naturalOrder()) { it.id.lowercase() })
        return items
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
        return dirs.flatMap { d -> listOf("TextGrid", "textgrid", "lab", "txt").map { Paths.join(d, "$stem.$it") } }
    }

    private fun findLabels(audioPath: String): Pair<String?, LabelFormat?> {
        for (c in candidates(audioPath)) {
            if (!fs.exists(c)) continue
            when (Paths.ext(c)) {
                "textgrid" -> return c to LabelFormat.TextGrid
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
            null -> throw FormatException("Unknown label format")
        }
        return Edits.fitToDuration(doc, duration)
    }

    /**
     * Writes labels in the item's format (or [format]) and returns the updated item.
     * The previous file is copied to .mlabeler/backup once per session.
     */
    fun writeLabels(item: Item, doc: LabelDoc, duration: Double, format: LabelFormat? = null): Item {
        val fmt = format ?: item.labelFormat ?: state.defaultFormat
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
