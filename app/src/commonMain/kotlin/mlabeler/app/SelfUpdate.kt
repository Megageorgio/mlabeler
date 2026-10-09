package mlabeler.app

/**
 * Putting a new version in place by the program itself: on Windows (the zip, the portable zip and the .msi
 * installation, all of which are a folder the program may write to) the new folder is downloaded and unpacked in
 * the data folder and swapped in after the program closes; on Android the .apk is downloaded and the system's
 * installer opened. Elsewhere the release page opens in the browser as before.
 */
expect object SelfUpdate {
    /** This installation can update itself at all. */
    val supported: Boolean
    /** A release file this installation can update itself from. */
    fun canUse(download: String?): Boolean
    /** Downloads and unpacks [download], checked against [sha256] when known; [progress] goes 0..1. Throws on failure. */
    suspend fun prepare(download: String, tag: String, sha256: String?, progress: (Float) -> Unit)
    /** The tag of an update downloaded earlier and ready to be put in place, if any. */
    fun prepared(): String?
    fun discard()
    /**
     * Puts the prepared update in place. Windows: after the program closes (it should close right after this when
     * [restart]); the new version starts then when [restart]. Android: opens the installer. Returns an error or a
     * hint for the user, null when it went ahead.
     */
    fun install(restart: Boolean): String?
    /** True when [install] needs the program to close (Windows). */
    val installsOnClose: Boolean
}
