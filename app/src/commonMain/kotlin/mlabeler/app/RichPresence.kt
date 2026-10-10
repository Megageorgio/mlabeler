package mlabeler.app

/** What the program is doing, as the Discord profile shows it: two lines and since when ([startEpochSec]; null = no clock). */
data class PresenceInfo(val details: String, val state: String, val startEpochSec: Long?)

/** Discord Rich Presence through the Discord program running on the same computer (computers only). */
expect object RichPresence {
    val supported: Boolean
    /** Shows [info] in the profile, or clears it when null. Returns at once; talking to Discord happens in the background. */
    fun show(info: PresenceInfo?)
}
