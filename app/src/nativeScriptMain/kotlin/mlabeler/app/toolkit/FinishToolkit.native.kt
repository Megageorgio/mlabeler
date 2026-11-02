package mlabeler.app.toolkit

actual fun finishToolkit(block: suspend () -> Unit) { kotlinx.coroutines.runBlocking { block() } }
