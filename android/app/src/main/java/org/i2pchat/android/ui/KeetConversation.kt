package org.i2pchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.i2pchat.android.ChatImageThumb
import org.i2pchat.android.MediaKind
import org.i2pchat.android.MessageUi
import org.i2pchat.android.R
import org.i2pchat.android.ReplyQuoteParts
import org.i2pchat.android.mediaKindForName
import org.i2pchat.android.splitReplyQuote

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeetMessageBubble(
    msg: MessageUi,
    mine: Boolean,
    showSender: Boolean,
    textStyle: TextStyle,
    onOpenFile: (String) -> Unit,
    onOpenSealed: (String) -> Unit = {},
    onOpenMemoryImage: (String) -> Unit = {},
    resolveSealed: (String, (String) -> Unit) -> Unit = { _, done -> done("") },
    onQuoteClick: (ReplyQuoteParts) -> Unit = {},
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
    val fileLabel = msg.fileCaption.ifBlank {
        msg.filePath.substringAfterLast('/').ifBlank {
            msg.sealedFile.ifBlank { msg.text.removePrefix("[file] ").trim() }
        }
    }
    val audioCandidate = msg.filePath.ifBlank { fileLabel }
    val isAudio = mediaKindForName(audioCandidate) == MediaKind.Audio ||
        mediaKindForName(fileLabel) == MediaKind.Audio
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
                        when {
                            msg.memoryImage.isNotBlank() -> onOpenMemoryImage(msg.memoryImage)
                            msg.imagePath.isNotBlank() -> onOpenFile(msg.imagePath)
                            isAudio -> Unit // controls live in the bubble
                            msg.filePath.isNotBlank() -> onOpenFile(msg.filePath)
                            msg.sealedFile.isNotBlank() -> onOpenSealed(msg.sealedFile)
                        }
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
            if (msg.imagePath.isNotBlank() || msg.memoryImage.isNotBlank()) {
                ChatImageThumb(
                    path = msg.imagePath,
                    memoryKey = msg.memoryImage,
                    onOpen = {
                        when {
                            msg.memoryImage.isNotBlank() -> onOpenMemoryImage(msg.memoryImage)
                            msg.imagePath.isNotBlank() -> onOpenFile(msg.imagePath)
                        }
                    },
                    onLongClick = onLongClick,
                )
                if (msg.fileCaption.isNotBlank()) {
                    Text(msg.fileCaption, style = MaterialTheme.typography.labelSmall, color = foreground)
                }
            } else if (isAudio && (msg.filePath.isNotBlank() || msg.sealedFile.isNotBlank() || msg.text.startsWith("[file] "))) {
                InlineAudioPlayer(
                    name = fileLabel.ifBlank { "audio" },
                    path = msg.filePath,
                    sealedId = msg.sealedFile,
                    resolveSealed = resolveSealed,
                    color = foreground,
                )
            } else if (msg.filePath.isNotBlank() || msg.sealedFile.isNotBlank() || msg.text.startsWith("[file] ")) {
                val path = msg.filePath
                Text(
                    "📎 ${msg.fileCaption.ifBlank { msg.text }}",
                    color = foreground,
                )
                if (path.isNotBlank() || msg.sealedFile.isNotBlank()) {
                    Text(
                        stringResource(R.string.tap_to_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.7f),
                    )
                }
            } else if (msg.text.startsWith("[image] ")) {
                // The picture is gone: the cache was cleared, or the
                // in-memory copy was dropped after "Do not keep files".
                Text("📷 ${msg.fileCaption.ifBlank { msg.text }}", color = foreground)
            } else {
                val quote = splitReplyQuote(msg.text)
                if (quote == null) {
                    Text(msg.text, style = textStyle, color = foreground)
                } else {
                    ReplyQuoteBlock(
                        quote = quote,
                        textStyle = textStyle,
                        color = foreground,
                        onClick = { onQuoteClick(quote) },
                        onLongClick = onLongClick,
                    )
                    if (quote.answer.isNotBlank()) {
                        Text(quote.answer, style = textStyle, color = foreground)
                    }
                }
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

/** A reply's quoted lines behind a vertical bar, with the `@author` line on top. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyQuoteBlock(
    quote: ReplyQuoteParts,
    textStyle: TextStyle,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = 0.6f)),
        )
        Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
            if (quote.author.isNotBlank()) {
                Text(
                    "@${quote.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.8f),
                )
            }
            Text(
                quote.quoted,
                style = textStyle,
                color = color.copy(alpha = 0.75f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
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
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** A touch landed on the text field; the screen swaps the emoji panel for the keyboard. */
    onFieldTap: () -> Unit = {},
) {
    val currentFieldTap by rememberUpdatedState(onFieldTap)
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
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .focusRequester(focusRequester)
                .pointerInput(Unit) {
                    // Watch only: the field still handles the touch itself.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        currentFieldTap()
                    }
                },
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
