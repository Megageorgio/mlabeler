package mlabeler.core

import mlabeler.core.io.FileSystem
import mlabeler.core.io.Paths
import mlabeler.core.io.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A folder tree in memory. */
private class MemFs : FileSystem {
    val files = mutableMapOf<String, ByteArray>()
    override fun exists(path: String) = path in files || isDirectory(path)
    override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
    override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }
        .map { path + "/" + it.removePrefix("$path/").substringBefore('/') }.distinct()
    override fun read(path: String) = files[path] ?: throw IllegalStateException(path)
    override fun write(path: String, bytes: ByteArray) { files[path] = bytes }
    override fun mkdirs(path: String) {}
    override fun size(path: String) = (files[path]?.size ?: 0).toLong()
    override fun lastModified(path: String) = 0L
    override fun delete(path: String) = files.remove(path) != null
    override fun rename(from: String, to: String): Boolean {
        val moved = files.keys.filter { it == from || it.startsWith("$from/") }
        if (moved.isEmpty()) return false
        for (k in moved) files[to + k.removePrefix(from)] = files.remove(k)!!
        return true
    }
    fun text(path: String) = files.getValue(path).decodeToString()
}

class RecordingFilesTest {
    private fun folder(): MemFs = MemFs().apply {
        val wav = byteArrayOf(1, 2, 3)
        write("/v/ka.wav", wav); write("/v/ka.lab", "0 1 a\n".encodeToByteArray()); write("/v/ka_wav.frq", byteArrayOf(9))
        write("/v/ka.b.wav", wav); write("/v/ka.b.lab", "x".encodeToByteArray())
        write("/v/sa.wav", wav)
        write("/v/oto.ini", "ka.wav=- ka,1,2,3,4,5\r\nsa.wav=- sa,1,2,3,4,5\r\nka.wav=a ka,1,2,3,4,5\r\n".encodeToByteArray())
        write("/v/transcriptions.csv", "name,ph_seq,ph_dur\nka,k a,0.1 0.2\nsa,s a,0.1 0.2\n".encodeToByteArray())
    }

    @Test
    fun decomposedNamesBecomeComposed() {
        // ga written as ka + the combining voiced mark, as macOS stores it
        val ga = "\u304B\u3099"
        val composed = "\u304C"
        val fs = MemFs().apply {
            write("/v/$ga.wav", byteArrayOf(1))
            write("/v/sub$ga/a.wav", byteArrayOf(1))
            write("/v/oto.ini", "$ga.wav=- $ga,1,2,3,4,5\r\n".encodeToByteArray())
        }
        val ws = Workspace("/v", fs)
        Workspace.timestamp = { "T" }
        val (names, otos) = ws.decomposedNames()
        assertEquals(setOf("/v/sub$ga", "/v/$ga.wav"), names.toSet())
        assertEquals(listOf("/v/oto.ini"), otos)
        val (n, failed) = ws.normalizeNames()
        assertEquals(3, n); assertTrue(failed.isEmpty())
        assertTrue("/v/$composed.wav" in fs.files && "/v/sub$composed/a.wav" in fs.files)
        assertEquals("$composed.wav=- $composed,1,2,3,4,5\r\n", fs.text("/v/oto.ini"))
        assertTrue(ws.decomposedNames().first.isEmpty())
    }

    @Test
    fun renameTakesEverythingAlong() {
        val fs = folder()
        val ws = Workspace("/v", fs)
        val ka = ws.scan().first { it.id == "ka.wav" }
        assertEquals(setOf("/v/ka.lab", "/v/ka_wav.frq"), ws.companions(ka).toSet())
        val plan = ws.planRename(ka, "ta")
        assertEquals(2, plan.otoEntries); assertEquals(1, plan.csvRows); assertTrue(plan.problems.isEmpty())
        assertTrue(ws.planRename(ka, "sa").problems.isNotEmpty())
        val ta = ws.rename(ka, "ta")!!
        assertEquals("ta.wav", ta.id)
        assertTrue("/v/ta.lab" in fs.files && "/v/ta_wav.frq" in fs.files && "/v/ka.lab" !in fs.files)
        assertTrue("/v/ka.b.lab" in fs.files)
        assertEquals("ta.wav=- ka,1,2,3,4,5\r\nsa.wav=- sa,1,2,3,4,5\r\nta.wav=a ka,1,2,3,4,5\r\n", fs.text("/v/oto.ini"))
        assertTrue(fs.text("/v/transcriptions.csv").contains("\nta,k a"))
    }

    @Test
    fun trashKeepsACopy() {
        val fs = folder()
        val ws = Workspace("/v", fs)
        Workspace.timestamp = { "T" }
        val ka = ws.scan().first { it.id == "ka.wav" }
        val dest = ws.trash(ka)
        assertFalse("/v/ka.wav" in fs.files)
        assertTrue(Paths.join(dest, "ka.wav") in fs.files && Paths.join(dest, "ka.lab") in fs.files && Paths.join(dest, "oto.ini") in fs.files)
        assertEquals("sa.wav=- sa,1,2,3,4,5\r\n", fs.text("/v/oto.ini"))
        assertFalse(fs.text("/v/transcriptions.csv").contains("\nka,"))
        assertEquals(listOf("ka.b.wav", "sa.wav"), ws.scan().map { it.id })
    }
}
