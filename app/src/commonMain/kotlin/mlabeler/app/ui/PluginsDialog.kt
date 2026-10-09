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
import kotlinx.coroutines.launch
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
private val runOn = L("Run on", "Запустить на")
private val thisFile = L("This file", "Этом файле")
private val allFiles = L("All files ({0})", "Всех файлах ({0})")
private val notDoneFiles = L("Not marked done ({0})", "Не отмеченных готовыми ({0})")
private val pickedFiles = L("Picked in the list ({0})", "Выбранных в списке ({0})")
private val checkFiles = L("Check {0} files", "Проверить файлы: {0}")
private val manyHint = L("First every file is tried and the changes are listed; nothing is written until you apply them. Each changed file keeps a copy in .mlabeler/backup.",
    "Сначала плагин пробуется на каждом файле и показывается, что изменится; ничего не записывается, пока вы не примените. У каждого изменённого файла сохраняется копия в .mlabeler/backup.")
private val checking = L("Checking: {0} of {1}", "Проверка: {0} из {1}")
private val checked = L("{0} of {1} files would change", "Изменится файлов: {0} из {1}")
private val intervalsChanged = L("{0} intervals", "интервалов: {0}")
private val unchangedNote = L("The other files stay as they are.", "Остальные файлы останутся как есть.")
private val failedT = L("Not changed, with an error:", "Не изменены из-за ошибки:")
private val applyT = L("Apply to {0} files", "Применить к файлам: {0}")
private val appliedT = L("Changed {0} files. Copies of the old labels are in .mlabeler/backup; the open file can also be undone.",
    "Изменено файлов: {0}. Копии прежней разметки — в .mlabeler/backup; изменения открытого файла можно отменить.")
private val backT = L("Back", "Назад")

fun pluginTitle(p: Plugin) = if (Lang.current == "ru" && p.info.titleRu.isNotEmpty()) p.info.titleRu else p.info.title.ifEmpty { p.info.name }
private fun pluginDesc(p: Plugin) = if (Lang.current == "ru" && p.info.descriptionRu.isNotEmpty()) p.info.descriptionRu else p.info.description

@Composable
fun PluginsDialog(app: AppState) {
    val ed = app.editor ?: return
    val c = T.c
    val target = if (ed.mode == Mode.Oto) "oto" else "labels"
    val list = app.plugins.filter { it.info.target == target }
    var selected by remember { mutableStateOf(list.firstOrNull()?.info?.name) }
    fun close() { app.closePluginBatch(); app.showPlugins = false; ed.requestFocus() }
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
        // a plugin tried on many files shows what it would change instead of the list
        val batch = app.pluginBatch
        if (batch != null) {
            PluginBatchView(app, batch, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp, max = 560.dp)) { close() }
        } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp)) {
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
                "file", "folder" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    Field((v as? JsonPrimitive)?.content ?: "", { set(JsonPrimitive(it)) }, Modifier.weight(1f))
                    val start = app.editor?.workspace?.root
                    if (param.type == "folder") Btn(browseT()) { app.pickFolder(label) { set(JsonPrimitive(it)) } }
                    else if (mlabeler.app.Platform.hasNativeFolderPicker) Btn(browseT()) {
                        scope.launch {
                            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mlabeler.app.Platform.pickFileNative(label, param.options, start) }
                            if (f != null) set(JsonPrimitive(f))
                        }
                    }
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
        // a labels plugin can go over many files of the folder
        val ed = app.editor
        var scope by remember(p.info.name) { mutableStateOf("file") }
        val files = when (scope) {
            "all" -> ed?.items.orEmpty()
            "notdone" -> ed?.batchFiles(mlabeler.app.state.FileFilter.NotDone).orEmpty()
            "picked" -> ed?.let { e -> e.items.filter { it.id in e.pickedFiles } }.orEmpty()
            else -> emptyList()
        }
        if (p.info.target == "labels" && ed != null && ed.items.size > 1) {
            SectionTitle(runOn())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(thisFile(), scope == "file") { scope = "file" }
                Chip(allFiles.format(ed.items.size), scope == "all") { scope = "all" }
                Chip(notDoneFiles.format(ed.batchFiles(mlabeler.app.state.FileFilter.NotDone).size), scope == "notdone") { scope = "notdone" }
                if (ed.pickedFiles.size > 1) Chip(pickedFiles.format(ed.pickedFiles.size), scope == "picked") { scope = "picked" }
            }
            if (scope != "file") Text(manyHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            if (scope == "file") Btn(run(), primary = true) { app.runPlugin(p, values); onRun() }
            else Btn(checkFiles.format(files.size), primary = true, enabled = files.isNotEmpty()) { app.checkPluginOnFiles(p, values, files) }
        }
    }
}

