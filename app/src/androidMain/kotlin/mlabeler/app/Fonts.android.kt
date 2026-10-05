package mlabeler.app

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

// Android exposes its fonts by these family names
private val names = listOf("sans-serif", "sans-serif-light", "sans-serif-medium", "sans-serif-condensed", "serif", "monospace",
    "serif-monospace", "casual", "cursive", "sans-serif-smallcaps")

actual fun systemFontNames(): List<String> = names

actual fun systemFontFamily(name: String): FontFamily? =
    if (name.isBlank()) null else runCatching { FontFamily(Typeface.create(name, Typeface.NORMAL)) }.getOrNull()
