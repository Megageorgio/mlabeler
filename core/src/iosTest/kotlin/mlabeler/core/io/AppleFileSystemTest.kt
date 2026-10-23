@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mlabeler.core.io

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

class AppleFileSystemTest {
    private fun inFolder(test: (String) -> Unit) {
        val folder = NSTemporaryDirectory() + "mlabeler-" + NSUUID().UUIDString
        PlatformFs.mkdirs(folder)
        try { test(folder) } finally { PlatformFs.delete(folder) }
    }

    @Test fun emptyFileCanReplaceExistingContents() = inFolder { root ->
        val path = "$root/oto.ini"
        PlatformFs.write(path, byteArrayOf(1, 2, 3))
        PlatformFs.write(path, byteArrayOf())
        assertTrue(PlatformFs.exists(path))
        assertEquals(0L, PlatformFs.size(path))
        assertTrue(PlatformFs.read(path).isEmpty())
    }

    @Test fun failedWritesAreReportedInsteadOfPretendingToSave() = inFolder { root ->
        val blocker = "$root/file"
        PlatformFs.write(blocker, byteArrayOf(7))
        assertFails { PlatformFs.write("$blocker/child", byteArrayOf(1)) }
        assertFails { PlatformFs.write(root, byteArrayOf(1)) }
        assertContentEquals(byteArrayOf(7), PlatformFs.read(blocker))
    }
}
