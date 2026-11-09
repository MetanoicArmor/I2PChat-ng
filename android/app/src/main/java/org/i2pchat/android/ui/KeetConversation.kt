package org.i2pchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.i2pchat.android.ChatImageThumb
import org.i2pchat.android.MessageUi
import org.i2pchat.android.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeetMessageBubble(
    msg: MessageUi,
    mine: Boolean,
    showSender: Boolean,
    textStyle: TextStyle,
    onOpenFile: (String) -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else 4.dp,
        bottomEnd = if (mine) 4.dp else 18.dp,
    )
    val background = if (mine) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (mine) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(background)
                .combinedClickable(
                    onClick = {
                        val path = msg.imagePath.ifBlank { msg.filePath }
                        if (path.isNotBlank()) onOpenFile(path)
                    },
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showSender && msg.sender.isNotBlank()) {
                Text(
                    msg.sender.take(18),
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.75f),
                )
            }
            if (msg.imagePath.isNotBlank()) {
                ChatImageThumb(msg.imagePath)
                if (msg.fileCaption.isNotBlank()) {
                    Text(msg.fileCaption, style = MaterialTheme.typography.labelSmall, color = foreground)
                }
            } else if (msg.filePath.isNotBlank() || msg.text.startsWith("[file] ")) {
                val path = msg.filePath
                Text(
                    "📎 ${msg.fileCaption.ifBlank { msg.text }}",
                    color = foreground,
                )
                if (path.isNotBlank()) {
                    Text(
                        stringResource(R.string.tap_to_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.7f),
                    )
                }
            } else if (msg.text.startsWith("[image] ")) {
                // The picture file is gone, for example after "Do not keep
                // files" cleared its cache: show the name, not the local path.
                Text("📷 ${msg.fileCaption.ifBlank { msg.text }}", color = foreground)
            } else {
                Text(msg.text, style = textStyle, color = foreground)
            }
            val meta = listOf(
                shortClock(msg.ts),
                when (msg.delivery) {
                    "delivered" -> "✓"
                    "sent" -> "·"
                    "queued" -> "⧗"
                    "failed" -> "✗"
                    "sending" -> "…"
                    else -> ""
                },
            ).filter { it.isNotBlank() }.joinToString(" ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
fun KeetComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleEmoji: () -> Unit,
    showAttach: Boolean,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
) {
    var attach by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAttach) {
            Box {
                IconButton(onClick = { attach = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_attach))
                }
                DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cd_image)) },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = {
                            attach = false
                            onPickImage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cd_file)) },
                        leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                        onClick = {
                            attach = false
                            onPickFile()
                        },
                    )
                }
            }
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.message_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = onToggleEmoji) {
            Icon(Icons.Default.EmojiEmotions, contentDescription = stringResource(R.string.cd_emoji))
        }
        IconButton(onClick = onSend) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
