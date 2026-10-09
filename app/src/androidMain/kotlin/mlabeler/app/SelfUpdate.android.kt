package mlabeler.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File

actual object SelfUpdate {
    private val dir get() = File(AndroidContext.context.cacheDir, "update")
    private val readyFile get() = File(dir, "ready")
    private fun apk(tag: String) = File(dir, "$tag.apk")

    actual val supported: Boolean = true
    actual fun canUse(download: String?): Boolean = download?.lowercase()?.endsWith(".apk") == true

    actual suspend fun prepare(download: String, tag: String, sha256: String?, progress: (Float) -> Unit) {
        discard()
        downloadFile(download, apk(tag), sha256, progress)
        readyFile.writeText(tag)
    }

    actual fun prepared(): String? = runCatching { readyFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && apk(it).isFile }

    actual fun discard() { runCatching { dir.deleteRecursively() } }

    actual val installsOnClose: Boolean = false

    actual fun install(restart: Boolean): String? {
        val tag = prepared() ?: return "nothing to install"
        val ctx = AndroidContext.activity ?: AndroidContext.context
        return runCatching {
            // installing needs the user's permission for this program once
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return allowFirst()
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk(tag))
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { it.message ?: it.toString() }
    }

    private val allowFirst = mlabeler.app.i18n.L(
        "Allow mLabeler to install apps on the screen that opened, then come back and press Install again.",
        "Разрешите mLabeler устанавливать приложения на открывшемся экране, затем вернитесь и нажмите «Установить» ещё раз.")
}
