@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package mlabeler.core.io

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.CFStringConvertIANACharSetNameToEncoding
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFStringRef
import platform.posix.memcpy
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.BooleanVar

private fun nsEncoding(charset: String): ULong {
    if (charset.equals("UTF-8", true)) return NSUTF8StringEncoding
    @Suppress("UNCHECKED_CAST")
    val cf = CFBridgingRetain(charset as NSString) as CFStringRef?
    val enc = CFStringConvertIANACharSetNameToEncoding(cf)
    return CFStringConvertEncodingToNSStringEncoding(enc)
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

actual fun decodeText(bytes: ByteArray, charset: String): String {
    if (bytes.isEmpty()) return ""
    if (charset.equals("UTF-8", true)) return bytes.decodeToString()
    val s = NSString.create(data = bytes.toNSData(), encoding = nsEncoding(charset))
    return s?.toString() ?: bytes.decodeToString()
}

actual fun encodeText(text: String, charset: String): ByteArray {
    if (charset.equals("UTF-8", true)) return text.encodeToByteArray()
    @Suppress("CAST_NEVER_SUCCEEDS")
    val data = (text as NSString).dataUsingEncoding(nsEncoding(charset), allowLossyConversion = true)
    return data?.toByteArray() ?: text.encodeToByteArray()
}

actual val PlatformFs: FileSystem = object : FileSystem {
    private val fm get() = NSFileManager.defaultManager

    override fun exists(path: String) = fm.fileExistsAtPath(path)
    override fun isDirectory(path: String): Boolean = memScoped {
        val isDir = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDir.ptr) && isDir.value
    }
    override fun list(path: String): List<String> =
        (fm.contentsOfDirectoryAtPath(path, null) ?: emptyList<Any?>()).map { Paths.join(path, it.toString()) }
    override fun read(path: String): ByteArray = fm.contentsAtPath(path)?.toByteArray() ?: error("Cannot read $path")
    override fun write(path: String, bytes: ByteArray) {
        mkdirs(Paths.parent(path))
        bytes.toNSData().writeToFile(path, atomically = true)
    }
    override fun mkdirs(path: String) {
        fm.createDirectoryAtPath(path, true, null, null)
    }
    override fun size(path: String): Long =
        ((fm.attributesOfItemAtPath(path, null)?.get(NSFileSize)) as? NSNumber)?.longLongValue ?: 0L
    override fun lastModified(path: String): Long =
        (((fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate)) as? NSDate)?.timeIntervalSince1970?.times(1000))?.toLong() ?: 0L
}
