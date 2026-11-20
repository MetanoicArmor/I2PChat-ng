package org.i2pchat.android.ui

import android.media.MediaPlayer
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.i2pchat.android.MediaKind
import org.i2pchat.android.R
import org.i2pchat.android.decodeChatImageThumb

/** What the fullscreen viewer shows: a file on disk, or a picture kept only in memory. */
data class MediaViewerRequest(
    val kind: MediaKind,
    val path: String = "",
    val memoryKey: String = "",
    val title: String = "",
)

/** Survives process/Activity recreation so rotation does not dump the user back to chat. */
val MediaViewerRequestSaver: Saver<MediaViewerRequest?, Any> = run {
    val kindKey = "kind"
    val pathKey = "path"
    val memoryKey = "memoryKey"
    val titleKey = "title"
    mapSaver(
        save = { req ->
            if (req == null) emptyMap()
            else mapOf(
                kindKey to req.kind.name,
                pathKey to req.path,
                memoryKey to req.memoryKey,
                titleKey to req.title,
            )
        },
        restore = { map ->
            if (map.isEmpty()) null
            else MediaViewerRequest(
                kind = MediaKind.valueOf(map[kindKey] as String),
                path = map[pathKey] as String,
                memoryKey = map[memoryKey] as String,
                title = map[titleKey] as String,
            )
        },
    )
}

/** In-app fullscreen image or video viewer. Back or the close button dismisses it. */
@Composable
fun MediaViewerDialog(request: MediaViewerRequest, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (request.kind) {
                MediaKind.Video -> FullscreenVideo(request.path)
                else -> FullscreenImage(request.path, request.memoryKey, request.title)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun FullscreenImage(path: String, memoryKey: String, title: String) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, memoryKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeChatImageThumb(path, memoryKey) }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shown = bitmap
    if (shown == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(title, color = Color.White.copy(alpha = 0.7f))
        }
        return
    }
    Image(
        bitmap = shown,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
}

@Composable
private fun FullscreenVideo(path: String) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
                    true
                }
                // Double-tap left/right seeks ±10s (Kennedy g2387); single taps still reach MediaController.
                var lastTapMs = 0L
                setOnTouchListener { view, event ->
                    if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapMs < 300L) {
                        val delta = if (event.x < view.width / 2f) -10_000 else 10_000
                        val dur = duration.coerceAtLeast(0)
                        seekTo((currentPosition + delta).coerceIn(0, dur))
                        lastTapMs = 0L
                        return@setOnTouchListener true
                    }
                    lastTapMs = now
                    false
                }
                setVideoPath(path)
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

// At most one inline audio message plays at a time.
private var activeAudio: AudioState? = null

private class AudioState {
    var player: MediaPlayer? = null
    var playing by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var position by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)
    var failed by mutableStateOf(false)

    fun pause() {
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
        playing = false
    }

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
        playing = false
        loading = false
        position = 0
        if (activeAudio === this) activeAudio = null
    }

    fun fail() {
        release()
        failed = true
    }

    fun begin(mp: MediaPlayer) {
        if (activeAudio !== this) activeAudio?.pause()
        activeAudio = this
        runCatching { mp.start() }.onFailure { fail(); return }
        playing = true
    }

    fun open(file: String) {
        val mp = MediaPlayer()
        player = mp
        loading = true
        runCatching {
            mp.setDataSource(file)
            mp.setOnPreparedListener {
                loading = false
                duration = it.duration
                begin(it)
            }
            mp.setOnCompletionListener {
                playing = false
                position = 0
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { _, _, _ -> fail(); true }
            mp.prepareAsync()
        }.onFailure { fail() }
    }
}

/**
 * Audio inside the message bubble: play/pause and a progress bar, no popup.
 * [path] is the local file, or blank when only [sealedId] is known; then
 * [resolveSealed] decrypts it on the first play and hands the plain path back
 * (blank on failure).
 */
@Composable
fun InlineAudioPlayer(
    name: String,
    path: String,
    sealedId: String,
    resolveSealed: (String, (String) -> Unit) -> Unit,
    color: Color,
) {
    val context = LocalContext.current
    val audio = remember(path, sealedId) { AudioState() }

    LaunchedEffect(audio.failed) {
        if (audio.failed) {
            audio.failed = false
            Toast.makeText(context, R.string.media_play_failed, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(audio.playing) {
        while (audio.playing) {
            audio.position = audio.player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0
            delay(250)
        }
    }
    DisposableEffect(audio) { onDispose { audio.release() } }

    fun toggle() {
        val mp = audio.player
        when {
            audio.loading -> Unit
            mp == null -> when {
                path.isNotBlank() -> audio.open(path)
                sealedId.isNotBlank() -> {
                    audio.loading = true
                    resolveSealed(sealedId) { plain ->
                        audio.loading = false
                        if (plain.isBlank()) audio.fail() else audio.open(plain)
                    }
                }
            }
            audio.playing -> audio.pause()
            else -> audio.begin(mp)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = ::toggle, modifier = Modifier.size(40.dp)) {
            Icon(
                if (audio.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (audio.playing) R.string.cd_pause else R.string.cd_play),
                tint = color,
            )
        }
        Column(Modifier.padding(start = 8.dp).width(190.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = {
                    if (audio.duration > 0) (audio.position.toFloat() / audio.duration).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(14.dp)
                    .pointerInput(audio.duration, audio.player) {
                        val dur = audio.duration
                        if (dur <= 0) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun seekToX(x: Float) {
                                val frac = (x / size.width.toFloat()).coerceIn(0f, 1f)
                                val pos = (frac * dur).toInt()
                                runCatching { audio.player?.seekTo(pos) }
                                audio.position = pos
                            }
                            seekToX(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                seekToX(change.position.x)
                                change.consume()
                            }
                        }
                    },
                color = color,
                trackColor = color.copy(alpha = 0.25f),
            )
            Text(
                "${formatMillis(audio.position)} / ${formatMillis(audio.duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatMillis(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
