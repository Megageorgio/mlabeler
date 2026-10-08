package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import mlabeler.app.Platform
import mlabeler.app.i18n.L
import mlabeler.app.i18n.Lang
import mlabeler.app.i18n.S
import mlabeler.app.plugins.Plugin
import mlabeler.app.state.AppState
import mlabeler.app.state.Mode
import mlabeler.app.theme.T
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs

private val run = L("Run", "Запустить")
private val slot = L("Quick slot (Ctrl+number)", "Быстрый слот (Ctrl+цифра)")
private val newPlugin = L("New plugin…", "Новый плагин…")
private val openFolder = L("Plugin folder", "Папка плагинов")
private val created = L("Created {0}: edit main.js and plugin.json, then reopen this list", "Создан {0}: отредактируйте main.js и plugin.json, затем откройте список заново")
private val builtIn = L("built in", "встроенный")
private val none = L("No plugins for this kind of folder.", "Для такой папки плагинов нет.")

fun pluginTitle(p: Plugin) = if (Lang.current == "ru" && p.info.titleRu.isNotEmpty()) p.info.titleRu else p.info.title.ifEmpty { p.info.name }
private fun pluginDesc(p: Plugin) = if (Lang.current == "ru" && p.info.descriptionRu.isNotEmpty()) p.info.descriptionRu else p.info.description

@Composable
fun PluginsDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val target = if (ed.mode == Mode.Oto) "oto" else "labels"
    val list = app.plugins.filter { it.info.target == target }
    var selected by remember { mutableStateOf(list.firstOrNull()?.info?.name) }
    fun close() { app.showPlugins = false; ed.requestFocus() }
    Overlay({ close() }, 820) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.pluginsTitle(), color = c.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Btn(newPlugin()) {
                val dir = Paths.join(Paths.join(Platform.dataDir(), "plugins"), "my-plugin-" + (app.plugins.size + 1))
                PlatformFs.write(Paths.join(dir, "plugin.json"), TEMPLATE_JSON.replace("NAME", Paths.name(dir)).replace("TARGET", target).encodeToByteArray())
                PlatformFs.write(Paths.join(dir, "main.js"), (if (target == "oto") TEMPLATE_OTO else TEMPLATE_LABELS).encodeToByteArray())
                app.reloadPlugins()
                app.message(created.format(dir))
                Platform.openInFileManager(dir)
            }
            IconBtn(Icons.folder, openFolder()) { Platform.openInFileManager(Paths.join(Platform.dataDir(), "plugins").also { PlatformFs.mkdirs(it) }) }
            IconBtn(Icons.close, S.close()) { close() }
        }
        Divider()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp)) {
            val narrow = maxWidth < 560.dp
            val listView: @Composable (Modifier) -> Unit = { m ->
                Column(m.background(c.panelAlt).scrollWithHint()) {
                    if (list.isEmpty()) Text(none(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                    for (p in list) {
                        val sel = p.info.name == selected
                        Column(
                            Modifier.fillMaxWidth().background(if (sel) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selected = p.info.name }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(pluginTitle(p), color = if (sel) c.accent else c.text, fontSize = 13.sp)
                            if (p.builtIn) Text(builtIn(), color = c.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            val p = list.firstOrNull { it.info.name == selected }
            if (narrow) {
                Column {
                    listView(Modifier.fillMaxWidth().height(180.dp))
                    Divider()
                    if (p != null) PluginForm(app, p, Modifier.fillMaxWidth()) { close() }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(minOf(520.dp, maxHeight))) {
                    listView(Modifier.width(260.dp).fillMaxHeight())
                    Divider(vertical = true)
                    if (p != null) PluginForm(app, p, Modifier.weight(1f)) { close() }
                }
            }
        }
    }
}

@Composable
private fun PluginForm(app: AppState, p: Plugin, modifier: Modifier, onRun: () -> Unit) {
    val c = T.c
    var values by remember(p.info.name) { mutableStateOf(app.pluginParams(p)) }
    Column(modifier.scrollWithHint().padding(18.dp)) {
        Text(pluginTitle(p), color = c.text, fontSize = 16.sp)
        pluginDesc(p).takeIf { it.isNotEmpty() }?.let { Text(it, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (p.info.author.isNotEmpty()) Text(p.info.author + " · " + p.info.version, color = c.muted, fontSize = 11.sp)
        for (param in p.info.parameters) {
            val label = if (Lang.current == "ru" && param.labelRu.isNotEmpty()) param.labelRu else param.label.ifEmpty { param.name }
            val v = values[param.name] ?: JsonNull
            fun set(x: JsonElement) { values = values + (param.name to x) }
            SectionTitle(label)
            when (param.type) {
                "boolean" -> Toggle((v as? JsonPrimitive)?.booleanOrNull == true, { set(JsonPrimitive(it)) })
                "enum" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in param.options) Chip(o, (v as? JsonPrimitive)?.content == o) { set(JsonPrimitive(o)) }
                }
                "text" -> BasicTextField(
                    (v as? JsonPrimitive)?.content ?: "", { set(JsonPrimitive(it)) }, textStyle = TextStyle(color = c.text, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(c.radius)).background(c.bg)
                        .border(c.borderWidth, c.border, RoundedCornerShape(c.radius)).padding(8.dp),
                )
                "integer", "float" -> {
                    var text by remember(p.info.name, param.name) { mutableStateOf((v as? JsonPrimitive)?.content ?: "") }
                    Field(text, { text = it; it.replace(',', '.').toDoubleOrNull()?.let { d -> set(if (param.type == "integer") JsonPrimitive(d.toLong()) else JsonPrimitive(d)) } }, Modifier.width(160.dp))
                }
                else -> Field((v as? JsonPrimitive)?.content ?: "", { set(JsonPrimitive(it)) }, Modifier.fillMaxWidth())
            }
        }
        SectionTitle(slot())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in 0 until 4) {
                val on = app.settings.pluginSlots.getOrNull(k) == p.info.name
                Chip((k + 1).toString(), on) {
                    app.update { s ->
                        val slots = s.pluginSlots.toMutableList()
                        while (slots.size < 4) slots += ""
                        slots[k] = if (on) "" else p.info.name
                        s.copy(pluginSlots = slots)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            Btn(run(), primary = true) { app.runPlugin(p, values); onRun() }
        }
    }
}

private const val TEMPLATE_JSON = """{
  "name": "NAME",
  "title": "My plugin",
  "titleRu": "Мой плагин",
  "description": "What it does.",
  "target": "TARGET",
  "parameters": [
    { "name": "text", "type": "string", "label": "Text", "labelRu": "Текст", "default": "" }
  ],
  "script": "main.js"
}
"""

private const val TEMPLATE_LABELS = """// labels: [{ name, intervals: [{ start, end, text }] }] — change in place.
// params: values from the dialog. file: { name, duration }. Set report = '...' to show a message, log(...) prints.
labels.forEach(function (tier) {
  tier.intervals.forEach(function (iv) {
    // example: iv.text = iv.text.toUpperCase();
  });
});
report = 'checked ' + labels.length + ' tiers';
"""

private const val TEMPLATE_OTO = """// entries: [{ sample, alias, offset, consonant, cutoff, preutterance, overlap }] — change, add or remove in place.
// params: values from the dialog. Set report = '...' to show a message, log(...) prints.
entries.forEach(function (e) {
  // example: if (/^- /.test(e.alias)) e.overlap = 5;
});
report = entries.length + ' entries';
"""
