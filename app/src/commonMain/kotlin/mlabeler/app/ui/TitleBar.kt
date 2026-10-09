package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.state.AppState
import mlabeler.app.theme.T

/**
 * A window whose title the program draws itself (computers, when "menus in the window title" is on).
 * The desktop part supplies it; on phones and with the setting off there is none.
 */
interface WindowFrame {
    val maximized: Boolean
    /** macOS: the system's own round buttons stay at the left; the program leaves room for them. */
    val systemButtons: Boolean
    fun minimize()
    fun toggleMaximize()
    fun close()
    /** Pressing and moving inside moves the window; a double click maximizes or restores it. */
    @Composable
    fun DragArea(modifier: Modifier, content: @Composable () -> Unit)
}

val LocalWindowFrame = staticCompositionLocalOf<WindowFrame?> { null }

/** The window title drawn by the program: menus, the name of the open file, the window buttons. */
@Composable
fun TitleBar(app: AppState, frame: WindowFrame) {
    val c = T.c
    val ed = app.editor
    val title = ed?.item?.name?.let { "$it — mLabeler" } ?: "mLabeler"
    Row(Modifier.fillMaxWidth().height(32.dp).background(c.panel), verticalAlignment = Alignment.CenterVertically) {
        if (frame.systemButtons) Spacer(Modifier.width(76.dp)) else Spacer(Modifier.width(6.dp))
        if (ed != null && app.settings.menuBar && app.recorder == null && app.karaoke == null) MenuBar(app, ed, inTitle = true)
        frame.DragArea(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!frame.systemButtons) {
            CaptionButton(Icons.winMinimize, minimizeT()) { frame.minimize() }
            CaptionButton(if (frame.maximized) Icons.winRestore else Icons.winMaximize, if (frame.maximized) restoreT() else maximizeT()) { frame.toggleMaximize() }
            CaptionButton(Icons.close, closeWindowT(), danger = true) { frame.close() }
        }
    }
}

@Composable
private fun CaptionButton(icon: ImageVector, hint: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = T.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animatedColor(
        when {
            hovered && danger -> Color(0xFFD9363E)
            hovered -> c.text.copy(alpha = 0.09f)
            else -> Color.Transparent
        },
    )
    Tip(hint) {
        Box(
            Modifier.width(44.dp).fillMaxHeight().background(bg).hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, hint, tint = if (hovered && danger) Color.White else c.text, modifier = Modifier.size(16.dp))
        }
    }
}

private val minimizeT = mlabeler.app.i18n.L("Minimize", "Свернуть")
private val maximizeT = mlabeler.app.i18n.L("Maximize", "Развернуть")
private val restoreT = mlabeler.app.i18n.L("Restore", "Восстановить")
private val closeWindowT = mlabeler.app.i18n.L("Close", "Закрыть")