private val browseT = L("Choose…", "Выбрать…")

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
// notes: [{ start, end, pitch, slur, text }] or null; marks: { done, star, tag } of the file — change in place.
// params: values from the dialog (types: integer, float, boolean, string, text, enum, file, folder).
// file: { name, duration }. folder: { path, files: [{ name, path, labelled, done, star, tag }] }. env: { platform, language }.
// pitch: { hop, values } when plugin.json has "uses": ["pitch"].
// readText(path), writeText(path, text), listFiles(dir), exists(path): files inside the folder (paths relative to it).
// Set report = '...' to show a message, play = [start, end] to play a part afterwards; log(...) prints.
labels.forEach(function (tier) {
  tier.intervals.forEach(function (iv) {
    // example: iv.text = iv.text.toUpperCase();
  });
});
report = 'checked ' + labels.length + ' tiers';
"""

private const val TEMPLATE_OTO = """// entries: [{ sample, alias, offset, consonant, cutoff, preutterance, overlap, done, star, tag }] — change, add or remove in place.
// params: values from the dialog (types: integer, float, boolean, string, text, enum, file, folder).
// folder: { path, files }. env: { platform, language }. readText(path), writeText(path, text), listFiles(dir), exists(path).
// Set report = '...' to show a message, log(...) prints.
entries.forEach(function (e) {
  // example: if (/^- /.test(e.alias)) e.overlap = 5;
});
report = entries.length + ' entries';
"""

/** A plugin tried on many files: progress, then the files it would change, and applying it. */
@Composable
private fun PluginBatchView(app: AppState, batch: mlabeler.app.state.PluginBatch, modifier: Modifier, onDone: () -> Unit) {
    val c = T.c
    val ed = app.editor ?: return
    Column(modifier.scrollWithHint().padding(18.dp)) {
        Text(pluginTitle(batch.plugin), color = c.text, fontSize = 16.sp)
        val total = batch.files.size
        val n = batch.results.size
        if (batch.checking) {
            Text(checking.format(n, total), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (total > 0) n.toFloat() / total else 0f }, color = c.accent, trackColor = c.border,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
            )
        }
        val changed = batch.changedFiles
        val applied = batch.applied
        if (!batch.checking) {
            Text(if (applied != null) appliedT.format(applied) else checked.format(changed.size, total), color = c.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (applied == null && changed.size < total) Text(unchangedNote(), color = c.muted, fontSize = 12.sp)
        }
        // the files that change, biggest change first; a click opens the file
        Column(Modifier.padding(top = 10.dp)) {
            for (r in changed.sortedByDescending { it.changed }.take(300)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(c.radius)).clickable { ed.items.indexOfFirst { it.id == r.item.id }.takeIf { it >= 0 }?.let { ed.open(it) } }.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(r.item.name, color = c.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    r.note?.let { Text(it, color = c.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f)) }
                    Text(intervalsChanged.format(r.changed), color = c.accent, fontSize = 12.sp)
                }
            }
            if (changed.size > 300) Text("…", color = c.muted, fontSize = 13.sp)
        }
        val failed = batch.failedFiles
        if (failed.isNotEmpty()) {
            Text(failedT(), color = c.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            for (r in failed.take(50)) Text(r.item.name + ": " + (r.note ?: ""), color = c.muted, fontSize = 12.sp, maxLines = 2)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (applied == null) {
                Btn(backT()) { app.closePluginBatch() }
                Btn(applyT.format(changed.size), primary = true, enabled = !batch.checking && changed.isNotEmpty()) { batch.apply(ed) }
            } else Btn(S.close(), primary = true) { onDone() }
        }
    }
}
