package mlabeler.app

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import mlabeler.app.state.AppSettings
import mlabeler.app.state.OtoProfile
import mlabeler.core.io.Workspace
import mlabeler.core.oto.*

class OtoSettingsTest {
    @Test fun existingSettingsKeepNewFeaturesAtOriginalDefaults() {
        val old = Workspace.json.decodeFromString<AppSettings>("""{"language":"ru","setupDone":true,"otoNote":{"notes":2},"edit":{"saveOnSwitch":false}}""")
        assertEquals("ru", old.language)
        assertFalse(old.edit.saveOnSwitch)
        assertEquals(2, old.otoNote.notes)
        assertEquals(AutoOtoSettings(), old.autoOto)
        assertTrue(old.otoProfiles.isEmpty())
    }

    @Test fun customProfilesAndLastOptionsSurviveRestart() {
        val s = AutoOtoSettings(style = RecStyle.CVVC, aliases = AliasScript.Both, aliasSuffix = "_C4",
            rules = listOf(OtoRule(OtoEntryKind.Initial, offset = 10.0), OtoRule(OtoEntryKind.VV, cutoff = -20.0)))
        val original = AppSettings(autoOto = s, otoProfiles = listOf(OtoProfile("My CVVC", s)))
        val restored = Workspace.json.decodeFromString<AppSettings>(Workspace.json.encodeToString(original))
        assertEquals(original.autoOto, restored.autoOto)
        assertEquals(original.otoProfiles, restored.otoProfiles)
    }
}
