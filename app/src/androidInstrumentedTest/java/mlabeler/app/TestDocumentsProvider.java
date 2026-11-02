package mlabeler.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Lives in the test APK's separate process, which does not load the target APK's Kotlin runtime. */
public final class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS };

    private File root() {
        File folder = new File(getContext().getFilesDir(), "document-tests");
        folder.mkdirs();
        try { return folder.getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private File file(String id) throws FileNotFoundException {
        try {
            File root = root().getCanonicalFile();
            File file = new File(root, id.equals("root") ? "" : id.substring("root/".length())).getCanonicalFile();
            if (!file.equals(root) && !file.getPath().startsWith(root.getPath() + File.separator))
                throw new FileNotFoundException("Outside fixture");
            return file;
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
    }
    private String id(File file) {
        String relative = file.getPath().substring(root().getPath().length()).replace(File.separatorChar, '/');
        return "root" + relative;
    }
    private void row(MatrixCursor cursor, File file) {
        Object[] values = new Object[cursor.getColumnCount()];
        String[] names = cursor.getColumnNames();
        for (int i = 0; i < names.length; i++) {
            switch (names[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id(file); break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = file.getName(); break;
                case Document.COLUMN_MIME_TYPE: values[i] = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                case Document.COLUMN_SIZE: values[i] = file.length(); break;
                case Document.COLUMN_LAST_MODIFIED: values[i] = file.lastModified(); break;
                case Document.COLUMN_FLAGS: values[i] = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE |
                    Document.FLAG_SUPPORTS_RENAME | (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0); break;
                default: values[i] = null;
            }
        }
        cursor.addRow(values);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[0] : projection); }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File file = file(documentId);
        if (file.exists()) row(cursor, file);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] files = file(parentDocumentId).listFiles();
        if (files != null) for (File file : files) row(cursor, file);
        return cursor;
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        if (displayName.contains("/") || displayName.equals("..")) throw new FileNotFoundException("Invalid name");
        File result = new File(file(parentDocumentId), displayName);
        try {
            if (result.exists() || !(mimeType.equals(Document.MIME_TYPE_DIR) ? result.mkdir() : result.createNewFile()))
                throw new FileNotFoundException("Cannot create fixture file");
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return id(result);
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File from = file(documentId);
        if (from.getName().endsWith(".tmp") && displayName.equals("rename-fails.lab"))
            throw new FileNotFoundException("Simulated commit failure");
        File to = new File(from.getParentFile(), displayName);
        if (to.exists() || !from.renameTo(to)) throw new FileNotFoundException("Cannot rename fixture file");
        return id(to);
    }
    private boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return file.delete();
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = file(documentId);
        if (file.equals(root()) || !deleteTree(file)) throw new FileNotFoundException("Cannot delete fixture file");
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = file(documentId);
        if (mode.contains("w") && file.getName().startsWith(".write-fails.lab."))
            throw new FileNotFoundException("Simulated write failure");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }
}
