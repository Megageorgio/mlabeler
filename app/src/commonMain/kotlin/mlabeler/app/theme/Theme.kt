package mlabeler.app.theme

import mlabeler.app.theme.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Theme tokens. Everything the app draws takes colours and shapes from here. */
@Immutable
data class Tokens(
    val id: String,
    val dark: Boolean,
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val ok: Color,
    val warn: Color,
    // timeline
    val laneBg: Color,
    val wave: Color,
    val waveCenter: Color,
    val bound: Color,
    val boundSelected: Color,
    val intervalSelected: Color,
    val intervalHover: Color,
    val playhead: Color,
    val cursor: Color,
    val selectionRange: Color,
    val tierText: Color,
    val tierColors: List<Color>,
    val spectrogram: List<Color>,
    // shape
    val radius: Dp,
    val borderWidth: Dp,
    val square: Boolean,
    val mono: Boolean = false,
    /** Set by the app (not stored in theme files): nothing is smoothed — text, corners, sliders. */
    val crisp: Boolean = false,
    /** On/off settings drawn as square check boxes instead of sliding switches. */
    val checkboxes: Boolean = false,
    /** Boundaries drawn over the waveform and spectrogram: colour (Unspecified = [bound], half see-through), width, style. */
    val boundLine: Color = Color.Unspecified,
    val boundWidth: Float = 1f,
    /** "dash", "dot" or "solid". */
    val boundStyle: String = "dash",
    /** The window's background: a gradient of these colours (top left to bottom right) instead of [bg]; none by default. */
    val bgGradient: List<Color> = emptyList(),
    /** A picture over the background (a file path), and how strongly it shows (0..1). */
    val bgImage: String = "",
    val bgImageAlpha: Float = 0.35f,
    /** How solid the panels are (1 = not see-through); less lets the background show through them. */
    val panelAlpha: Float = 1f,
    /**
     * How solid the sound lanes are (waveform and spectrogram background, the spectrogram itself); below 0 it
     * follows [panelAlpha] (half as see-through).
     */
    val laneAlpha: Float = -1f,
    /** How the program's own window title looks (menus in the window title): "" plain, "xp" blue and rounded, "classic" a flat stripe. */
    val windowStyle: String = "",
)

private fun hex(v: Long) = Color(v or 0xFF000000)

object Themes {
    private val magma = listOf(0x000004L, 0x1c1044L, 0x4f127bL, 0x812581L, 0xb5367aL, 0xe55964L, 0xfb8761L, 0xfec287L, 0xfcfdbfL).map(::hex)
    private val ice = listOf(0x0b0e14L, 0x14233aL, 0x1d4e7aL, 0x2b84a8L, 0x5fbfc9L, 0xb6e6e2L, 0xf4fbf7L).map(::hex)
    private val gray = listOf(0x0f0f0fL, 0xf0f0f0L).map(::hex)
    private val paper = listOf(0xfbfaf7L, 0xd8d3c8L, 0x8a8577L, 0x34322dL, 0x000000L).map(::hex)

    val palettes = mapOf("magma" to magma, "ice" to ice, "gray" to gray, "paper" to paper)

    val modernDark = Tokens(
        id = "modern-dark", dark = true,
        bg = hex(0x16181c), panel = hex(0x1d2026), panelAlt = hex(0x252932), border = hex(0x323743),
        text = hex(0xe6e8ec), muted = hex(0x8c93a1), accent = hex(0xa6cf82), onAccent = hex(0x15240c),
        danger = hex(0xff6b6b), ok = hex(0x6fd49a), warn = hex(0xf2c46d),
        laneBg = hex(0x121419), wave = hex(0xb1d394), waveCenter = hex(0x2a2f3a),
        bound = hex(0xc9cfdb), boundSelected = hex(0xffd166), intervalSelected = Color(0x33a6cf82), intervalHover = Color(0x14ffffff),
        playhead = hex(0xff5c7a), cursor = Color(0x99e6e8ec), selectionRange = Color(0x29a6cf82),
        tierText = hex(0xe6e8ec), tierColors = listOf(hex(0xa6cf82), hex(0x7aa2ff), hex(0xf2c46d), hex(0xd99cff), hex(0x5fd0d6)),
        spectrogram = magma, radius = 8.dp, borderWidth = 1.dp, square = false,
    )

