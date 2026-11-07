package org.i2pchat.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Ink = Color(0xFF0B0C0E)
private val InkRaised = Color(0xFF141518)
private val InkBubble = Color(0xFF1C1E22)
private val Grass = Color(0xFF6FBF3A)
private val GrassInk = Color(0xFF102008)

private val Paper = Color(0xFFF3F4F6)
private val PaperRaised = Color(0xFFFFFFFF)
private val PaperBubble = Color(0xFFE7E8EC)
private val Leaf = Color(0xFF2F7D1E)

private val DarkColors = darkColorScheme(
    primary = Grass,
    onPrimary = GrassInk,
    primaryContainer = Color(0xFF1E4A12),
    onPrimaryContainer = Color(0xFFD4F0A8),
    secondary = Grass,
    onSecondary = GrassInk,
    secondaryContainer = Color(0xFF1E4A12),
    onSecondaryContainer = Color(0xFFD4F0A8),
    background = Ink,
    surface = InkRaised,
    surfaceVariant = InkBubble,
    onBackground = Color(0xFFF2F3F5),
    onSurface = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF9AA0A8),
    outline = Color(0xFF3A3D44),
)

private val LightColors = lightColorScheme(
    primary = Leaf,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F0B8),
    onPrimaryContainer = Color(0xFF16300A),
    secondary = Leaf,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F0B8),
    onSecondaryContainer = Color(0xFF16300A),
    background = Paper,
    surface = PaperRaised,
    surfaceVariant = PaperBubble,
    onBackground = Color(0xFF141518),
    onSurface = Color(0xFF141518),
    onSurfaceVariant = Color(0xFF5C616A),
    outline = Color(0xFFD0D2D8),
)

/** Named schemes from the terminal UI (Textual), same ids as `app.theme`. */
data class TerminalPalette(
    val id: String,
    val label: String,
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val panel: Color,
    val foreground: Color,
    val primary: Color,
    val secondary: Color,
    val error: Color,
)

val TerminalPalettes = listOf(
    TerminalPalette("tokyo-night", "Tokyo Night", true, Color(0xFF1A1B26), Color(0xFF24283B), Color(0xFF414868), Color(0xFFA9B1D6), Color(0xFFBB9AF7), Color(0xFF7AA2F7), Color(0xFFF7768E)),
    TerminalPalette("dracula", "Dracula", true, Color(0xFF282A36), Color(0xFF2B2E3B), Color(0xFF313442), Color(0xFFF8F8F2), Color(0xFFBD93F9), Color(0xFF6272A4), Color(0xFFFF5555)),
    TerminalPalette("nord", "Nord", true, Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF434C5E), Color(0xFFD8DEE9), Color(0xFF88C0D0), Color(0xFF81A1C1), Color(0xFFBF616A)),
    TerminalPalette("gruvbox", "Gruvbox", true, Color(0xFF282828), Color(0xFF3C3836), Color(0xFF504945), Color(0xFFFBF1C7), Color(0xFF85A598), Color(0xFFA89A85), Color(0xFFFB4934)),
    TerminalPalette("monokai", "Monokai", true, Color(0xFF272822), Color(0xFF2E2E2E), Color(0xFF3E3D32), Color(0xFFD6D6D6), Color(0xFFAE81FF), Color(0xFFF92672), Color(0xFFF92672)),
    TerminalPalette("catppuccin-mocha", "Catppuccin", true, Color(0xFF181825), Color(0xFF313244), Color(0xFF45475A), Color(0xFFCDD6F4), Color(0xFFF5C2E7), Color(0xFFCBA6F7), Color(0xFFF28FAD)),
    TerminalPalette("catppuccin-latte", "Catppuccin Latte", false, Color(0xFFEFF1F5), Color(0xFFE6E9EF), Color(0xFFCCD0DA), Color(0xFF4C4F69), Color(0xFF8839EF), Color(0xFFDC8A78), Color(0xFFD20F39)),
    TerminalPalette("solarized-dark", "Solarized Dark", true, Color(0xFF002B36), Color(0xFF073642), Color(0xFF073642), Color(0xFF839496), Color(0xFF268BD2), Color(0xFF2AA198), Color(0xFFDC322F)),
    TerminalPalette("solarized-light", "Solarized Light", false, Color(0xFFFDF6E3), Color(0xFFEEE8D5), Color(0xFFEEE8D5), Color(0xFF586E75), Color(0xFF268BD2), Color(0xFF2AA198), Color(0xFFDC322F)),
    TerminalPalette("rose-pine", "Rose Pine", true, Color(0xFF191724), Color(0xFF1F1D2E), Color(0xFF26233A), Color(0xFFE0DEF4), Color(0xFFC4A7E7), Color(0xFF31748F), Color(0xFFEB6F92)),
    TerminalPalette("atom-one-dark", "Atom One Dark", true, Color(0xFF282C34), Color(0xFF3B414D), Color(0xFF4F5666), Color(0xFFABB2BF), Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFFF06262)),
    TerminalPalette("flexoki", "Flexoki", true, Color(0xFF100F0F), Color(0xFF1C1B1A), Color(0xFF282726), Color(0xFFFFFCF0), Color(0xFF205EA6), Color(0xFF24837B), Color(0xFFAF3029)),
)

fun terminalPalette(id: String): TerminalPalette? = TerminalPalettes.firstOrNull { it.id == id }

private fun mix(a: Color, b: Color, t: Float): Color {
    val keep = 1f - t
    return Color(
        red = a.red * keep + b.red * t,
        green = a.green * keep + b.green * t,
        blue = a.blue * keep + b.blue * t,
    )
}

private fun inkOn(fill: Color): Color {
    val luminance = fill.red * 0.299f + fill.green * 0.587f + fill.blue * 0.114f
    return if (luminance > 0.55f) Color(0xFF1A1B1E) else Color.White
}

private fun schemeFor(palette: TerminalPalette): ColorScheme {
    val bubble = if (palette.panel == palette.surface) mix(palette.surface, palette.foreground, 0.16f) else palette.panel
    val onPrimary = inkOn(palette.primary)
    val muted = mix(palette.foreground, palette.background, 0.35f)
    val colors = if (palette.dark) darkColorScheme() else lightColorScheme()
    return colors.copy(
        primary = palette.primary,
        onPrimary = onPrimary,
        primaryContainer = mix(palette.primary, palette.background, 0.72f),
        onPrimaryContainer = palette.foreground,
        secondary = palette.secondary,
        onSecondary = inkOn(palette.secondary),
        secondaryContainer = bubble,
        onSecondaryContainer = palette.primary,
        background = palette.background,
        onBackground = palette.foreground,
        surface = palette.surface,
        onSurface = palette.foreground,
        surfaceVariant = bubble,
        onSurfaceVariant = muted,
        outline = mix(bubble, palette.foreground, 0.25f),
        error = palette.error,
        onError = inkOn(palette.error),
    )
}

@Composable
fun I2PChatTheme(theme: String, content: @Composable () -> Unit) {
    val palette = terminalPalette(theme)
    val dark = palette?.dark ?: when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = palette?.let { schemeFor(it) } ?: if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
