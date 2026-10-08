package mlabeler.app

actual object ScreenColor {
    actual val supported: Boolean = false
    actual fun pick(onPicked: (Int?) -> Unit) = onPicked(null)
}
