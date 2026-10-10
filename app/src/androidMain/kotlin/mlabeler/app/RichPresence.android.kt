package mlabeler.app

/** Phones and tablets: Discord can't be told what another program does. */
actual object RichPresence {
    actual val supported: Boolean = false
    actual fun show(info: PresenceInfo?) {}
}
