package mlabeler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mlabeler.app.ui.Commands
import mlabeler.app.ui.MItem
import mlabeler.app.ui.MSep
import mlabeler.app.ui.MSub
import mlabeler.app.ui.availableMenuEntries

class PlayDistributionTest {
    @Test fun remoteCommandsCannotBeReachedThroughShortcutsOrThePalette() {
        val remote = listOf(Commands.autolabel, Commands.compareModels, Commands.g2p, Commands.modelWords,
            Commands.otoNote, Commands.resynthWorld, Commands.resynthNsf, Commands.resynthSave)
        for (cmd in remote) {
            assertEquals(AppInfo.TOOLKIT_ENABLED, cmd.available, cmd.id)
            assertEquals(AppInfo.TOOLKIT_ENABLED, cmd in Commands.all, cmd.id)
        }
        assertTrue(Commands.autoOto in Commands.all)
        assertTrue(Commands.quickLabel in Commands.all)
        assertTrue(Commands.refine in Commands.all)
        assertTrue(Commands.record in Commands.all)
    }

    @Test fun hiddenMenuCommandsLeaveNoEmptySubmenusOrSeparators() {
        val visible = MItem("Local editing") {}
        val entries = availableMenuEntries(listOf(MSep, MItem("Remote", available = false) {}, MSep,
            MSub("Remote submenu", listOf(MItem("Remote", available = false) {})), visible, MSep, MSep))
        assertEquals(listOf(visible), entries)
    }
}
