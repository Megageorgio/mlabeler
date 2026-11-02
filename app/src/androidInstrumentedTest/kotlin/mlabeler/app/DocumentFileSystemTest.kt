package mlabeler.app

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.util.UUID
import kotlin.test.*
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import mlabeler.core.io.Workspace

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DocumentFileSystemTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tree: Uri
    private lateinit var fs: DocumentFileSystem
    private lateinit var root: String
    private var previousDocuments: mlabeler.core.io.FileSystem? = null

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val parent = DocumentsContract.buildDocumentUri("pw.msan.mlabeler.saf.tests", "root")
        val project = DocumentsContract.createDocument(context.contentResolver, parent, Document.MIME_TYPE_DIR, "project-${UUID.randomUUID()}")!!
        tree = DocumentsContract.buildTreeDocumentUri("pw.msan.mlabeler.saf.tests", DocumentsContract.getDocumentId(project))
        fs = DocumentFileSystem(context, listOf(tree))
        root = fs.places().single().second
        previousDocuments = mlabeler.core.io.JvmFileSystems.documents
        mlabeler.core.io.JvmFileSystems.documents = fs
    }

    @After fun cleanUp() {
        mlabeler.core.io.JvmFileSystems.documents = previousDocuments
        try {
            if (::tree.isInitialized) DocumentsContract.deleteDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        } finally {
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun projectFilesAndStateSurviveReopeningTheDocumentTree() {
        fs.mkdirs("$root/sub/日本語")
        val audio = Audio(44100, FloatArray(4410) { 0.1f })
        fs.write("$root/sub/日本語/sa.wav", Wav.encode16(audio))
        fs.write("$root/sub/日本語/sa.lab", "0 1000000 a\n".encodeToByteArray())
        fs.write("$root/sub/日本語/oto.ini", "sa.wav=sa,0,50,-100,40,10\n".encodeToByteArray())
        val workspace = Workspace(root)
        assertEquals(1, workspace.scan().size)
        workspace.updateState { it.copy(kind = "oto", lastItem = "sub/日本語/sa.wav") }
        fs.write("$root/sub/日本語/oto.ini", "sa.wav=さ,0,55,-100,45,10\n".encodeToByteArray())
        // A new adapter has no URI cache, like a new process.
        val reopened = DocumentFileSystem(context, listOf(tree))
        assertEquals("sa.wav=さ,0,55,-100,45,10\n", reopened.read("$root/sub/日本語/oto.ini").decodeToString())
        assertEquals("oto", Workspace(root, reopened).state.kind)
        assertEquals(audio.samples.size, Wav.decode(reopened.read("$root/sub/日本語/sa.wav")).samples.size)
        assertTrue(reopened.list("$root/sub/日本語").none { it.endsWith(".tmp") || it.endsWith(".backup") })
    }

    @Test fun selectedSingleDocumentCanBeImportedWithoutAccessToItsSiblings() {
        fs.write("$root/song.wav", byteArrayOf(1, 2, 3))
        val treeDocument = fs.uri("$root/song.wav")
        val directDocument = DocumentsContract.buildDocumentUri("pw.msan.mlabeler.saf.tests",
            DocumentsContract.getDocumentId(treeDocument))
        for (selected in listOf(directDocument, treeDocument)) {
            val single = DocumentFileSystem(context, listOf(selected))
            val singlePath = "saf:/" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(selected.toString().toByteArray()).take(12)
                .joinToString("") { "%02x".format(it.toInt() and 255) } + "/song.wav"
            assertContentEquals(byteArrayOf(1, 2, 3), single.read(singlePath))
            assertFails { single.exists(mlabeler.core.io.Paths.withExt(singlePath, "lrc")) }
            fs.write("$root/imported.wav", single.read(singlePath))
            assertContentEquals(byteArrayOf(1, 2, 3), fs.read("$root/imported.wav"))
        }
    }

    @Test fun failedWritePreservesTheOriginal() {
        val uri = DocumentsContract.createDocument(context.contentResolver, fs.uri(root), "text/plain", "write-fails.lab")!!
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("original".encodeToByteArray()) }
        assertFails { fs.write("$root/write-fails.lab", "replacement".encodeToByteArray()) }
        assertEquals("original", fs.read("$root/write-fails.lab").decodeToString())
        assertEquals(1, fs.list(root).size)
    }

    @Test fun failedCommitRestoresTheBackup() {
        val uri = DocumentsContract.createDocument(context.contentResolver, fs.uri(root), "text/plain", "rename-fails.lab")!!
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("original".encodeToByteArray()) }
        assertFails { fs.write("$root/rename-fails.lab", "replacement".encodeToByteArray()) }
        assertEquals("original", fs.read("$root/rename-fails.lab").decodeToString())
        assertEquals(1, fs.list(root).size)
    }

    @Test fun renameDeleteAndMissingGrantsDoNotEscapeTheSelectedFolder() {
        fs.write("$root/a.lab", "a".encodeToByteArray())
        assertTrue(fs.rename("$root/a.lab", "$root/あ.lab"))
        assertFalse(fs.exists("$root/a.lab"))
        assertTrue(fs.delete("$root/あ.lab"))
        assertFalse(fs.delete(root))
        assertFails { fs.write("$root/../escape.lab", byteArrayOf(1)) }
        assertFails { DocumentFileSystem(context, emptyList()).read("$root/a.lab") }
    }
}
