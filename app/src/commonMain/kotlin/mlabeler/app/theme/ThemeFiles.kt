package mlabeler.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mlabeler.core.io.Paths
import mlabeler.core.io.PlatformFs

/** Themes as JSON files: any colour of a built-in theme can be changed; missing keys come from "base". */
object ThemeFiles {
    private val json = Json { prettyPrint = true; isLenient = true }

    fun hex(c: Color): String {
        val v = c.toArgb()
        val a = (v ushr 24) and 0xFF
        val rgb = (v and 0xFFFFFF).toString(16).padStart(6, '0')
        return if (a == 0xFF) "#$rgb" else "#" + a.toString(16).padStart(2, '0') + rgb
    }

    fun color(s: String): Color? {
        val h = s.trim().removePrefix("#")
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> null
        }
    }

    val colorKeys: List<Pair<String, (Tokens) -> Color>> = listOf(
        "bg" to { it.bg }, "panel" to { it.panel }, "panelAlt" to { it.panelAlt }, "border" to { it.border },
        "text" to { it.text }, "muted" to { it.muted }, "accent" to { it.accent }, "onAccent" to { it.onAccent },
        "danger" to { it.danger }, "ok" to { it.ok }, "warn" to { it.warn }, "laneBg" to { it.laneBg },
        "wave" to { it.wave }, "waveCenter" to { it.waveCenter }, "bound" to { it.bound }, "boundSelected" to { it.boundSelected },
        "intervalSelected" to { it.intervalSelected }, "intervalHover" to { it.intervalHover }, "playhead" to { it.playhead },
        "cursor" to { it.cursor }, "selectionRange" to { it.selectionRange }, "tierText" to { it.tierText },
    )

    fun encode(t: Tokens, id: String, name: String): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", id)
        put("name", name)
        put("base", t.id)
        put("dark", t.dark)
        put("radius", t.radius.value)
        put("borderWidth", t.borderWidth.value)
        put("square", t.square)
        put("mono", t.mono)
        put("checkboxes", t.checkboxes)
        if (t.boundLine != Color.Unspecified) put("boundLine", hex(t.boundLine))
        put("boundWidth", t.boundWidth)
        put("boundStyle", t.boundStyle)
        put("colors", buildJsonObject { for ((k, f) in colorKeys) put(k, hex(f(t))) })
        put("tierColors", JsonArray(t.tierColors.map { JsonPrimitive(hex(it)) }))
        put("spectrogram", JsonArray(t.spectrogram.map { JsonPrimitive(hex(it)) }))
    })

    fun decode(text: String): Pair<Tokens, String>? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val base = Themes.builtIn.firstOrNull { it.id == o["base"]?.jsonPrimitive?.content } ?: Themes.modernDark
        val cs = o["colors"]?.jsonObject ?: JsonObject(emptyMap())
        fun c(k: String, d: Color) = cs[k]?.jsonPrimitive?.content?.let { color(it) } ?: d
        fun list(k: String, d: List<Color>) = o[k]?.jsonArray?.mapNotNull { color(it.jsonPrimitive.content) }?.takeIf { it.size >= 2 } ?: d
        val t = base.copy(
            id = o["id"]?.jsonPrimitive?.content ?: return@runCatching null,
            dark = o["dark"]?.jsonPrimitive?.booleanOrNull ?: base.dark,
            bg = c("bg", base.bg), panel = c("panel", base.panel), panelAlt = c("panelAlt", base.panelAlt), border = c("border", base.border),
            text = c("text", base.text), muted = c("muted", base.muted), accent = c("accent", base.accent), onAccent = c("onAccent", base.onAccent),
            danger = c("danger", base.danger), ok = c("ok", base.ok), warn = c("warn", base.warn), laneBg = c("laneBg", base.laneBg),
            wave = c("wave", base.wave), waveCenter = c("waveCenter", base.waveCenter), bound = c("bound", base.bound),
            boundSelected = c("boundSelected", base.boundSelected), intervalSelected = c("intervalSelected", base.intervalSelected),
            intervalHover = c("intervalHover", base.intervalHover), playhead = c("playhead", base.playhead), cursor = c("cursor", base.cursor),
            selectionRange = c("selectionRange", base.selectionRange), tierText = c("tierText", base.tierText),
            tierColors = list("tierColors", base.tierColors), spectrogram = list("spectrogram", base.spectrogram),
            radius = (o["radius"]?.jsonPrimitive?.floatOrNull ?: base.radius.value).dp,
            borderWidth = (o["borderWidth"]?.jsonPrimitive?.floatOrNull ?: base.borderWidth.value).dp,
            square = o["square"]?.jsonPrimitive?.booleanOrNull ?: base.square,
            mono = o["mono"]?.jsonPrimitive?.booleanOrNull ?: base.mono,
            checkboxes = o["checkboxes"]?.jsonPrimitive?.booleanOrNull ?: base.checkboxes,
            boundLine = o["boundLine"]?.jsonPrimitive?.content?.let { color(it) } ?: base.boundLine,
            boundWidth = o["boundWidth"]?.jsonPrimitive?.floatOrNull ?: base.boundWidth,
            boundStyle = o["boundStyle"]?.jsonPrimitive?.content ?: base.boundStyle,
        )
        t to (o["name"]?.jsonPrimitive?.content ?: t.id)
    }.getOrNull()

    fun dir(dataDir: String) = Paths.join(dataDir, "themes")

    /** Loads *.json from the themes folder into [Themes.custom]. */
    fun load(dataDir: String) {
        val d = dir(dataDir)
        Themes.custom = if (!PlatformFs.isDirectory(d)) emptyList() else PlatformFs.list(d).filter { Paths.ext(it) == "json" }.sorted()
            .mapNotNull { p -> runCatching { decode(PlatformFs.read(p).decodeToString()) }.getOrNull()?.let { CustomTheme(it.first, it.second, p) } }
    }

    /** Writes [t] as a new editable theme file named [name]; returns its id. */
    fun copy(dataDir: String, t: Tokens, name: String): String {
        PlatformFs.mkdirs(dir(dataDir))
        var k = 1
        while (PlatformFs.exists(Paths.join(dir(dataDir), "my-theme-$k.json"))) k++
        val id = "my-theme-$k"
        PlatformFs.write(Paths.join(dir(dataDir), "$id.json"), encode(t, id, name).encodeToByteArray())
        load(dataDir)
        return id
    }

    /** Saves an edited theme back to its file and reloads. */
    fun save(dataDir: String, theme: CustomTheme, t: Tokens, name: String) {
        PlatformFs.write(theme.path, encode(t, theme.tokens.id, name).encodeToByteArray())
        load(dataDir)
    }

    fun delete(dataDir: String, theme: CustomTheme) {
        PlatformFs.delete(theme.path)
        load(dataDir)
    }

    /** Replaces one colour by its key in [colorKeys]. */
    fun withColor(t: Tokens, key: String, c: Color): Tokens = when (key) {
        "bg" -> t.copy(bg = c); "panel" -> t.copy(panel = c); "panelAlt" -> t.copy(panelAlt = c); "border" -> t.copy(border = c)
        "text" -> t.copy(text = c); "muted" -> t.copy(muted = c); "accent" -> t.copy(accent = c); "onAccent" -> t.copy(onAccent = c)
        "danger" -> t.copy(danger = c); "ok" -> t.copy(ok = c); "warn" -> t.copy(warn = c); "laneBg" -> t.copy(laneBg = c)
        "wave" -> t.copy(wave = c); "waveCenter" -> t.copy(waveCenter = c); "bound" -> t.copy(bound = c)
        "boundSelected" -> t.copy(boundSelected = c); "intervalSelected" -> t.copy(intervalSelected = c)
        "intervalHover" -> t.copy(intervalHover = c); "playhead" -> t.copy(playhead = c); "cursor" -> t.copy(cursor = c)
        "selectionRange" -> t.copy(selectionRange = c); "tierText" -> t.copy(tierText = c)
        else -> t
    }
}
