package org.i2pchat.android.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Motif drawn over the chat wallpaper; each theme gets its own feel. */
enum class ChatMotif {
    Dots,
    Diamonds,
    Grid,
    Diagonals,
    Waves,
    Leaves,
    Hex,
    Circles,
}

fun chatMotifForTheme(themeId: String): ChatMotif = when (themeId) {
    "tokyo-night" -> ChatMotif.Grid
    "dracula" -> ChatMotif.Diamonds
    "nord" -> ChatMotif.Dots
    "gruvbox" -> ChatMotif.Diagonals
    "monokai" -> ChatMotif.Hex
    "catppuccin-mocha" -> ChatMotif.Circles
    "catppuccin-latte" -> ChatMotif.Dots
    "solarized-dark" -> ChatMotif.Waves
    "solarized-light" -> ChatMotif.Leaves
    "rose-pine" -> ChatMotif.Circles
    "atom-one-dark" -> ChatMotif.Grid
    "flexoki" -> ChatMotif.Diagonals
    "light" -> ChatMotif.Leaves
    "dark" -> ChatMotif.Dots
    else -> ChatMotif.Dots
}

private fun mix(a: Color, b: Color, t: Float): Color {
    val keep = 1f - t
    return Color(
        red = a.red * keep + b.red * t,
        green = a.green * keep + b.green * t,
        blue = a.blue * keep + b.blue * t,
        alpha = a.alpha * keep + b.alpha * t,
    )
}

/**
 * Themed wallpaper behind the message list. Base wash follows the active
 * ColorScheme; the motif and accent tint follow the named terminal palette.
 */
@Composable
fun ChatConversationBackground(
    themeId: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(themeId) { terminalPalette(themeId) }
    val motif = remember(themeId) { chatMotifForTheme(themeId) }
    val base = scheme.background
    val primary = palette?.primary ?: scheme.primary
    val secondary = palette?.secondary ?: scheme.secondary
    Canvas(modifier = modifier) {
        val top = mix(base, primary, if (palette?.dark == false) 0.10f else 0.16f)
        val mid = base
        val bottom = mix(base, secondary, if (palette?.dark == false) 0.08f else 0.14f)
        drawRect(
            brush = Brush.verticalGradient(listOf(top, mid, bottom)),
            size = size,
        )
        val ink = primary.copy(alpha = if (palette?.dark == false) 0.10f else 0.14f)
        val ink2 = secondary.copy(alpha = if (palette?.dark == false) 0.07f else 0.10f)
        when (motif) {
            ChatMotif.Dots -> drawDots(ink, ink2)
            ChatMotif.Diamonds -> drawDiamonds(ink)
            ChatMotif.Grid -> drawGrid(ink, ink2)
            ChatMotif.Diagonals -> drawDiagonals(ink)
            ChatMotif.Waves -> drawWaves(ink, ink2)
            ChatMotif.Leaves -> drawLeaves(ink)
            ChatMotif.Hex -> drawHex(ink)
            ChatMotif.Circles -> drawCircles(ink, ink2)
        }
    }
}

private fun DrawScope.drawDots(a: Color, b: Color) {
    val step = 28f
    var row = 0
    var y = step / 2f
    while (y < size.height + step) {
        var x = if (row % 2 == 0) step / 2f else step
        while (x < size.width + step) {
            drawCircle(color = if ((row + x.toInt()) % 3 == 0) b else a, radius = 1.6f, center = Offset(x, y))
            x += step
        }
        y += step
        row++
    }
}

private fun DrawScope.drawDiamonds(color: Color) {
    val step = 36f
    var y = 0f
    var row = 0
    while (y < size.height + step) {
        var x = if (row % 2 == 0) 0f else step / 2f
        while (x < size.width + step) {
            val path = Path().apply {
                moveTo(x, y + step / 2f)
                lineTo(x + step / 2f, y)
                lineTo(x + step, y + step / 2f)
                lineTo(x + step / 2f, y + step)
                close()
            }
            drawPath(path, color = color, style = Stroke(width = 1.2f))
            x += step
        }
        y += step
        row++
    }
}

