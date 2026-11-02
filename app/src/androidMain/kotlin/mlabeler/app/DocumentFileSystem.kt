package mlabeler.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import mlabeler.core.io.FileSystem
import mlabeler.core.io.Paths
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Virtual paths preserve the core's filenames while all project IO goes through the granted document tree. */
internal class DocumentFileSystem(private val context: Context,
    initialUris: List<Uri> = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri },
) : FileSystem {
    private val resolver get() = context.contentResolver
    private data class Node(val uri: Uri, val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val directory get() = mime == Document.MIME_TYPE_DIR
    }
    private data class Mount(val path: String, val tree: Uri?, val root: Node)
    private val mounts = ConcurrentHashMap<String, Mount>()
    private val nodes = ConcurrentHashMap<String, Node>()
    private val mutationLock = Any()
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    init {
        // Persisted grants survive process death/reboots. A removed or revoked grant never falls back to a raw path.
        initialUris.forEach {
            runCatching { mount(it) }.onFailure { error -> android.util.Log.w("mLabeler", "Cannot restore document access", error) }
        }
    }

    fun places(): List<Pair<String, String>> = mounts.values.filter { it.root.directory }
        .map { it.root.name to it.path }.sortedBy { it.first }

    fun accept(data: Intent, directory: Boolean): String {
        val uri = data.data ?: throw IOException("No document was selected")
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            directory && flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            throw IOException("Choose a folder with read and write access")
        }
        resolver.takePersistableUriPermission(uri, flags)
        return mount(uri).path
    }

    private fun mount(uri: Uri): Mount {
        val tree = uri.takeIf { DocumentsContract.isTreeUri(it) }
        val document = if (tree != null) DocumentsContract.buildDocumentUriUsingTree(tree,
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse { DocumentsContract.getTreeDocumentId(tree) }) else uri
        val root = query(document) ?: throw FileNotFoundException("The selected document is unavailable")
        checkName(root.name)
        val id = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).take(12)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = "saf:/$id/${root.name}"
        val mount = Mount(path, tree, root)
        mounts[id] = mount
        nodes[path] = root
        return mount
    }

    fun uri(path: String): Uri = requireNode(path).uri
    fun initialUri(path: String?): Uri? = path?.let { runCatching { requireNode(it).uri }.getOrNull() }

    private fun checkName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Unsupported document name: $name"
        }
    }

    private fun mountFor(path: String): Mount {
        require(path.startsWith("saf:/")) { "Not a document path" }
        val id = path.removePrefix("saf:/").substringBefore('/')
        val mount = mounts[id] ?: throw FileNotFoundException("Folder access is unavailable; select the project folder again")
        require(path == mount.path || path.startsWith(mount.path + "/")) { "Path is outside the selected folder" }
        path.removePrefix(mount.path).split('/').filter { it.isNotEmpty() }.forEach(::checkName)
        return mount
    }

    private fun query(uri: Uri): Node? = resolver.query(uri, columns, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        Node(uri, c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) 0 else c.getLong(3),
            if (c.isNull(4)) 0 else c.getLong(4))
    }

    private fun children(mount: Mount, parent: Node): List<Node> {
        if (!parent.directory || mount.tree == null) return emptyList()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(mount.tree, parent.id)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Cannot list the selected folder")
        return cursor.use { c -> buildList {
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                checkName(name)
                add(Node(DocumentsContract.buildDocumentUriUsingTree(mount.tree, id), id, name, c.getString(2),
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
            }
        } }
    }

    private fun node(path: String): Node? {
        val mount = mountFor(path)
        nodes[path]?.let { return it }
        val parent = node(Paths.parent(path)) ?: return null
        val child = children(mount, parent).firstOrNull { it.name == Paths.name(path) } ?: return null
        nodes[path] = child
        return child
    }

    private fun requireNode(path: String) = node(path) ?: throw FileNotFoundException(path)
    private fun invalidate() { nodes.clear(); mounts.values.forEach { nodes[it.path] = it.root } }

    override fun exists(path: String): Boolean = node(path)?.let { query(it.uri) != null } ?: false
    override fun isDirectory(path: String): Boolean = node(path)?.let { query(it.uri)?.directory == true } ?: false
    override fun size(path: String): Long = query(requireNode(path).uri)?.size ?: throw FileNotFoundException(path)
    override fun lastModified(path: String): Long = query(requireNode(path).uri)?.modified ?: throw FileNotFoundException(path)
    override fun list(path: String): List<String> {
        val mount = mountFor(path)
        val found = children(mount, requireNode(path))
        // Refresh after changes made outside the app, as well as changes through this adapter.
        nodes.keys.filter { it.startsWith(path + "/") }.forEach { nodes.remove(it) }
        return found.map { Paths.join(path, it.name).also { p -> nodes[p] = it } }
    }
    override fun read(path: String): ByteArray = resolver.openInputStream(requireNode(path).uri)?.use { it.readBytes() }
        ?: throw IOException("Cannot read $path")

    override fun mkdirs(path: String): Unit = synchronized(mutationLock) {
        val mount = mountFor(path)
        if (path == mount.path) return@synchronized
        val existing = node(path)
        if (existing != null) {
            check(existing.directory) { "A file already exists at $path" }
            return@synchronized
        }
        val parent = Paths.parent(path)
        mkdirs(parent)
        val uri = DocumentsContract.createDocument(resolver, requireNode(parent).uri, Document.MIME_TYPE_DIR, Paths.name(path))
            ?: throw IOException("Cannot create folder $path")
        val made = query(uri) ?: throw IOException("Cannot read the new folder $path")
        check(made.name == Paths.name(path)) { "The provider changed the folder name" }
        nodes[path] = made
    }

    /** Never truncate the only copy. Providers without safe rename support are refused, preserving pending edits. */
    override fun write(path: String, bytes: ByteArray) = synchronized(mutationLock) {
        mountFor(path)
        val parentPath = Paths.parent(path)
        mkdirs(parentPath)
        val parent = requireNode(parentPath)
        val old = node(path)
        check(old?.directory != true) { "Cannot write over a directory" }
        val name = Paths.name(path)
        val token = UUID.randomUUID().toString()
        var temporary: Uri? = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", ".$name.$token.tmp")
            ?: throw IOException("Cannot create a temporary file in $parentPath")
        var backup: Uri? = null
        try {
            val stream = resolver.openOutputStream(temporary!!, "wt") ?: throw IOException("Cannot write $path")
            stream.use { it.write(bytes) }
            if (old != null) {
                backup = DocumentsContract.renameDocument(resolver, old.uri, ".$name.$token.backup")
                    ?: throw IOException("This storage provider does not support safe replacement")
            }
            val saved = DocumentsContract.renameDocument(resolver, temporary!!, name)
                ?: throw IOException("This storage provider does not support safe file names")
            temporary = null
            if (query(saved)?.name != name) throw IOException("The storage provider changed the file name")
            backup?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            backup = null
        } catch (failure: Exception) {
            backup?.let {
                try {
                    // If the provider renamed the temporary file but changed its name, keep both files.
                    val restored = DocumentsContract.renameDocument(resolver, it, name)
                    if (restored == null || query(restored)?.name != name)
                        throw IOException("The original is retained as a .backup file in $parentPath")
                } catch (restore: Exception) {
                    failure.addSuppressed(restore)
                }
            }
            throw failure
        } finally {
            temporary?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            invalidate()
        }
    }

    override fun delete(path: String): Boolean = synchronized(mutationLock) {
        if (path == mountFor(path).path) return@synchronized false
        val target = node(path) ?: return@synchronized false
        val deleted = DocumentsContract.deleteDocument(resolver, target.uri)
        invalidate()
        deleted
    }

    override fun rename(from: String, to: String): Boolean = synchronized(mutationLock) {
        val source = mountFor(from)
        val destination = mountFor(to)
        if (source.path != destination.path || from == source.path || Paths.parent(from) != Paths.parent(to)) return@synchronized false
        if (node(to) != null) return@synchronized false
        val entry = node(from) ?: return@synchronized false
        val renamed = DocumentsContract.renameDocument(resolver, entry.uri, Paths.name(to))
        invalidate()
        renamed != null && query(renamed)?.name == Paths.name(to)
    }
}
