package mlabeler.app.plugins

/** Native script engine kept out of browser dependencies. */
expect class PluginRuntime() {
    fun stringFunction(name: String, body: (List<Any?>) -> String?)
    fun booleanFunction(name: String, body: (List<Any?>) -> Boolean)
    suspend fun evaluate(code: String, name: String): String
    fun close()
}
