package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.Lang
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.state.EnvironmentEntry
import mlabeler.app.state.Environments
import mlabeler.app.theme.T

/** Shown once, on the first start: language and the work environment. */
@Composable
fun SetupDialog(app: AppState) {
    val c = T.c
    Overlay({ app.applyEnvironment("basic") }, 760) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("mLabeler", color = c.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                for ((code, name) in Lang.available) {
                    Chip(name, Lang.current == code) { app.update { it.copy(language = code) } }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                }
            }
            Text(S.chooseEnvironment(), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp))
            Text(S.chooseEnvironmentHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            EnvironmentCards(app, Environments.builtIns(), current = null) { app.applyEnvironment(it.id) }
        }
    }
}

/** Environments as cards: name and what's in it; [onDelete] shows a remove button on the user's own ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentCards(
    app: AppState,
    entries: List<EnvironmentEntry>,
    current: String?,
    onDelete: ((EnvironmentEntry) -> Unit)? = null,
    onPick: (EnvironmentEntry) -> Unit,
) {
    val c = T.c
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = when {
            maxWidth < 420.dp -> 1
            maxWidth < 700.dp -> 2
            else -> 4
        }
        val w = (maxWidth - 10.dp * (cols - 1)) / cols - 1.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (e in entries) {
                val sel = e.id == current
                val shape = RoundedCornerShape(c.radius * 2)
                Column(
                    Modifier.width(w).clip(shape).background(if (sel) c.accent.copy(alpha = 0.14f) else c.panelAlt)
                        .border(if (sel) 2.dp else c.borderWidth, if (sel) c.accent else c.border, shape)
                        .clickable { onPick(e) }.padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, color = if (sel) c.accent else c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (!e.builtIn && onDelete != null) IconBtn(Icons.trash, S.removeFromList(), size = 24.dp) { onDelete(e) }
                    }
                    if (e.description.isNotEmpty()) Text(e.description, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** List, save, update and remove environments (settings page and View menu entry point). */
@Composable
fun EnvironmentsSection(app: AppState) {
    val c = T.c
    val s = app.settings
    val version = app.environmentsVersion
    val entries = remember(version) { Environments.all() }
    val current = entries.firstOrNull { it.id == s.environment }
    Text(S.environmentHint(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    EnvironmentCards(app, entries, s.environment, onDelete = { app.deleteEnvironment(it.id) }) { app.applyEnvironment(it.id) }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current != null && !Environments.matches(s, current)) {
            if (!current.builtIn) Btn(S.environmentUpdate.format(current.title), primary = true) { app.saveEnvironment(current.title) }
            Btn(S.reset()) { app.applyEnvironment(current.id) }
        }
    }
    var name by remember { mutableStateOf("") }
    Text(S.environmentSaveAs(), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(name, { name = it }, Modifier.weight(1f), placeholder = S.environmentName(), onDone = { app.saveEnvironment(name); name = "" })
        Btn(S.save(), enabled = name.isNotBlank()) { app.saveEnvironment(name); name = "" }
    }
    if (!Platform.isMobile) {
        Row(Modifier.padding(top = 8.dp)) {
            Btn(S.environmentsFolder()) {
                mlabeler.core.io.PlatformFs.mkdirs(Environments.dir())
                Platform.openInFileManager(Environments.dir())
            }
        }
    }
}
