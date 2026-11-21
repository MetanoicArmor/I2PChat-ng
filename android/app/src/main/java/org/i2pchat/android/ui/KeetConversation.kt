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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.i2pchat.android.ChatImageThumb
import org.i2pchat.android.MediaKind
import org.i2pchat.android.MessageUi
import org.i2pchat.android.PendingReply
import org.i2pchat.android.R
import org.i2pchat.android.ReplyQuoteParts
import org.i2pchat.android.TransferProgressUi
import org.i2pchat.android.splitReplyQuote
import org.i2pchat.android.summarizeForReply
import java.util.Locale

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
    val attachment = msg.attachment
    val fileLabel = attachment?.displayName.orEmpty().ifBlank { msg.fileCaption }
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
                        val att = attachment ?: return@combinedClickable
                        when {
                            att.hasMemory -> onOpenMemoryImage(att.memoryKey)
                            att.hasOpenablePath -> {
                                if (att.kind != MediaKind.Audio || att.inlinePreview) {
                                    onOpenFile(att.path)
                                }
                            }
                            att.hasSealed && att.kind != MediaKind.Audio -> onOpenSealed(att.sealedId)
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
            if (attachment != null && attachment.inlinePreview &&
                (attachment.hasOpenablePath || attachment.hasMemory)
            ) {
                ChatImageThumb(
                    path = attachment.path,
                    memoryKey = attachment.memoryKey,
                    onOpen = {
                        when {
                            attachment.hasMemory -> onOpenMemoryImage(attachment.memoryKey)
                            attachment.hasOpenablePath -> onOpenFile(attachment.path)
                        }
                    },
                    onLongClick = onLongClick,
                )
                if (attachment.displayName.isNotBlank()) {
                    Text(attachment.displayName, style = MaterialTheme.typography.labelSmall, color = foreground)
                }
            } else if (attachment != null && attachment.kind == MediaKind.Audio &&
                (attachment.hasOpenablePath || attachment.hasSealed || !attachment.inlinePreview)
            ) {
                InlineAudioPlayer(
                    name = fileLabel.ifBlank { "audio" },
                    path = attachment.path,
                    sealedId = attachment.sealedId,
                    resolveSealed = resolveSealed,
                    color = foreground,
                )
            } else if (attachment != null && !attachment.inlinePreview) {
                val prefix = when (attachment.kind) {
                    MediaKind.Video -> "🎬"
                    MediaKind.Image -> "🖼"
                    else -> "📎"
                }
                Text(
                    "$prefix ${attachment.displayName.ifBlank { msg.text }}",
                    color = foreground,
                )
                if (attachment.hasOpenablePath || attachment.hasSealed) {
                    Text(
                        stringResource(R.string.tap_to_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.7f),
                    )
                }
            } else if (attachment != null && attachment.inlinePreview) {
                // Picture line remains but bytes are gone (cache / ephemeral).
                Text("📷 ${attachment.displayName.ifBlank { msg.text }}", color = foreground)
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
    val deleted = quote.isDeleted
    val preview = if (deleted) {
        stringResource(R.string.reply_quote_deleted)
    } else {
        summarizeForReply(quote.quoted)
    }
    val clickable = !deleted
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = { if (clickable) onClick() },
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = if (deleted) 0.35f else 0.6f)),
        )
        Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
            if (!deleted && quote.author.isNotBlank()) {
                Text(
                    "@${quote.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.8f),
                )
            }
            Text(
                preview,
                style = textStyle,
                color = color.copy(alpha = if (deleted) 0.55f else 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Insert [chunk] at the caret (or replace the selection) and leave the caret after it. */
fun TextFieldValue.insertAtCaret(chunk: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val next = text.replaceRange(start, end, chunk)
    val caret = (start + chunk.length).coerceIn(0, next.length)
    return copy(text = next, selection = TextRange(caret))
}

/** Cap growing multiline compose (~5–6 lines) before the field scrolls internally. */
private val ComposerFieldMaxHeight = 128.dp

private fun formatTransferBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
fun TransferProgressBars(
    transfers: List<TransferProgressUi>,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        transfers.forEach { transfer ->
            TransferProgressRow(transfer)
        }
    }
}

@Composable
private fun TransferProgressRow(transfer: TransferProgressUi) {
    val action = when {
        transfer.outcome == "failed" -> stringResource(R.string.transfer_failed)
        transfer.outcome == "completed" -> stringResource(R.string.transfer_done)
        transfer.image && transfer.incoming -> stringResource(R.string.transfer_receiving_image)
        transfer.image -> stringResource(R.string.transfer_sending_image)
        transfer.incoming -> stringResource(R.string.transfer_receiving_file)
        else -> stringResource(R.string.transfer_sending_file)
    }
    val progress = when (transfer.outcome) {
        "completed" -> 1f
        "failed" -> transfer.percent / 100f
        else -> (transfer.percent / 100f).coerceIn(0f, 1f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$action: ${transfer.name}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.transfer_percent, transfer.percent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surface,
        )
        if (transfer.size > 0L) {
            Text(
                stringResource(
                    R.string.transfer_bytes,
                    formatTransferBytes(transfer.transferred.coerceAtMost(transfer.size)),
                    formatTransferBytes(transfer.size),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun KeetComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onToggleEmoji: () -> Unit,
    showAttach: Boolean,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** A touch landed on the text field; the screen swaps the emoji panel for the keyboard. */
    onFieldTap: () -> Unit = {},
    /** Outer composer height in px; parent scrolls chat history when this grows and sticky. */
    onHeightChanged: (Int) -> Unit = {},
    pendingReply: PendingReply? = null,
    onClearPendingReply: () -> Unit = {},
) {
    val currentFieldTap by rememberUpdatedState(onFieldTap)
    val currentHeightChanged by rememberUpdatedState(onHeightChanged)
    var attach by remember { mutableStateOf(false) }
    val fieldScroll = rememberScrollState()
    // Keep the caret in view inside the capped field when typing at the end.
    LaunchedEffect(value.text, value.selection) {
        if (value.selection.end >= value.text.length) {
            fieldScroll.scrollTo(fieldScroll.maxValue)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { currentHeightChanged(it.height) },
    ) {
        if (pendingReply != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.reply_quote_bar),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "@${pendingReply.author}: ${pendingReply.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClearPendingReply) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.reply_quote_clear),
                    )
                }
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.Bottom,
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
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .heightIn(max = ComposerFieldMaxHeight)
                .verticalScroll(fieldScroll)
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
                    if (value.text.isEmpty()) {
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
}
