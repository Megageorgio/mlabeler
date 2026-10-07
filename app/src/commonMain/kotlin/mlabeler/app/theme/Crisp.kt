package mlabeler.app.theme

import androidx.compose.ui.text.PlatformTextStyle

/** Text drawn without smoothing (hard pixel edges), where the platform can; null where it can't. */
expect fun crispTextStyle(): PlatformTextStyle?
