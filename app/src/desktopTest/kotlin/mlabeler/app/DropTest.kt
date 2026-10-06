package mlabeler.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.core.io.Workspace
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DropTest {
    private fun wav(f: File) {
        f.parentFile.mkdirs()
        f.writeBytes(mlabeler.core.audio.Wav.encode16(mlabeler.core.audio.Audio(8000, FloatArray(800))))
    }

    @Test
    fun droppedRecordingsAreCopiedWithTheirLabels() {
        val root = Files.createTempDirectory("ws").toFile()
        wav(File(root, "a.wav"))
        val other = Files.createTempDirectory("other").toFile()
        wav(File(other, "a.wav"))
        File(other, "a.lab").writeText("0 1000000 x\n")
        wav(File(other, "b.wav"))
        File(other, "notes.pdf").writeText("x")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val app = AppState(scope)
        val ed = EditorState(Workspace(root.path), app, scope)
        ed.scan()
        val n = ed.addFiles(listOf(File(other, "a.wav").path, File(other, "b.wav").path, File(other, "notes.pdf").path))
        assertEquals(2, n)
        // the taken name gets " (2)", its label follows it
        assertTrue(File(root, "a (2).wav").exists() && File(root, "a (2).lab").exists() && File(root, "b.wav").exists())
        assertEquals(listOf("a (2).wav", "a.wav", "b.wav"), ed.items.map { File(it.audioPath).name }.sorted())
        // a recording already in the folder is just opened
        assertEquals(0, ed.addFiles(listOf(File(root, "b.wav").path)))
        assertEquals("b.wav", File(ed.item!!.audioPath).name)
        assertTrue(ed.contains(File(root, "a.wav").path) && !ed.contains(File(other, "a.wav").path))
    }
}
