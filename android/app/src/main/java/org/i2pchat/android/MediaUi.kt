package org.i2pchat.android

import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ChatImageThumb(path: String, onOpen: () -> Unit) {
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val maxSide = 1280
            while (
                (bounds.outWidth / sample) > maxSide ||
                (bounds.outHeight / sample) > maxSide
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = File(path).name,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpen),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            "📷 ${File(path).name.ifBlank { path }}",
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

@Composable
fun ChatVideoThumb(path: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Videocam, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(File(path).name, style = MaterialTheme.typography.bodyMedium)
            Text("Tap to play", style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
    }
}

@Composable
fun InlineAudioPlayer(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = remember(path) { MediaUtils.contentUri(context, path) }
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            if (uri != null) {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, playing, scrubbing) {
        while (isActive) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                if (durationMs <= 0L && player.duration > 0L) {
                    durationMs = player.duration
                }
            }
            delay(200)
        }
    }

    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AudioFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                File(path).name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
        }
        Slider(
            value = if (scrubbing) scrubValue else progress,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                val target = (scrubValue * durationMs).toLong()
                player.seekTo(target)
                positionMs = target
                scrubbing = false
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = durationMs > 0L || player.duration > 0L,
        )
        Text(
            "${formatDuration(if (scrubbing) (scrubValue * durationMs).toLong() else positionMs)} / ${formatDuration(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun MediaViewerDialog(path: String, onDismiss: () -> Unit) {
    val kind = MediaUtils.kindFor(path)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when (kind) {
                MediaKind.Image -> FullscreenImage(path, Modifier.fillMaxSize())
                MediaKind.Video -> FullscreenVideo(path, Modifier.fillMaxSize())
                else -> {
                    Text(
                        File(path).name,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                File(path).name,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun FullscreenImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val maxSide = 4096
            while (
                (bounds.outWidth / sample) > maxSide ||
                (bounds.outHeight / sample) > maxSide
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = File(path).name,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            "Cannot open image",
            color = Color.White,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun FullscreenVideo(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = remember(path) { MediaUtils.contentUri(context, path) }
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            if (uri != null) {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = true
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

private fun formatDuration(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
