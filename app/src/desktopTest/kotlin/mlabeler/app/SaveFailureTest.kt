package mlabeler.app

import kotlinx.coroutines.*
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.edit.History
import mlabeler.core.io.*
import mlabeler.core.model.LabelDoc
import java.nio.file.Files
import kotlin.test.*

class SaveFailureTest {
    @Test fun allPendingFilesSurviveFailedSaveAndCanBeRetried() {
        val root = Files.createTempDirectory("mlabeler-save-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (name in listOf("a", "b")) root.resolve("$name.wav").writeBytes(Wav.encode16(Audio(8000, FloatArray(800))))
            var deny = true
            val fs = object : FileSystem by PlatformFs {
                override fun write(path: String, bytes: ByteArray) {
                    if (deny && path.endsWith(".lab")) error("Storage full")
                    PlatformFs.write(path, bytes)
                }
            }
            val ws = Workspace(root.path, fs)
            val ed = EditorState(ws, AppState(scope), scope)
            ed.items = ws.scan()
            for (item in ed.items) ed.histories[item.id] = History(LabelDoc.empty(0.1)).apply { push(LabelDoc.empty(0.2)) }
            assertFalse(ed.savePendingChanges())
            assertTrue(ed.histories.values.all { it.dirty })
            assertFalse(root.resolve("a.lab").exists())
            deny = false
            assertTrue(ed.savePendingChanges())
            assertTrue(ed.histories.values.none { it.dirty })
            assertTrue(root.resolve("a.lab").isFile && root.resolve("b.lab").isFile)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