    val modernLight = Tokens(
        id = "modern-light", dark = false,
        bg = hex(0xf4f5f7), panel = hex(0xffffff), panelAlt = hex(0xeef0f4), border = hex(0xd9dde4),
        text = hex(0x1b1f27), muted = hex(0x667085), accent = hex(0x4e8a2c), onAccent = hex(0xffffff),
        danger = hex(0xd14343), ok = hex(0x23955a), warn = hex(0xb7791f),
        laneBg = hex(0xffffff), wave = hex(0x6a9f48), waveCenter = hex(0xe3e6ec),
        bound = hex(0x3b4252), boundSelected = hex(0xe8590c), intervalSelected = Color(0x2e4e8a2c), intervalHover = Color(0x0f000000),
        playhead = hex(0xe03159), cursor = Color(0x881b1f27), selectionRange = Color(0x224e8a2c),
        tierText = hex(0x1b1f27), tierColors = listOf(hex(0x4e8a2c), hex(0x3461d1), hex(0xb7791f), hex(0x8e44c4), hex(0x0e8a91)),
        spectrogram = paper, radius = 8.dp, borderWidth = 1.dp, square = false,
    )

    /** Square corners, hard borders, flat colours, monospace text. */
    val retro = Tokens(
        id = "retro", dark = false,
        bg = hex(0xc0c0c0), panel = hex(0xd4d0c8), panelAlt = hex(0xe4e0d8), border = hex(0x404040),
        text = hex(0x000000), muted = hex(0x404040), accent = hex(0x000080), onAccent = hex(0xffffff),
        danger = hex(0xa00000), ok = hex(0x006000), warn = hex(0x806000),
        laneBg = hex(0xffffff), wave = hex(0x000080), waveCenter = hex(0xc0c0c0),
        bound = hex(0x000000), boundSelected = hex(0xc00000), intervalSelected = Color(0x40000080), intervalHover = Color(0x18000000),
        playhead = hex(0xff0000), cursor = Color(0xaa000000), selectionRange = Color(0x30000080),
        tierText = hex(0x000000), tierColors = listOf(hex(0x000080), hex(0x006000), hex(0x806000), hex(0x800080), hex(0x008080)),
        spectrogram = gray.reversed(), radius = 0.dp, borderWidth = 1.dp, square = true, mono = true, checkboxes = true,
        windowStyle = "xp",
    )

    val contrast = Tokens(
        id = "contrast", dark = true,
        bg = hex(0x000000), panel = hex(0x000000), panelAlt = hex(0x111111), border = hex(0xffffff),
        text = hex(0xffffff), muted = hex(0xd0d0d0), accent = hex(0xffff00), onAccent = hex(0x000000),
        danger = hex(0xff4040), ok = hex(0x40ff40), warn = hex(0xffc000),
        laneBg = hex(0x000000), wave = hex(0x00ffff), waveCenter = hex(0x404040),
        bound = hex(0xffffff), boundSelected = hex(0xffff00), intervalSelected = Color(0x55ffff00), intervalHover = Color(0x22ffffff),
        playhead = hex(0xff00ff), cursor = hex(0xffffff), selectionRange = Color(0x44ffff00),
        tierText = hex(0xffffff), tierColors = listOf(hex(0xffff00), hex(0x00ffff), hex(0xff80ff), hex(0x80ff80), hex(0xffa040)),
        spectrogram = gray, radius = 2.dp, borderWidth = 2.dp, square = false,
    )

    /** The classic look in soft pink, with crisp square check boxes. */
    val fairy = Tokens(
        id = "retro-fairy", dark = false,
        bg = hex(0xe7e6dd), panel = hex(0xdfb8bf), panelAlt = hex(0xddcccc), border = hex(0x404040),
        text = hex(0x000000), muted = hex(0x404040), accent = hex(0xd55b86), onAccent = hex(0xffffff),
        danger = hex(0xff0000), ok = hex(0x64b41e), warn = hex(0xfffd76),
        laneBg = hex(0xffffff), wave = hex(0x007479), waveCenter = hex(0xc0c0c0),
        bound = hex(0x000000), boundSelected = hex(0xc00000), intervalSelected = Color(0x40000080), intervalHover = Color(0x18000000),
        playhead = hex(0xff0000), cursor = Color(0xaa000000), selectionRange = Color(0x30000080),
        tierText = hex(0x000000), tierColors = listOf(hex(0x000080), hex(0x006000), hex(0x806000), hex(0x800080), hex(0x008080)),
        spectrogram = listOf(hex(0xf0f0f0), hex(0xff0063)), radius = 10.dp, borderWidth = 1.dp, square = true, mono = true, checkboxes = true,
        boundLine = hex(0xff008b), boundWidth = 1f, boundStyle = "dash",
    )

