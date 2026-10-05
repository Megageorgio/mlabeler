package mlabeler.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import platform.UIKit.UIFont

actual fun systemFontNames(): List<String> =
    UIFont.familyNames.mapNotNull { it as? String }.sortedBy { it.lowercase() }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual fun systemFontFamily(name: String): FontFamily? =
    if (name.isBlank()) null else runCatching { FontFamily(SystemFont(name)) }.getOrNull()
