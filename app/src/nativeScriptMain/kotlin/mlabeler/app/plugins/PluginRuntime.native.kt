package mlabeler.app.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers

actual class PluginRuntime actual constructor() {
    private val engine = QuickJs.create(Dispatchers.Default)
    actual fun stringFunction(name: String, body: (List<Any?>) -> String?) {
        engine.function(name) { args -> body(args.toList()) }
    }
    actual fun booleanFunction(name: String, body: (List<Any?>) -> Boolean) {
        engine.function(name) { args -> body(args.toList()) }
    }
    actual suspend fun evaluate(code: String, name: String): String = engine.evaluate(code, name, false)
    actual fun close() = engine.close()
}
