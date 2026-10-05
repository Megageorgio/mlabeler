package mlabeler.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont

private val names: List<String> by lazy {
    runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }
        .getOrDefault(emptyList()).filter { !it.startsWith(".") }.distinct().sortedBy { it.lowercase() }
}

actual fun systemFontNames(): List<String> = names

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual fun systemFontFamily(name: String): FontFamily? =
    if (name.isBlank()) null else runCatching { FontFamily(SystemFont(name)) }.getOrNull()
