@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)
package mlabeler.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mlabeler.app.i18n.Lang
import mlabeler.core.io.binaryBytes
import mlabeler.app.theme.Themes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    check(AppInfo.WEB) { "Build the web edition with -Pdistribution=web" }
    val scope = MainScope()
    ComposeViewport(document.getElementById("app")!!) {
        val resolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        LaunchedEffect(resolver) {
            for (name in listOf("NotoSans-Regular", "NotoSansSymbols2-Regular", "NotoSansMath-Regular", "DroidSansFallback")) {
                resolver.preload(FontFamily(Font(name, Browser.font(name).binaryBytes())))
            }
            fontsReady = true
        }
        if (!fontsReady) return@ComposeViewport
        val app = rememberAppState()
        val theme = Themes.byId(app.settings.theme)
        val scale = app.settings.scale.coerceIn(0.5f, 2f)
        LaunchedEffect(theme, scale) {
            fun Color.css() = "#" + (toArgb() and 0xffffff).toString(16).padStart(6, '0')
            Browser.setTheme(buildJsonObject {
                put("bg", theme.bg.css()); put("panel", theme.panel.css()); put("panel-alt", theme.panelAlt.css())
                put("border", theme.border.css()); put("text", theme.text.css()); put("muted", theme.muted.css())
                put("accent", theme.accent.css()); put("on-accent", theme.onAccent.css()); put("warn", theme.warn.css())
                put("radius", "${theme.radius.value * scale}px"); put("border-width", "${theme.borderWidth.value * scale}px")
                put("scale", scale); put("dark", theme.dark); put("mono", theme.mono)
            }.toString())
        }
        LaunchedEffect(app) {
            Browser.bind(
                imported = { app.openFolder(it) },
                save = { done -> scope.launch { done(app.preserveSession()) } },
                beforeImport = { done -> scope.launch { done(app.preserveSession() && app.closeFolder()) } },
            )
        }
        LaunchedEffect(Lang.current) { Browser.setLanguage(Lang.current) }
        LaunchedEffect(app) {
            while (true) {
                Browser.setUnsaved(app.editor?.dirty == true || app.karaoke?.dirty == true)
                Browser.setBusy(app.openingFolder || app.editor?.toolkitBusy != null)
                delay(300)
            }
        }
        App(app)
    }
}
