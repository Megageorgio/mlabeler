package mlabeler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mlabeler.app.Platform
import mlabeler.app.i18n.S
import mlabeler.app.state.AppState
import mlabeler.app.theme.T
import mlabeler.core.io.AUDIO_EXTENSIONS
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs
import mlabeler.core.io.naturalOrder

@Composable
fun StartScreen(app: AppState) {
    val c = T.c
    var browsing by remember { mutableStateOf(false) }
    if (browsing) {
        FolderBrowser(
            start = app.settings.recent.firstOrNull()?.let { Paths.parent(it) } ?: Platform.homeDir(),
            onPick = { browsing = false; app.openFolder(it) },
            onCancel = { browsing = false },
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth > 760.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 56.dp else 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S.appName(), color = c.text, fontSize = 28.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.settings, S.settings()) { app.showSettings = true }
            }
            Spacer(Modifier.height(28.dp))
            val open = {
                if (Platform.hasNativeFolderPicker) {
                    Platform.pickFolderNative(S.openFolder())?.let { app.openFolder(it) }
                } else {
                    browsing = true
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn(S.openFolder(), primary = true, icon = Icons.folder) { open() }
            }
            Text(S.openFolderHint(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).widthIn(max = 520.dp))
            Spacer(Modifier.height(32.dp))
            SectionTitle(S.recent())
            if (app.settings.recent.isEmpty()) {
                Text(S.noRecent(), color = c.muted, fontSize = 13.sp)
            }
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                items(app.settings.recent) { path ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 56.dp else 44.dp)
                            .clickable { app.openFolder(path) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.folder, null, Modifier.size(18.dp), tint = c.muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Paths.name(path), color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconBtn(Icons.close, S.removeFromList(), size = 28.dp) { app.forgetRecent(path) }
                    }
                }
            }
        }
    }
}

/** In-app folder browser: works the same on every platform. */
@Composable
fun FolderBrowser(start: String, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val c = T.c
    var dir by remember { mutableStateOf(if (PlatformFs.isDirectory(start)) start else Platform.homeDir()) }
    val entries = remember(dir) {
        runCatching { PlatformFs.list(dir) }.getOrDefault(emptyList())
            .filter { !Paths.name(it).startsWith(".") }
    }
    val folders = remember(entries) { entries.filter { PlatformFs.isDirectory(it) }.sortedWith(compareBy(naturalOrder()) { Paths.name(it).lowercase() }) }
    val audioCount = remember(entries) { entries.count { Paths.ext(it) in AUDIO_EXTENSIONS } }
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(c.panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.back, S.cancel()) { onCancel() }
            IconBtn(Icons.up, S.up(), enabled = Paths.parent(dir).isNotEmpty() && Paths.parent(dir) != dir) { dir = Paths.parent(dir).ifEmpty { "/" } }
            Text(dir, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, path) in Platform.places()) Chip(name, dir == path) { dir = path }
        }
        Divider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (folders.isEmpty()) item { Text(S.folderEmpty(), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            items(folders) { f ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (Platform.isMobile) 52.dp else 38.dp).clickable { dir = f }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.folder, null, Modifier.size(18.dp), tint = c.muted)
                    Spacer(Modifier.width(12.dp))
                    Text(Paths.name(f), color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth().background(c.panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.audioFiles.format(audioCount), color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Btn(S.chooseThisFolder(), primary = true) { onPick(dir) }
        }
    }
}

@Suppress("unused")
@Composable
private fun Fill() = Box(Modifier.fillMaxHeight())
