package mlabeler.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mlabeler.app.plugins.BuiltInPlugins
import mlabeler.app.plugins.Plugin
import mlabeler.app.plugins.Plugins
import mlabeler.core.format.OtoEntry
import mlabeler.core.model.IntervalTier
import mlabeler.core.model.LabelDoc
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginsTest {
    private fun builtin(name: String) = BuiltInPlugins.all.first { it.first.name == name }.let { Plugin(it.first, "", it.second, true) }

    @Test
    fun replaceLabels() = runBlocking {
        val doc = LabelDoc(listOf(IntervalTier("phones", listOf(0.0, 0.5, 1.0), listOf("pau", "a"))))
        val r = Plugins.run(builtin("replace-labels"), mapOf("table" to JsonPrimitive("pau=SP"), "tier" to JsonPrimitive("")), doc, null, "x", 1.0)
        assertEquals(listOf("SP", "a"), (r.doc!!.tiers[0] as IntervalTier).texts)
        assertEquals("1 replaced", r.report)
    }

    @Test
    fun otoSetValue() = runBlocking {
        val e = listOf(OtoEntry("a.wav", "- ka", 100.0, 50.0, -300.0, 80.0, 20.0), OtoEntry("a.wav", "a ki", 400.0, 50.0, -300.0, 80.0, 20.0))
        val r = Plugins.run(builtin("oto-set-value"), mapOf("value" to JsonPrimitive("preutterance"), "expr" to JsonPrimitive("v + 10"),
            "alias" to JsonPrimitive("^a "), "keep" to JsonPrimitive(false)), null, e, "", 0.0)
        assertEquals(listOf(80.0, 90.0), r.entries!!.map { it.preutterance })
    }
}
