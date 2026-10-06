package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EditorState
import mlabeler.app.state.Mode
import mlabeler.app.state.ToolbarGroups
import mlabeler.app.theme.T

/** Short names under toolbar buttons. */
object ToolLabels {
    val prev = L("Back", "Назад")
    val next = L("Next", "Дальше")
    val save = L("Save", "Сохранить")
    val undo = L("Undo", "Отменить")
    val redo = L("Redo", "Вернуть")
    val play = L("Play", "Играть")
    val stop = L("Stop", "Стоп")
    val loop = L("Loop", "Повтор")
    val speed = L("Speed", "Скорость")
    val split = L("Split", "Разрезать")
    val merge = L("Merge", "Склеить")
    val delete = L("Remove", "Убрать")
    val rename = L("Rename", "Текст")
    val ripple = L("Ripple", "Следом")
    val linked = L("Linked", "Связь")
    val overlay = L("Overlay", "Наложить")
    val pitch = L("Pitch", "Высота")
    val zoomIn = L("Closer", "Ближе")
    val zoomOut = L("Farther", "Дальше")
    val fit = L("Whole", "Весь")
    val autolabel = L("Autolabel", "Авторазметка")
    val autoOto = L("Auto-oto", "Авто-ото")
    val done = L("Done", "Готово")
    val star = L("Star", "Отметка")
    val plugins = L("Plugins", "Плагины")
    val commands = L("Commands", "Команды")
    val add = L("Add", "Добавить")
    val duplicate = L("Copy", "Копия")
    val lock = L("Together", "Вместе")
    val record = L("Record", "Запись")
    val cut = L("Scissors", "Ножницы")

    fun group(id: String): L = when (id) {
        ToolbarGroups.FILES -> L("Files", "Файлы")
        ToolbarGroups.HISTORY -> L("Undo and redo", "Отмена и возврат")
        ToolbarGroups.PLAY -> L("Playback", "Воспроизведение")
        ToolbarGroups.EDIT -> L("Editing", "Правка")
        ToolbarGroups.MODES -> L("Editing modes", "Режимы правки")
        ToolbarGroups.VIEW -> L("View", "Вид")
        ToolbarGroups.ZOOM -> L("Zoom", "Масштаб")
        ToolbarGroups.AUTO -> L("Automatic", "Автоматика")
        ToolbarGroups.MARKS -> L("Marks", "Отметки")
        ToolbarGroups.EXTRAS -> L("Plugins and commands", "Плагины и команды")
        else -> L(id, id)
    }
}

