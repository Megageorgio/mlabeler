package mlabeler.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mlabeler.app.AppInfo
import mlabeler.app.Platform

/** Which releases are offered: "stable", "beta" (and stable) or "alpha" (and beta and stable). */
@Serializable
data class UpdateSettings(
    val channel: String = "stable",
    /** Look for a newer version every time the program starts (and ask before anything else happens). */
    val checkOnStart: Boolean = true,
    /** A version the user chose to skip: not offered at start again (still shown in About). */
    val skipped: String = "",
    /** Where the program can (Windows, Android): download a new version by itself and put it in place. */
    val autoInstall: Boolean = true,
)

/**
 * Versions as 0.1.0, 0.2.0-beta3, 0.2.0-alpha1: an alpha comes before a beta of the same number, both before
 * the stable release.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val stage: Int, val n: Int) : Comparable<Version> {
    val channel: String get() = when (stage) { 0 -> "alpha"; 1 -> "beta"; else -> "stable" }
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.stage }, { it.n })

    companion object {
        private val re = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-.]?(alpha|beta|a|b|rc)\.?(\d+)?)?$""", RegexOption.IGNORE_CASE)

        fun parse(text: String): Version? {
            val m = re.find(text.trim()) ?: return null
            val (ma, mi, pa, kind, num) = m.destructured
            val stage = when (kind.lowercase()) { "alpha", "a" -> 0; "beta", "b", "rc" -> 1; else -> 2 }
            return Version(ma.toInt(), mi.toInt(), pa.ifEmpty { "0" }.toInt(), stage, num.ifEmpty { "0" }.toInt())
        }

        /** Channels a user of [channel] gets: alpha takes everything, beta takes beta and stable. */
        fun allowed(channel: String): Set<String> = when (channel) {
            "alpha" -> setOf("alpha", "beta", "stable")
            "beta" -> setOf("beta", "stable")
            else -> setOf("stable")
        }
    }
}

/** A release on GitHub: its version, its page and the file for this platform (null: the page is opened). */
data class Release(val version: Version, val tag: String, val page: String, val download: String?, val name: String, val sha256: String? = null)

/** Looks for newer versions of mLabeler among the releases of its repository. */
class Updater(private val app: AppState, private val scope: CoroutineScope) {
    var available by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** The result of the last check for the About page; empty before any. */
    var lastResult by mutableStateOf("")
        private set
    /** A release offered at start (a dialog asks what to do). */
    var offer by mutableStateOf<Release?>(null)

    val current: Version? = Version.parse(AppInfo.VERSION)

    /** Downloading a new version: 0..1; null when not downloading. */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** The tag of a downloaded version ready to be put in place. */
    var ready by mutableStateOf<String?>(null)
        private set
    /** The last thing that went wrong while downloading or installing. */
    var installError by mutableStateOf("")
        private set

    /** This release can be downloaded and put in place by the program itself. */
    fun canInstall(r: Release): Boolean = mlabeler.app.SelfUpdate.canUse(r.download)

    fun checkAtStart() {
        // a version downloaded earlier: ready at once, or left over from before the update and removed
        runCatching {
            mlabeler.app.SelfUpdate.prepared()?.let { tag ->
                val v = Version.parse(tag)
                if (v != null && current != null && v > current) ready = tag else mlabeler.app.SelfUpdate.discard()
            }
        }
        if (!app.settings.updates.checkOnStart) return
        scope.launch {
            delay(2500)
            val r = check()
            if (r != null && r.tag != app.settings.updates.skipped) {
                // where it can, the program fetches the new version by itself and only then asks
                if (ready != r.tag && app.settings.updates.autoInstall && canInstall(r)) prepare(r)
                offer = r
            }
        }
    }

    /** Downloads [r] and gets it ready to be put in place. */
    fun download(r: Release) { if (progress == null) scope.launch { prepare(r) } }

    private suspend fun prepare(r: Release) {
        val url = r.download ?: return
        if (progress != null) return
        progress = 0f
        installError = ""
        try {
            mlabeler.app.SelfUpdate.prepare(url, r.tag, r.sha256) { p -> progress = p }
            ready = r.tag
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            installError = failedDownload.format(e.message ?: e.toString())
        } finally {
            progress = null
        }
    }

