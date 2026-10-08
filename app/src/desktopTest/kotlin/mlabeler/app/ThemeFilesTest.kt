package mlabeler.app

import mlabeler.app.theme.ThemeFiles
import mlabeler.app.theme.Themes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeFilesTest {
    @Test
    fun themeFilesAreNamedAfterTheThemes() {
        val data = kotlin.io.path.createTempDirectory("mlab").toFile()
        val dir = File(data, "themes").apply { mkdirs() }
        // a file of an earlier version takes its theme's name
        File(dir, "my-theme-1.json").writeText(ThemeFiles.encode(Themes.modernDark, "my-theme-1", "Night"))
        ThemeFiles.load(data.path)
        assertEquals(listOf("Night.json"), dir.list()!!.sorted())
        // a copy is named after its name; the same name gets a number
        ThemeFiles.copy(data.path, Themes.modernLight, "Night")
        assertEquals(listOf("Night 2.json", "Night.json"), dir.list()!!.sorted())
        // renaming moves the file, the id stays
        val t = Themes.custom.first { it.tokens.id == "my-theme-1" }
        ThemeFiles.save(data.path, t, t.tokens, "Dusk: test")
        assertTrue(File(dir, "Dusk_ test.json").exists())
        assertEquals("my-theme-1", Themes.custom.first { it.name == "Dusk: test" }.tokens.id)
        assertEquals(2, dir.list()!!.size)
        data.deleteRecursively()
    }
}