private fun DrawScope.drawGrid(a: Color, b: Color) {
    val step = 32f
    var x = step
    while (x < size.width) {
        drawLine(a, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(b, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawDiagonals(color: Color) {
    val step = 24f
    val span = size.width + size.height
    var t = -size.height
    while (t < span) {
        drawLine(
            color,
            Offset(t, 0f),
            Offset(t + size.height, size.height),
            strokeWidth = 1.1f,
        )
        t += step
    }
}

private fun DrawScope.drawWaves(a: Color, b: Color) {
    val amp = 10f
    val waveLen = 56f
    var y = 20f
    var row = 0
    while (y < size.height) {
        val path = Path()
        path.moveTo(0f, y)
        var x = 0f
        while (x <= size.width) {
            val yy = y + sin((x / waveLen) * 2f * PI.toFloat()) * amp
            path.lineTo(x, yy)
            x += 4f
        }
        drawPath(path, color = if (row % 2 == 0) a else b, style = Stroke(width = 1.3f))
        y += 34f
        row++
    }
}

private fun DrawScope.drawLeaves(color: Color) {
    val step = 40f
    var row = 0
    var y = step / 2f
    while (y < size.height + step) {
        var x = if (row % 2 == 0) step / 2f else step
        while (x < size.width + step) {
            val path = Path().apply {
                moveTo(x, y - 6f)
                quadraticTo(x + 7f, y, x, y + 6f)
                quadraticTo(x - 7f, y, x, y - 6f)
                close()
            }
            drawPath(path, color = color)
            x += step
        }
        y += step
        row++
    }
}

private fun DrawScope.drawHex(color: Color) {
    val r = 14f
    val dx = r * 1.75f
    val dy = r * 1.5f
    var row = 0
    var y = r
    while (y < size.height + r) {
        var x = if (row % 2 == 0) r else r + dx / 2f
        while (x < size.width + r) {
            val path = Path()
            for (i in 0 until 6) {
                val ang = (PI / 3.0 * i - PI / 6.0).toFloat()
                val px = x + cos(ang) * r
                val py = y + sin(ang) * r
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, color = color, style = Stroke(width = 1.1f))
            x += dx
        }
        y += dy
        row++
    }
}

private fun DrawScope.drawCircles(a: Color, b: Color) {
    val step = 48f
    var row = 0
    var y = step / 2f
    while (y < size.height + step) {
        var x = if (row % 2 == 0) step / 2f else step
        while (x < size.width + step) {
            val radius = min(9f, step / 4f)
            drawCircle(
                color = if (row % 2 == 0) a else b,
                radius = radius,
                center = Offset(x, y),
                style = Stroke(width = 1.2f),
            )
            x += step
        }
        y += step
        row++
    }
}

/** Tiny swatch for theme chips: gradient + a hint of the motif color. */
@Composable
fun ThemePreviewSwatch(
    themeId: String,
    modifier: Modifier = Modifier,
) {
    val palette = remember(themeId) { terminalPalette(themeId) }
    val scheme = MaterialTheme.colorScheme
    val (bg, primary) = when {
        palette != null -> palette.background to palette.primary
        themeId == "light" -> Color(0xFFF3F4F6) to Color(0xFF2F7D1E)
        themeId == "dark" -> Color(0xFF0B0C0E) to Color(0xFF6FBF3A)
        else -> scheme.background to scheme.primary // system / unknown
    }
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(mix(bg, primary, 0.22f), bg, mix(bg, primary, 0.12f)),
            ),
            size = Size(size.width, size.height),
        )
        drawCircle(
            color = primary.copy(alpha = 0.55f),
            radius = size.minDimension * 0.18f,
            center = Offset(size.width * 0.7f, size.height * 0.35f),
        )
    }
}
