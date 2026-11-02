package mlabeler.app.plugins

actual class PluginRuntime actual constructor() {
    actual fun stringFunction(name: String, body: (List<Any?>) -> String?) = Unit
    actual fun booleanFunction(name: String, body: (List<Any?>) -> Boolean) = Unit
    actual suspend fun evaluate(code: String, name: String): String = error("Native plugins are unavailable in the web edition")
    actual fun close() = Unit
}
