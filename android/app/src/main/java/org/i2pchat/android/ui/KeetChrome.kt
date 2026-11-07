package org.i2pchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AvatarPalette = listOf(
    Color(0xFF3D7AD6),
    Color(0xFF2E9B68),
    Color(0xFFE0A83A),
    Color(0xFFD15A5A),
    Color(0xFF8E62D6),
    Color(0xFF2A9D96),
    Color(0xFFE07A45),
    Color(0xFF5C86E0),
)

fun avatarColor(seed: String): Color {
    val hash = seed.fold(0) { acc, ch -> acc * 31 + ch.code }
    return AvatarPalette[kotlin.math.abs(hash) % AvatarPalette.size]
}

fun avatarLetter(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().uppercase()
}

/** Clock portion of an ISO-ish timestamp, or the raw string when it is already short. */
fun shortClock(ts: String): String {
    val text = ts.trim()
    if (text.length >= 16 && (text[10] == 'T' || text[10] == ' ')) {
        return text.substring(11, 16)
    }
    return text.take(5)
}

@Composable
fun PeerAvatar(
    seed: String,
    label: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier.then(click)
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(seed)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarLetter(label),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (size < 40.dp) 14.sp else 18.sp,
        )
    }
}

@Composable
fun UnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .size(if (count > 9) 22.dp else 18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
