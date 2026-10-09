package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
    /** The program's icon for the left of the title. */
    val icon: androidx.compose.ui.graphics.painter.Painter?
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
    val menus = ed != null && app.settings.menuBar && app.recorder == null && app.karaoke == null
    when (c.windowStyle) {
        "xp", "classic" -> Column(Modifier.fillMaxWidth()) {
            // the old look: a coloured stripe with the title and the buttons, the menus on their own row under it
            StyledCaption(frame, title, xp = c.windowStyle == "xp")
            if (menus) MenuBar(app, ed!!)
        }
        else -> Row(Modifier.fillMaxWidth().height(32.dp).background(c.panel), verticalAlignment = Alignment.CenterVertically) {
            if (frame.systemButtons) Spacer(Modifier.width(76.dp))
            else {
                Spacer(Modifier.width(8.dp))
                frame.icon?.let { Image(it, null, Modifier.size(16.dp)) }
                Spacer(Modifier.width(2.dp))
            }
            if (menus) MenuBar(app, ed!!, inTitle = true)
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
}

/** The edge of a window without the system's frame (none when maximized or on macOS). */
@Composable
fun Modifier.windowEdge(frame: WindowFrame?): Modifier {
    if (frame == null || frame.systemButtons || frame.maximized) return this
    val c = T.c
    return when (c.windowStyle) {
        "xp" -> border(3.dp, XpBlue)
        "classic" -> border(1.dp, c.border).padding(1.dp).border(2.dp, c.panel)
        else -> border(1.dp, c.border.copy(alpha = 0.6f))
    }
}

private val XpBlue = Color(0xFF0054E3)

@Composable
private fun StyledCaption(frame: WindowFrame, title: String, xp: Boolean) {
    val c = T.c
    val bg = if (xp) Brush.verticalGradient(
        0f to Color(0xFF3D8DFF), 0.09f to Color(0xFF0A5FEA), 0.5f to Color(0xFF0054E3), 0.88f to Color(0xFF0B62EE), 1f to Color(0xFF003BCB),
    ) else Brush.horizontalGradient(listOf(c.accent, lerp(c.accent, Color.White, 0.55f)))
    Row(
        Modifier.fillMaxWidth().height(if (xp) 30.dp else 24.dp).background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (frame.systemButtons) Spacer(Modifier.width(76.dp))
        else {
            Spacer(Modifier.width(if (xp) 6.dp else 3.dp))
            frame.icon?.let { Image(it, null, Modifier.size(16.dp)) }
        }
        frame.DragArea(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        shadow = if (xp) Shadow(Color(0xFF0A1E6E), Offset(1f, 1f), 1f) else null,
                    ),
                )
            }
        }
        if (!frame.systemButtons) {
            val max = if (frame.maximized) Icons.winRestore else Icons.winMaximize
            val maxHint = if (frame.maximized) restoreT() else maximizeT()
            if (xp) {
                XpButton(Icons.winMinimize, minimizeT()) { frame.minimize() }
                Spacer(Modifier.width(2.dp))
                XpButton(max, maxHint) { frame.toggleMaximize() }
                Spacer(Modifier.width(2.dp))
                XpButton(Icons.close, closeWindowT(), close = true) { frame.close() }
                Spacer(Modifier.width(5.dp))
            } else {
                ClassicButton(Icons.winMinimize, minimizeT()) { frame.minimize() }
                ClassicButton(max, maxHint) { frame.toggleMaximize() }
                Spacer(Modifier.width(2.dp))
                ClassicButton(Icons.close, closeWindowT()) { frame.close() }
                Spacer(Modifier.width(3.dp))
            }
        }
    }
}

/** A rounded glossy button with a white rim, blue or (to close) red-orange. */
@Composable
private fun XpButton(icon: ImageVector, hint: String, close: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val (top, bottom) = if (close) Color(0xFFE97A5C) to Color(0xFFC4391C) else Color(0xFF6AA2FF) to Color(0xFF2560DF)
    val k = when { pressed -> -0.18f; hovered -> 0.16f; else -> 0f }
    fun shade(x: Color) = if (k >= 0) lerp(x, Color.White, k) else lerp(x, Color.Black, -k)
    val shape = RoundedCornerShape(3.dp)
    Tip(hint) {
        Box(
            Modifier.size(21.dp).clip(shape).background(Brush.verticalGradient(listOf(shade(top), shade(bottom))))
                .border(1.dp, Color.White, shape).hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, hint, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/** A small grey raised button, pressed in while held. */
@Composable
private fun ClassicButton(icon: ImageVector, hint: String, onClick: () -> Unit) {
    val c = T.c
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Tip(hint) {
        Box(
            Modifier.width(18.dp).height(16.dp).background(c.panel)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val px = 1.dp.toPx()
                    val light = if (pressed) Color(0xFF404040) else Color.White
                    val dark = if (pressed) Color.White else Color(0xFF404040)
                    drawRect(light, size = Size(w, px))
                    drawRect(light, size = Size(px, h))
                    drawRect(dark, topLeft = Offset(0f, h - px), size = Size(w, px))
                    drawRect(dark, topLeft = Offset(w - px, 0f), size = Size(px, h))
                }
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, hint, tint = Color.Black, modifier = Modifier.size(12.dp).padding(top = if (pressed) 1.dp else 0.dp))
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
