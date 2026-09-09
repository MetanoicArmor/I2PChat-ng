package org.i2pchat.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DFFB3),
    onPrimary = Color(0xFF003920),
    background = Color(0xFF101410),
    surface = Color(0xFF171C18),
    surfaceVariant = Color(0xFF232A24),
    onBackground = Color(0xFFE2E8E2),
    onSurface = Color(0xFFE2E8E2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C45),
    onPrimary = Color.White,
    background = Color(0xFFF6F8F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EEE8),
    onBackground = Color(0xFF161D18),
    onSurface = Color(0xFF161D18),
)

@Composable
fun I2PChatTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
