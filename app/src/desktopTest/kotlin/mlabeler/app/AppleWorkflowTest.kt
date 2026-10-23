package mlabeler.app

import kotlinx.coroutines.*
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.*
import java.nio.file.Files
import kotlin.test.*

class AppleWorkflowTest {
    @Test fun anUnreadableReclistShowsAnErrorWithoutStartingABrokenRecorder() = runBlocking {
        val root = Files.createTempDirectory("mlabeler-reclist-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            root.resolve("reclist.txt").mkdir()
            val app = withContext(Dispatchers.Main) { AppState(scope).also { it.openRecorder(root.path) } }
            withTimeout(10000) {
                while (withContext(Dispatchers.Main) { app.message == null }) delay(10)
            }
            withContext(Dispatchers.Main) {
                assertTrue(app.message!!.error)
                assertNull(app.recorder)
                assertTrue(scope.isActive)
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun unfinishedLabelDraftIsSavedOnBackgroundPreservation() = runBlocking {
        val root = Files.createTempDirectory("mlabeler-draft-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            root.resolve("a.wav").writeBytes(Wav.encode16(Audio(8000, FloatArray(800))))
            val app = withContext(Dispatchers.Main) { AppState(scope).also { it.openFolder(root.path) } }
            withTimeout(10000) {
                while (withContext(Dispatchers.Main) { app.editor?.doc == null }) delay(10)
            }
            withContext(Dispatchers.Main) {
                val ed = app.editor!!
                val interval = mlabeler.core.edit.IntervalRef(0, 0)
                ed.editingText = interval
                ed.editingDraft = interval to "draft"
                assertTrue(app.preserveSession())
                assertTrue(root.resolve("a.lab").readText().contains("draft"))
                assertTrue(app.closeFolder())
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun aDroppedRecordingStillOpensAfterTheFolderScanBecameAsynchronous() = runBlocking {
        val root = Files.createTempDirectory("mlabeler-open-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            for (name in listOf("a", "b")) root.resolve("$name.wav").writeBytes(Wav.encode16(Audio(8000, FloatArray(800))))
            val app = withContext(Dispatchers.Main) {
                AppState(scope).also { assertTrue(it.openDropped(listOf(root.resolve("b.wav").path))) }
            }
            withTimeout(10000) {
                while (withContext(Dispatchers.Main) { app.openingFolder }) delay(10)
            }
            withContext(Dispatchers.Main) {
                assertEquals("b.wav", Paths.name(app.editor!!.item!!.audioPath))
                assertTrue(app.closeFolder())
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun unreadableOtoProducesAReadOnlyPlaceholderInsteadOfCrashingOrOverwritingIt() {
        val root = Files.createTempDirectory("mlabeler-oto-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            root.resolve("a.wav").writeBytes(Wav.encode16(Audio(8000, FloatArray(800))))
            val oto = root.resolve("oto.ini").apply { writeText("a.wav=alias,0,1,2,3,4\n") }
            val original = oto.readBytes()
            val fs = object : FileSystem by PlatformFs {
                override fun read(path: String): ByteArray {
                    if (path.endsWith("oto.ini")) error("Access denied")
                    return PlatformFs.read(path)
                }
            }
            val ed = EditorState(Workspace(root.path, fs), AppState(scope), scope)
            ed.scan()
            val book = ed.oto.book()!!
            assertNotNull(book.readError)
            assertTrue(book.entries.isEmpty())
            assertTrue(ed.oto.save())
            assertContentEquals(original, oto.readBytes())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
