package mlabeler.app

import java.io.File

actual object SelfUpdate {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The program's folder (mLabeler.exe, app, runtime), when it can be written to. */
    private val installDir: File? by lazy {
        if (!windows) return@lazy null
        runCatching {
            val dir = File(System.getProperty("java.home")).canonicalFile.parentFile ?: return@runCatching null
            if (!File(dir, "mLabeler.exe").isFile || !File(dir, "app").isDirectory || !File(dir, "runtime").isDirectory) return@runCatching null
            val probe = File(dir, ".mlabeler-write-test")
            if (runCatching { probe.writeText("x"); probe.delete() }.getOrDefault(false)) dir else null
        }.getOrNull()
    }

    private val updates get() = File(Platform.dataDir(), "update")
    private val readyFile get() = File(updates, "ready")
    private var done = false

    actual val supported: Boolean get() = installDir != null
    actual fun canUse(download: String?): Boolean = supported && download?.lowercase()?.endsWith(".zip") == true

    actual suspend fun prepare(download: String, tag: String, progress: (Float) -> Unit) {
        discard()
        val zip = File(updates, "$tag.zip")
        downloadFile(download, zip) { progress(it * 0.9f) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val into = File(updates, tag)
            into.deleteRecursively()
            java.util.zip.ZipFile(zip).use { z ->
                val all = z.entries().toList()
                for ((k, e) in all.withIndex()) {
                    val f = File(into, e.name).canonicalFile
                    if (!f.path.startsWith(into.canonicalPath)) continue
                    if (e.isDirectory) f.mkdirs()
                    else { f.parentFile?.mkdirs(); z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                    if (k % 50 == 0) progress(0.9f + 0.1f * k / all.size.coerceAtLeast(1))
                }
            }
            zip.delete()
            val root = newRoot(tag) ?: throw IllegalStateException("the archive has no mLabeler.exe")
            if (!File(root, "app").isDirectory || !File(root, "runtime").isDirectory) throw IllegalStateException("the archive is incomplete")
            readyFile.writeText(tag)
        }
        progress(1f)
    }

    /** The unpacked program folder of [tag] (the archive holds it as "mLabeler/"). */
    private fun newRoot(tag: String): File? {
        val into = File(updates, tag)
        return listOf(File(into, "mLabeler"), into).firstOrNull { File(it, "mLabeler.exe").isFile }
    }

    actual fun prepared(): String? = runCatching { readyFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && newRoot(it) != null }

    actual fun discard() {
        runCatching { updates.listFiles()?.forEach { if (it.name != "update.log") it.deleteRecursively() } }
    }

    actual val installsOnClose: Boolean = true

    actual fun install(restart: Boolean): String? {
        if (done) return null
        val dir = installDir ?: return "not supported"
        val tag = prepared() ?: return "nothing to install"
        val src = newRoot(tag) ?: return "nothing to install"
        val start = if (Platform.legacyWindows) File(dir, "mLabeler (Windows 7, 8.1).cmd") else File(dir, "mLabeler.exe")
        val script = updateScript(ProcessHandle.current().pid(), src, dir, updates, readyFile, tag, start, restart)
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .directory(updates).start()
            done = true
            null
        }.getOrElse { it.message ?: it.toString() }
    }
}

/**
 * The PowerShell script that puts the program folder [src] in place of [dir] once the process [pid] is gone (and
 * starts [start] when [restart]).
 */
internal fun updateScript(pid: Long, src: File, dir: File, updates: File, readyFile: File, tag: String, start: File, restart: Boolean): String {
    fun q(f: File) = "'" + f.path.replace("'", "''") + "'"
    // runs after this program is gone: the new folders are copied next to the old ones first, then swapped by
    // renaming, so a failed copy leaves the old version working; the settings, data, toolkit and the
    // "portable" marker are never touched
    return """
        ${'$'}log = ${q(File(updates, "update.log"))}
        function L(${'$'}m) { Add-Content -LiteralPath ${'$'}log -Value ((Get-Date).ToString('s') + ' ' + ${'$'}m) }
        ${'$'}p = $pid
        for (${'$'}i = 0; ${'$'}i -lt 600; ${'$'}i++) { if (-not (Get-Process -Id ${'$'}p -ErrorAction SilentlyContinue)) { break }; Start-Sleep -Milliseconds 300 }
        Start-Sleep -Milliseconds 700
        ${'$'}new = ${q(src)}; ${'$'}dst = ${q(dir)}
        ${'$'}ok = ${'$'}true
        try {
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}parts = @('app', 'runtime', 'natives') | Where-Object { Test-Path -LiteralPath (Join-Path ${'$'}new ${'$'}_) }
            foreach (${'$'}d in ${'$'}parts) {
                ${'$'}t = Join-Path ${'$'}dst (${'$'}d + '.new')
                if (Test-Path -LiteralPath ${'$'}t) { Remove-Item -LiteralPath ${'$'}t -Recurse -Force }
                Copy-Item -LiteralPath (Join-Path ${'$'}new ${'$'}d) -Destination ${'$'}t -Recurse -Force
            }
            foreach (${'$'}d in ${'$'}parts) {
                ${'$'}t = Join-Path ${'$'}dst ${'$'}d; ${'$'}o = Join-Path ${'$'}dst (${'$'}d + '.old')
                if (Test-Path -LiteralPath ${'$'}o) { Remove-Item -LiteralPath ${'$'}o -Recurse -Force }
                if (Test-Path -LiteralPath ${'$'}t) {
                    for (${'$'}k = 0; ${'$'}k -lt 40; ${'$'}k++) { try { Move-Item -LiteralPath ${'$'}t -Destination ${'$'}o; break } catch { if (${'$'}k -eq 39) { throw }; Start-Sleep -Milliseconds 500 } }
                }
                Move-Item -LiteralPath (${'$'}t + '.new') -Destination ${'$'}t
            }
            Get-ChildItem -LiteralPath ${'$'}new | Where-Object { -not ${'$'}_.PSIsContainer -and ${'$'}_.Name -ne 'portable' } | ForEach-Object { Copy-Item -LiteralPath ${'$'}_.FullName -Destination ${'$'}dst -Force }
            L ('updated to ' + ${q(File(tag))})
        } catch { ${'$'}ok = ${'$'}false; L ('update failed: ' + ${'$'}_) }
        ${'$'}ErrorActionPreference = 'Continue'
        foreach (${'$'}d in @('app', 'runtime', 'natives')) { Remove-Item -LiteralPath (Join-Path ${'$'}dst (${'$'}d + '.old')) -Recurse -Force -ErrorAction SilentlyContinue }
        if (${'$'}ok) { Remove-Item -LiteralPath ${q(readyFile)} -Force -ErrorAction SilentlyContinue; Remove-Item -LiteralPath ${q(File(updates, tag))} -Recurse -Force -ErrorAction SilentlyContinue }
        if (${if (restart) "\$true" else "\$false"}) { Start-Process -FilePath ${q(start)} -WorkingDirectory ${'$'}dst }
    """.trimIndent()
}
