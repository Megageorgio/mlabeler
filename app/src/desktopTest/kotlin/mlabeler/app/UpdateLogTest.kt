package mlabeler.app

import mlabeler.app.toolkit.decodeLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateLogTest {
    private val text = "uv.exe : Resolved 32 packages in 994ms\r\nInstalled 1 executable: mvt\r\n"

    @Test
    fun powershellUtf16WithMark() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val lines = bytes.decodeLog().lines()
        assertTrue(lines.any { it.startsWith("Installed ") && "executable" in it })
    }

    @Test
    fun utf16WithoutMark() {
        assertEquals(text, text.toByteArray(Charsets.UTF_16LE).decodeLog())
    }

    @Test
    fun utf8AsItIs() {
        val t = "Установлено: mvt\nInstalled 1 executable: mvt\n"
        assertEquals(t, t.encodeToByteArray().decodeLog())
    }
}
