package com.meowtaversegames.cadence

import kotlinx.serialization.json.*
import kotlin.math.pow
import kotlin.math.roundToInt

enum class TextTone(val label: String) { WALLPAPER("壁紙に合わせる"), WHITE("白い文字"), BLACK("黒い文字") }
enum class Accent(val label: String, val rgb: Int?) {
    WALLPAPER("壁紙から", null), DEW("朝露", 0x398B81), TIDE("潮の音", 0x387DC1),
    IRIS("花あかり", 0x9169BF), BERRY("実り", 0xBB557B), CLAY("土のぬくもり", 0xBF7047),
    LEAF("若葉の道", 0x718C39),
}
enum class HomeSize(val label: String, val textSp: Int, val rowDp: Int) {
    SMALL("小", 18, 56), MEDIUM("中", 22, 68), LARGE("大", 26, 80);
    val lineHeightSp: Float get() = textSp * 1.25f
}
enum class ScrollMotion(val label: String) { LOWER_LEFT("左下から"), NONE("なし") }
data class HomeThemeSettings(val tone: TextTone = TextTone.WALLPAPER, val accent: Accent = Accent.WALLPAPER,
    val size: HomeSize = HomeSize.MEDIUM, val scrollMotion: ScrollMotion = ScrollMotion.LOWER_LEFT)

data class WallpaperPalette(val primary: Int, val supportsDarkText: Boolean?)
fun usesDarkText(tone: TextTone, wallpaper: WallpaperPalette?): Boolean = when (tone) {
    TextTone.WHITE -> false
    TextTone.BLACK -> true
    TextTone.WALLPAPER -> wallpaper?.let { it.supportsDarkText ?: (contrastRatio(it.primary, 0x000000) >= contrastRatio(it.primary, 0xFFFFFF)) } ?: false
}
/** Last resolved appearance is used only until the first wallpaper response arrives. */
data class HomeAppearance(val darkText: Boolean, val accentSource: Int)
fun initialDarkText(tone: TextTone, wallpaper: WallpaperPalette?, initial: HomeAppearance?): Boolean =
    if (tone == TextTone.WALLPAPER && initial != null) initial.darkText else usesDarkText(tone, wallpaper)
fun initialAccentSource(accent: Accent, wallpaperSource: Int, initial: HomeAppearance?): Int =
    accent.rgb ?: initial?.accentSource ?: wallpaperSource

fun luminance(rgb: Int): Double {
    fun channel(shift: Int): Double {
        val value = ((rgb shr shift) and 255) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
fun contrastRatio(a: Int, b: Int): Double {
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
/** Reference surfaces for small accents; arbitrary wallpaper pixels cannot guarantee contrast. */
fun accentBackground(darkText: Boolean): Int = if (darkText) 0xFFFFFF else 0x101A20
fun readableAccent(rgb: Int, darkText: Boolean): Int {
    val background = accentBackground(darkText)
    val target = if (darkText) 0 else 255
    for (step in 0..255) {
        val amount = step / 255.0
        fun channel(shift: Int) = ((((rgb shr shift) and 255) * (1 - amount)) + target * amount).roundToInt()
        val candidate = (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        if (contrastRatio(candidate, background) >= 3.0) return candidate
    }
    return if (darkText) 0 else 0xFFFFFF
}

/** The I/O adapter uses AtomicFile. Re-read before writes to protect failures and future keys. */
class HomeThemeStore(private val readFile: () -> String?, private val writeFile: (String) -> Unit,
    private val logFailure: () -> Unit) {
    private fun document(): JsonObject = readFile()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    private inline fun <reified T : Enum<T>> option(doc: JsonObject, key: String, default: T): T {
        val value = doc[key] ?: return default
        require(value is JsonPrimitive && value.isString)
        return enumValues<T>().firstOrNull { it.name == value.content } ?: default
    }
    private fun settings(doc: JsonObject) = HomeThemeSettings(option(doc, "tone", TextTone.WALLPAPER),
        option(doc, "accent", Accent.WALLPAPER), option(doc, "size", HomeSize.MEDIUM),
        option(doc, "scrollMotion", ScrollMotion.LOWER_LEFT))
    @Synchronized fun read(): ReadValue<HomeThemeSettings> = try { ReadValue(settings(document()), false) }
    catch (_: Exception) { logFailure(); ReadValue(HomeThemeSettings(), true) }
    @Synchronized fun save(value: HomeThemeSettings) {
        val doc = try { document().also { settings(it) } } catch (error: Exception) { logFailure(); throw error }
        val fields = doc.toMutableMap()
        // Preserve unknown enum values too when that setting has not been changed.
        val previous = settings(doc)
        if (value.tone != previous.tone || "tone" !in fields) fields["tone"] = JsonPrimitive(value.tone.name)
        if (value.accent != previous.accent || "accent" !in fields) fields["accent"] = JsonPrimitive(value.accent.name)
        if (value.size != previous.size || "size" !in fields) fields["size"] = JsonPrimitive(value.size.name)
        if (value.scrollMotion != previous.scrollMotion || "scrollMotion" !in fields) fields["scrollMotion"] = JsonPrimitive(value.scrollMotion.name)
        writeFile(JsonObject(fields).toString())
    }
}

/** Category glass uses only generated texture and theme colors, never wallpaper pixels. */
object CategoryGlass {
    const val plateAlpha = 0.28f
    const val highlightAlpha = 0.10f
    const val borderTopAlpha = 0.45f
    const val borderBottomAlpha = 0.12f
    const val noiseAlpha = 0.04f
    const val cornerDp = 28
    const val borderDp = 1
    fun plateRgb(darkText: Boolean): Int = if (darkText) 0xFFFFFF else 0x000000
}