    val builtIn = listOf(modernDark, modernLight, retro, fairy, contrast)

    /** Themes from files (see ThemeFiles). */
    var custom by androidx.compose.runtime.mutableStateOf<List<CustomTheme>>(emptyList())

    val all: List<Tokens> get() = builtIn + custom.map { it.tokens }

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: modernDark
}

/** A theme from a file in the themes folder. */
data class CustomTheme(val tokens: Tokens, val name: String, val path: String)

val LocalTokens = staticCompositionLocalOf { Themes.modernDark }

/** Interface font chosen in the settings (null = the theme's). */
val LocalUiFont = staticCompositionLocalOf<FontFamily?> { null }

object T {
    val c: Tokens @Composable get() = LocalTokens.current
    /** Font for everything the app draws itself (labels, fields). */
    val font: FontFamily @Composable get() = LocalUiFont.current ?: if (LocalTokens.current.mono) FontFamily.Monospace else FontFamily.Default
}

private fun scheme(t: Tokens): ColorScheme {
    val base = if (t.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = t.accent, onPrimary = t.onAccent, primaryContainer = t.accent, onPrimaryContainer = t.onAccent,
        secondary = t.accent, onSecondary = t.onAccent, secondaryContainer = t.panelAlt, onSecondaryContainer = t.text,
        background = t.bg, onBackground = t.text, surface = t.panel, onSurface = t.text,
        surfaceVariant = t.panelAlt, onSurfaceVariant = t.muted, surfaceContainer = t.panel, surfaceContainerHigh = t.panelAlt,
        surfaceContainerHighest = t.panelAlt, surfaceContainerLow = t.panel, surfaceContainerLowest = t.bg,
        outline = t.border, outlineVariant = t.border, error = t.danger, onError = Color.White,
        inverseSurface = t.text, inverseOnSurface = t.bg,
    )
}

@Composable
fun AppTheme(tokens0: Tokens, font: FontFamily? = null, crisp: Boolean = false, content: @Composable () -> Unit) {
    // without smoothing: corners keep their radius but are drawn as pixel steps, text has hard pixel edges
    CrispShapes.on = crisp
    val t1 = if (crisp) tokens0.copy(crisp = true) else tokens0
    // see-through panels over a background picture or gradient
    val lane = t1.effectiveLaneAlpha
    val tokens = t1.copy(
        panel = t1.panel.copy(alpha = t1.panel.alpha * t1.panelAlpha),
        panelAlt = t1.panelAlt.copy(alpha = t1.panelAlt.alpha * (0.5f + t1.panelAlpha / 2)),
        laneBg = t1.laneBg.copy(alpha = t1.laneBg.alpha * lane),
        laneAlpha = lane,
    )
    val r = RoundedCornerShape(tokens.radius)
    val shapes = Shapes(extraSmall = r, small = r, medium = r, large = r, extraLarge = r)
    val family = font ?: if (tokens.mono) FontFamily.Monospace else FontFamily.Default
    val base = TextStyle(fontFamily = family, platformStyle = if (crisp) crispTextStyle() else null)
    val typography = Typography(
        bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = 11.sp),
        titleLarge = base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalTokens provides tokens, LocalUiFont provides font, androidx.compose.material3.LocalContentColor provides tokens.text) {
        MaterialTheme(colorScheme = scheme(tokens), shapes = shapes, typography = typography, content = content)
    }
}

/** [Tokens.laneAlpha] as used: set by the theme, or following the panels. */
val Tokens.effectiveLaneAlpha: Float get() = if (laneAlpha >= 0f) laneAlpha.coerceIn(0.1f, 1f) else 0.5f + panelAlpha.coerceIn(0f, 1f) / 2
