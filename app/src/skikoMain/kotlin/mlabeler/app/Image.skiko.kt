package mlabeler.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun imageFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap {
    // BGRA byte order = little-endian ARGB ints
    val bytes = ByteArray(width * height * 4)
    var j = 0
    for (p in pixels) {
        bytes[j] = p.toByte()
        bytes[j + 1] = (p shr 8).toByte()
        bytes[j + 2] = (p shr 16).toByte()
        bytes[j + 3] = (p ushr 24).toByte()
        j += 4
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun ImageBitmap.readyToDraw(): ImageBitmap {
    // every draw wraps the bitmap in a Skia image, which copies all its pixels while the bitmap may still change
    asSkiaBitmap().setImmutable()
    return this
}
