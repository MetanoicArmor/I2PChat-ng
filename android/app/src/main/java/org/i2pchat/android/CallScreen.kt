package org.i2pchat.android

import android.Manifest
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CallOverlayHost(bridge: ChatBridge) {
    val call by bridge.callController.state.collectAsStateWithLifecycle()
    if (call.phase == CallPhase.Idle) return
    if (call.minimized) {
        CallMiniPanel(bridge, call)
    } else {
        CallFullScreen(bridge, call)
    }
}

@Composable
fun CallFullScreen(bridge: ChatBridge, call: CallUiState) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted.values.all { it }
        if (!ok) {
            Toast.makeText(context, context.getString(R.string.call_permission_denied), Toast.LENGTH_LONG).show()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF010141C))
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.call_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when (call.phase) {
                            CallPhase.Outgoing -> stringResource(R.string.call_calling)
                            CallPhase.Incoming -> stringResource(R.string.call_incoming_from, call.peer.take(24))
                            CallPhase.Active -> stringResource(R.string.call_connected)
                            else -> call.peer.take(24)
                        },
                        color = Color(0xFFB0B8C4),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.call_settings), tint = Color.White)
                }
                IconButton(onClick = { bridge.callController.setMinimized(true) }) {
                    Icon(Icons.Default.CloseFullscreen, contentDescription = stringResource(R.string.call_minimize), tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoPane(
                    label = stringResource(R.string.call_remote_video),
                    jpeg = call.lastRemoteJpeg,
                    modifier = Modifier.weight(1f),
                )
                VideoPane(
                    label = stringResource(R.string.call_local_video),
                    jpeg = null,
                    modifier = Modifier.weight(1f),
                    placeholder = if (call.cam || call.screen) {
                        stringResource(R.string.call_cam_on)
                    } else {
                        stringResource(R.string.call_cam_off)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            if (call.phase == CallPhase.Incoming) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                            bridge.acceptCall()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.call_accept)) }
                    OutlinedButton(onClick = { bridge.rejectCall() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.call_reject))
                    }
                }
            } else {
                CallControlsRow(bridge, call, compact = false)
            }
        }
    }
    if (showSettings) {
        CallSettingsDialog(
            call = call,
            onDismiss = { showSettings = false },
            onSave = { a, v, f, e ->
                bridge.callController.savePrefs(a, v, f, e)
                showSettings = false
            },
        )
    }
}

@Composable
fun CallMiniPanel(bridge: ChatBridge, call: CallUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Surface(
            modifier = Modifier
                .padding(12.dp)
                .widthIn(max = 360.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xEE1C1F26),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    call.peer.take(28).ifBlank { stringResource(R.string.call_in_call) },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(6.dp))
                CallControlsRow(bridge, call, compact = true)
            }
        }
    }
}

@Composable
private fun CallControlsRow(bridge: ChatBridge, call: CallUiState, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { bridge.callController.toggleMic() }) {
            Icon(
                if (call.mic) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = stringResource(R.string.call_mic),
                tint = Color.White,
            )
        }
        IconButton(onClick = { bridge.callController.toggleSpk() }) {
            Icon(
                if (call.spk) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = stringResource(R.string.call_spk),
                tint = Color.White,
            )
        }
        IconButton(onClick = { bridge.callController.toggleCam() }) {
            Icon(Icons.Default.Cameraswitch, contentDescription = stringResource(R.string.call_cam), tint = Color.White)
        }
        IconButton(onClick = { bridge.callController.toggleScreen() }) {
            Icon(Icons.Default.ScreenShare, contentDescription = stringResource(R.string.call_screen), tint = Color.White)
        }
        if (compact) {
            IconButton(onClick = { bridge.callController.setMinimized(false) }) {
                Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.call_expand), tint = Color.White)
            }
        }
        IconButton(onClick = { bridge.hangupCall() }) {
            Icon(Icons.Default.CallEnd, contentDescription = stringResource(R.string.call_hangup), tint = Color(0xFFFF6B6B))
        }
    }
}

@Composable
private fun VideoPane(
    label: String,
    jpeg: ByteArray?,
    modifier: Modifier = Modifier,
    placeholder: String = label,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1A2030),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bmp = remember(jpeg) {
                jpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(placeholder, color = Color(0xFF8A93A3))
            }
        }
    }
}

@Composable
private fun CallSettingsDialog(
    call: CallUiState,
    onDismiss: () -> Unit,
    onSave: (audio: Int, video: Int, fps: Int, edge: Int) -> Unit,
) {
    var audio by remember { mutableIntStateOf(call.audioBitrateKbps) }
    var video by remember { mutableIntStateOf(call.videoBitrateKbps) }
    var fps by remember { mutableIntStateOf(call.videoFps) }
    var edge by remember { mutableIntStateOf(call.videoMaxEdge) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_settings)) },
        text = {
            Column {
                Text("${stringResource(R.string.call_audio_bitrate)}: $audio kbps")
                Row {
                    TextButton(onClick = { audio = (audio - 4).coerceAtLeast(12) }) { Text("-") }
                    TextButton(onClick = { audio = (audio + 4).coerceAtMost(64) }) { Text("+") }
                }
                Text("${stringResource(R.string.call_video_bitrate)}: $video kbps")
                Row {
                    TextButton(onClick = { video = (video - 25).coerceAtLeast(50) }) { Text("-") }
                    TextButton(onClick = { video = (video + 25).coerceAtMost(800) }) { Text("+") }
                }
                Text("${stringResource(R.string.call_video_fps)}: $fps")
                Row {
                    TextButton(onClick = { fps = (fps - 1).coerceAtLeast(3) }) { Text("-") }
                    TextButton(onClick = { fps = (fps + 1).coerceAtMost(20) }) { Text("+") }
                }
                Text("${stringResource(R.string.call_video_edge)}: $edge")
                Row {
                    TextButton(onClick = { edge = (edge - 40).coerceAtLeast(160) }) { Text("-") }
                    TextButton(onClick = { edge = (edge + 40).coerceAtMost(720) }) { Text("+") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(audio, video, fps, edge) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Top-bar call action buttons for direct chats. */
@Composable
fun ConversationCallActions(
    bridge: ChatBridge,
    peerId: String,
    peerSecure: Boolean,
) {
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            bridge.startAudioCall(peerId)
        } else {
            Toast.makeText(context, context.getString(R.string.call_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }
    val videoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            bridge.startVideoCall(peerId)
        } else {
            Toast.makeText(context, context.getString(R.string.call_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }
    IconButton(
        onClick = {
            if (!peerSecure) {
                Toast.makeText(context, context.getString(R.string.call_need_peer), Toast.LENGTH_SHORT).show()
                return@IconButton
            }
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        },
    ) {
        Icon(Icons.Default.Call, contentDescription = stringResource(R.string.call_audio))
    }
    IconButton(
        onClick = {
            if (!peerSecure) {
                Toast.makeText(context, context.getString(R.string.call_need_peer), Toast.LENGTH_SHORT).show()
                return@IconButton
            }
            videoPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        },
    ) {
        Icon(Icons.Default.Cameraswitch, contentDescription = stringResource(R.string.call_video))
    }
}
