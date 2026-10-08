package mlabeler.app

/** Picking a colour anywhere on the screen (computers only). */
expect object ScreenColor {
    val supported: Boolean
    /** Freezes the screen, lets the user click a pixel and returns its ARGB colour, or null when cancelled (Esc). */
    fun pick(onPicked: (Int?) -> Unit)
}
