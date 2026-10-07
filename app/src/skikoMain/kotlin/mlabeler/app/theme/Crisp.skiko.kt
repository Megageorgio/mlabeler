package mlabeler.app.theme

import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual fun crispTextStyle(): PlatformTextStyle? = PlatformTextStyle(
    null,
    PlatformParagraphStyle(FontRasterizationSettings(FontSmoothing.None, FontHinting.Full, false, false)),
)
