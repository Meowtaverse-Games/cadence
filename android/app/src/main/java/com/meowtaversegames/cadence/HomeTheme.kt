package com.meowtaversegames.cadence

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Activity-owned observer. Binder reads happen off main; stale reads never overwrite callbacks. */
class WallpaperObserver(context: Context, private val publish: (WallpaperPalette?) -> Unit) : AutoCloseable {
    private val manager = WallpaperManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private var closed = false
    private fun palette(colors: WallpaperColors?): WallpaperPalette? = colors?.let {
        WallpaperPalette(it.primaryColor.toArgb(), if (Build.VERSION.SDK_INT >= 31)
            it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 else null)
    }
    private val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (!closed && which and WallpaperManager.FLAG_SYSTEM != 0) {
            generation.incrementAndGet()
            publish(palette(colors))
        }
    }
    init { manager.addOnColorsChangedListener(listener, handler); refresh() }
    fun refresh() {
        val requested = generation.incrementAndGet()
        worker.execute {
            val result = try { palette(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)) }
                catch (_: RuntimeException) { null }
            handler.post { if (!closed && requested == generation.get()) publish(result) }
        }
    }
    override fun close() {
        closed = true
        generation.incrementAndGet()
        manager.removeOnColorsChangedListener(listener)
        worker.shutdownNow()
    }
}

data class HomeColors(val darkText: Boolean, val accent: Color) {
    val text = if (darkText) Color.Black else Color.White
    val opposite = if (darkText) Color.White else Color.Black
    val plate = Color(0xFF000000.toInt() or accentBackground(darkText))
    val shadow = Shadow(opposite.copy(alpha = .85f), Offset(0f, 1f), 4f)
}
val LocalHomeColors = staticCompositionLocalOf { HomeColors(false, Color(0xFF79D8BE)) }

@Composable
fun homeColors(settings: HomeThemeSettings, wallpaper: WallpaperPalette?): HomeColors {
    val darkText = usesDarkText(settings.tone, wallpaper)
    val context = LocalContext.current
    val source = settings.accent.rgb ?: if (Build.VERSION.SDK_INT >= 31) {
        // Re-evaluate on wallpaper publication, including when the activity stays alive.
        if (darkText) dynamicLightColorScheme(context).primary.toArgb() else dynamicDarkColorScheme(context).primary.toArgb()
    } else wallpaper?.primary ?: 0x398B81
    return HomeColors(darkText, Color(0xFF000000.toInt() or readableAccent(source, darkText)))
}

@Composable
fun CadenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}

@Composable
fun LauncherTheme(colors: HomeColors, content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.shaded() = copy(color = colors.text, shadow = colors.shadow)
    val typography = base.copy(
        displayLarge = base.displayLarge.shaded(), displayMedium = base.displayMedium.shaded(), displaySmall = base.displaySmall.shaded(),
        headlineLarge = base.headlineLarge.shaded(), headlineMedium = base.headlineMedium.shaded(), headlineSmall = base.headlineSmall.shaded(),
        titleLarge = base.titleLarge.shaded(), titleMedium = base.titleMedium.shaded(), titleSmall = base.titleSmall.shaded(),
        bodyLarge = base.bodyLarge.shaded(), bodyMedium = base.bodyMedium.shaded(), bodySmall = base.bodySmall.shaded(),
        labelLarge = base.labelLarge.shaded(), labelMedium = base.labelMedium.shaded(), labelSmall = base.labelSmall.shaded())
    val scheme = (if (colors.darkText) lightColorScheme() else darkColorScheme()).copy(
        primary = colors.text, onSurface = colors.text, onBackground = colors.text, onSurfaceVariant = colors.text,
        surface = colors.plate)
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalHomeColors provides colors, LocalContentColor provides colors.text, content = content)
    }
}