/** One toolbar button: icon, optionally with its name under it (big) or beside it. */
@Composable
fun ToolBtn(
    icon: ImageVector,
    label: String,
    hint: String,
    keys: String = "",
    enabled: Boolean = true,
    active: Boolean = false,
    showLabel: Boolean,
    big: Boolean,
    onClick: () -> Unit,
) {
    if (!showLabel) return IconBtn(icon, hint, keys, enabled, active, size = if (big) 40.dp else targetSize, onClick = onClick)
    val c = T.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(c.radius)
    val fg = when {
        !enabled -> c.muted.copy(alpha = 0.45f)
        active && c.square -> c.onAccent
        active -> c.accent
        else -> c.text
    }
    val bg = when {
        active -> c.accent.copy(alpha = if (c.square) 1f else 0.2f)
        hovered && enabled -> c.text.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Tip(if (keys.isEmpty()) hint else "$hint  ·  $keys") {
        val m = Modifier.clip(shape).background(bg).hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
        if (big) {
            Column(
                m.widthIn(min = 52.dp).height(if (Platform.isMobile) 52.dp else 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = hint, modifier = Modifier.size(20.dp), tint = fg)
                Text(label, color = fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp))
            }
        } else {
            Row(m.height(targetSize).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = hint, modifier = Modifier.size(17.dp), tint = fg)
                Text(label, color = fg, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

/** A framed group of buttons. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    val c = T.c
    val shape = RoundedCornerShape(c.radius + 2.dp)
    Row(
        Modifier.clip(shape).background(c.panelAlt.copy(alpha = 0.55f)).border(c.borderWidth, c.border.copy(alpha = 0.6f), shape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) { content() }
}

/** The groups chosen in the settings, in their order. */
@Composable
fun ToolbarGroupsRow(app: AppState, ed: EditorState) {
    val s = app.settings
    val tb = s.toolbar
    val lab = tb.labels
    val big = tb.big
    @Composable
    fun B(icon: ImageVector, label: L, cmd: Command?, hint: String = cmd?.title?.invoke() ?: label(), enabled: Boolean = cmd?.enabled?.invoke(ed) ?: true, active: Boolean = false, onClick: () -> Unit = { ed.finishEditing(); cmd?.run(ed, app) }) =
        ToolBtn(icon, label(), hint, cmd?.keyLabel ?: "", enabled, active, lab, big, onClick)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tb.scaleButton ?: Platform.isMobile) Group { ScaleTool(app, s.scale, lab, big) }
        for (g in tb.groups) when (g) {
            ToolbarGroups.FILES -> Group {
                B(Icons.prevFile, ToolLabels.prev, Commands.prevFile)
                B(Icons.nextFile, ToolLabels.next, Commands.nextFile)
                B(Icons.save, ToolLabels.save, Commands.save, enabled = ed.dirty || ed.item?.labelPath == null)
            }
            ToolbarGroups.HISTORY -> Group {
                B(Icons.undo, ToolLabels.undo, Commands.undo)
                B(Icons.redo, ToolLabels.redo, Commands.redo)
            }
            ToolbarGroups.PLAY -> Group {
                B(if (ed.playing) Icons.stop else Icons.play, if (ed.playing) ToolLabels.stop else ToolLabels.play, Commands.togglePlay,
                    hint = if (ed.playing) S.stop() else S.play())
                B(Icons.loop, ToolLabels.loop, Commands.loop, active = s.edit.loop)
                SpeedTool(ed, s.edit.speed, lab, big)
            }
            ToolbarGroups.EDIT -> Group {
                if (ed.mode == Mode.Oto) {
                    B(Icons.plus, ToolLabels.add, Commands.otoAdd)
                    B(Icons.merge, ToolLabels.duplicate, Commands.otoDuplicate, enabled = ed.oto.current() != null)
                    B(Icons.trash, ToolLabels.delete, Commands.otoDelete, enabled = ed.oto.current() != null)
                } else {
                    B(Icons.split, ToolLabels.split, Commands.split)
                    B(Icons.merge, ToolLabels.merge, Commands.merge)
                    B(Icons.edit, ToolLabels.rename, Commands.rename)
                    B(Icons.trash, ToolLabels.delete, Commands.delete)
                }
            }
            ToolbarGroups.MODES -> Group {
                if (ed.mode == Mode.Oto) {
                    B(Icons.link, ToolLabels.lock, Commands.otoLock, active = s.edit.otoLockedDrag)
                } else {
                    if (s.edit.tools) B(Icons.split, ToolLabels.cut, Commands.toolCut, active = s.edit.tool == "cut")
                    B(Icons.ripple, ToolLabels.ripple, Commands.ripple, hint = S.ripple() + " — " + S.rippleHint(), active = s.edit.ripple)
                    B(Icons.link, ToolLabels.linked, Commands.linked, hint = S.linked() + " — " + S.linkedHint(), active = s.edit.linked)
                }
            }
            ToolbarGroups.AUTO -> Group {
                if (ed.mode == Mode.Oto) B(Icons.magic, ToolLabels.autoOto, Commands.autoOto)
                else B(Icons.magic, ToolLabels.autolabel, Commands.autolabel)
            }
            ToolbarGroups.MARKS -> Group {
                val marks = if (ed.mode == Mode.Oto) ed.oto.current()?.let { ed.oto.marks(it) } else ed.item?.let { ed.marks(it) }
                B(Icons.check, ToolLabels.done, Commands.done, active = marks?.done == true)
                B(if (marks?.star == true) Icons.starOn else Icons.starOff, ToolLabels.star, Commands.star, active = marks?.star == true)
            }
            ToolbarGroups.VIEW -> Group {
                B(Icons.layers, ToolLabels.overlay, Commands.overlay, active = s.layout.overlay)
                B(Icons.tag, ToolLabels.pitch, Commands.pitchLane, active = s.layout.showPitch)
            }
            ToolbarGroups.ZOOM -> Group {
                B(Icons.zoomOut, ToolLabels.zoomOut, Commands.zoomOut)
                B(Icons.zoomIn, ToolLabels.zoomIn, Commands.zoomIn)
                B(Icons.fit, ToolLabels.fit, Commands.zoomFit)
            }
            ToolbarGroups.EXTRAS -> Group {
                B(Icons.plugin, ToolLabels.plugins, Commands.plugins)
                B(Icons.command, ToolLabels.commands, Commands.palette)
            }
        }
    }
}

private val scaleLabel = L("Size", "Размер")
private val scaleHint = L("Interface size", "Размер интерфейса")

/** The interface scale in percent; a tap opens the list of sizes. */
@Composable
fun ScaleTool(app: AppState, scale: Float, labels: Boolean, big: Boolean) {
    val c = T.c
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    val text = "${kotlin.math.round(scale * 100).toInt()}%"
    Tip(scaleHint()) {
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(c.radius)).clickable { open = true }
                    .then(if (big && labels) Modifier.height(if (Platform.isMobile) 52.dp else 48.dp).widthIn(min = 52.dp) else Modifier.height(if (big) 40.dp else targetSize))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (big && labels) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text, color = c.text, fontSize = 14.sp)
                        Text(scaleLabel(), color = c.text, fontSize = 11.sp, maxLines = 1)
                    }
                } else Text(text, color = c.muted, fontSize = 12.sp)
            }
            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                for (p in listOf(50, 60, 70, 80, 90, 100, 115, 130, 150, 175)) {
                    androidx.compose.material3.DropdownMenuItem(
                        { Text((if (kotlin.math.abs(scale * 100 - p) < 1) "✓  " else "     ") + "$p%", fontSize = 14.sp) },
                        onClick = { open = false; app.update { it.copy(scale = p / 100f) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedTool(ed: EditorState, speed: Float, labels: Boolean, big: Boolean) {
    val c = T.c
    val text = if (speed >= 1f) "1×" else "$speed×".removePrefix("0")
    Tip(Commands.speed.title() + "  ·  " + Commands.speed.keyLabel) {
        Box(
            Modifier.clip(RoundedCornerShape(c.radius)).clickable { ed.cycleSpeed() }
                .then(if (big && labels) Modifier.height(if (Platform.isMobile) 52.dp else 48.dp).widthIn(min = 52.dp) else Modifier.height(if (big) 40.dp else targetSize))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (big && labels) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text, color = if (speed < 1f) c.accent else c.text, fontSize = 15.sp)
                    Text(ToolLabels.speed(), color = c.text, fontSize = 11.sp, maxLines = 1)
                }
            } else {
                Text(text, color = if (speed < 1f) c.accent else c.muted, fontSize = 12.sp)
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun Fill() = Box(Modifier.fillMaxHeight())
