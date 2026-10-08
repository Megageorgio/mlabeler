package mlabeler.app

import mlabeler.app.state.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun order() {
        val v = listOf("0.1.0", "v0.2.0-alpha1", "0.2.0-beta2", "0.2.0", "0.2.0-beta10", "0.1.1").map { Version.parse(it)!! }
        assertEquals(listOf("0.1.0", "0.1.1", "0.2.0-alpha1", "0.2.0-beta2", "0.2.0-beta10", "0.2.0"),
            v.sorted().map { "${it.major}.${it.minor}.${it.patch}" + when (it.stage) { 0 -> "-alpha${it.n}"; 1 -> "-beta${it.n}"; else -> "" } })
        assertTrue("beta" in Version.allowed("alpha") && "stable" in Version.allowed("beta") && "beta" !in Version.allowed("stable"))
    }
}
