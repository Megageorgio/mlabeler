package mlabeler.app.state

import mlabeler.app.Platform
import mlabeler.core.ds.PhonemeDict
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.Workspace

/** Phoneme dictionaries: built-in ones and JSON files in <data>/phonemes (same fields as [PhonemeDict]). */
object Dictionaries {
    fun dir(): String = Paths.join(Platform.dataDir(), "phonemes")

    fun user(): List<PhonemeDict> = runCatching {
        PlatformFs.list(dir()).filter { Paths.ext(it).equals("json", true) }.sorted().mapNotNull { p ->
            runCatching {
                val d = Workspace.json.decodeFromString(PhonemeDict.serializer(), PlatformFs.read(p).decodeToString())
                d.copy(name = d.name.ifBlank { Paths.stem(p) })
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun all(): List<PhonemeDict> = PhonemeDict.builtIn + user()

    fun byName(name: String): PhonemeDict = all().firstOrNull { it.name == name } ?: PhonemeDict.auto

    /** Writes a copy of [d] to the folder so it can be edited; returns its path. */
    fun saveCopy(d: PhonemeDict, name: String): String {
        val path = Paths.join(dir(), name.replace(Regex("[^\\p{L}\\p{N}_ -]"), "_") + ".json")
        PlatformFs.mkdirs(dir())
        PlatformFs.write(path, Workspace.json.encodeToString(PhonemeDict.serializer(), d.copy(name = name)).encodeToByteArray())
        return path
    }
}