    /**
     * Puts the downloaded version in place now: on Windows the program closes and the new version starts, on
     * Android the system installer opens.
     */
    fun installNow() {
        val err = mlabeler.app.SelfUpdate.install(restart = true)
        if (err != null) { installError = err; return }
        if (mlabeler.app.SelfUpdate.installsOnClose) app.requestQuit()
    }

    /** The program closes: a downloaded version is put in place (Windows), unless the user turned that off. */
    fun onExit() {
        val tag = ready ?: return
        if (!app.settings.updates.autoInstall || !mlabeler.app.SelfUpdate.installsOnClose) return
        if (tag == app.settings.updates.skipped) return
        runCatching { mlabeler.app.SelfUpdate.install(restart = false) }
    }

    fun checkNow() { scope.launch { check() } }

    private suspend fun check(): Release? {
        if (checking) return available
        checking = true
        try {
            val r = mlabeler.app.toolkit.httpRequest(
                "GET", "https://api.github.com/repos/$REPO/releases?per_page=40",
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "mLabeler/${AppInfo.VERSION}"), null, 20_000,
            )
            if (r.status == 404) { available = null; lastResult = upToDate(); return null } // nothing published yet
            if (r.status !in 200..299) throw IllegalStateException("GitHub: HTTP ${r.status}")
            val list = Json.parseToJsonElement(r.body.decodeToString()).jsonArray
            val allowed = Version.allowed(app.settings.updates.channel)
            val best = list.mapNotNull { it as? JsonObject }
                .filter { (it["draft"] as? JsonPrimitive)?.booleanOrNull != true }
                .mapNotNull { rel -> release(rel) }
                .filter { it.version.channel in allowed }
                .maxByOrNull { it.version }
            val cur = current
            available = best?.takeIf { cur == null || it.version > cur }
            lastResult = if (available != null) "" else upToDate()
            return available
        } catch (e: Exception) {
            lastResult = failed.format(e.message ?: e.toString())
            return null
        } finally {
            checking = false
        }
    }

    private fun release(o: JsonObject): Release? {
        val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: return null
        var v = Version.parse(tag) ?: return null
        // a pre-release without alpha or beta in its tag counts as beta
        if (v.stage == 2 && (o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) v = v.copy(stage = 1)
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
            ((it["name"] as? JsonPrimitive)?.content ?: "") to ((it["browser_download_url"] as? JsonPrimitive)?.content ?: "")
        }
        // GitHub gives each file's checksum as "sha256:<hex>"
        val digests = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associate {
            ((it["browser_download_url"] as? JsonPrimitive)?.content ?: "") to
                (it["digest"] as? JsonPrimitive)?.content?.takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:")
        }
        val os = Platform.name.lowercase()
        fun find(vararg parts: String) = assets.firstOrNull { (n, _) -> parts.all { p -> n.lowercase().contains(p) } }?.second
        val file = when {
            os == "android" -> find(".apk")
            // the fully portable build updates to the portable archive, the others never to it
            os.contains("win") && Platform.portableDir != null -> find("windows", "portable", ".zip")
            os.contains("win") -> assets.firstOrNull { (n, _) -> n.lowercase().let { it.contains("windows") && it.endsWith(".zip") && !it.contains("portable") } }?.second
                ?: find(".msi")
            os.contains("mac") -> find(".dmg")
            os == "ios" -> null
            else -> null // Linux: .deb or the portable archive, the person picks on the release page
        }
        return Release(v, tag, (o["html_url"] as? JsonPrimitive)?.content ?: "https://github.com/$REPO/releases", file,
            (o["name"] as? JsonPrimitive)?.content ?: tag, file?.let { digests[it] })
    }

    companion object {
        const val REPO = "Megageorgio/mlabeler"
        val upToDate = mlabeler.app.i18n.L("This is the newest version", "Установлена последняя версия")
        val failed = mlabeler.app.i18n.L("Couldn't check for updates: {0}", "Не удалось проверить обновления: {0}")
        val failedDownload = mlabeler.app.i18n.L("Couldn't download the update: {0}", "Не удалось скачать обновление: {0}")
    }
}
