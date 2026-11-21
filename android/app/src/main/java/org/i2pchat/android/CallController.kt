package org.i2pchat.android

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class CallPhase {
    Idle, Outgoing, Incoming, Active, Ending
}

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String = "",
    val peer: String = "",
    val wantVideo: Boolean = false,
    val mic: Boolean = true,
    val cam: Boolean = false,
    val screen: Boolean = false,
    val spk: Boolean = true,
    val remoteMic: Boolean = true,
    val remoteCam: Boolean = false,
    val remoteScreen: Boolean = false,
    val minimized: Boolean = false,
    val error: String = "",
    val audioBitrateKbps: Int = 32,
    val videoBitrateKbps: Int = 250,
    val videoFps: Int = 8,
    val videoMaxEdge: Int = 480,
    val lastRemoteJpeg: ByteArray? = null,
)

/**
 * 1:1 call orchestration: STREAM signaling via [sendSignal], media via SAM DATAGRAM.
 */
class CallController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val samHost: () -> String,
    private val samPort: () -> Int,
    private val sendSignal: (peer: String, signal: String) -> Boolean,
) {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var datagram: SamDatagramSession? = null
    private var remoteDestB64: String = ""
    private var seq = AtomicInteger(0)
    private var audioJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private val prefs by lazy {
        appContext.getSharedPreferences("call_prefs", Context.MODE_PRIVATE)
    }

    fun loadPrefsIntoState() {
        _state.update {
            it.copy(
                audioBitrateKbps = prefs.getInt("audio_bitrate_kbps", 32).coerceIn(12, 64),
                videoBitrateKbps = prefs.getInt("video_bitrate_kbps", 250).coerceIn(50, 800),
                videoFps = prefs.getInt("video_fps", 8).coerceIn(3, 20),
                videoMaxEdge = prefs.getInt("video_max_edge", 480).coerceIn(160, 720),
            )
        }
    }

    fun savePrefs(audio: Int, video: Int, fps: Int, edge: Int) {
        prefs.edit()
            .putInt("audio_bitrate_kbps", audio.coerceIn(12, 64))
            .putInt("video_bitrate_kbps", video.coerceIn(50, 800))
            .putInt("video_fps", fps.coerceIn(3, 20))
            .putInt("video_max_edge", edge.coerceIn(160, 720))
            .apply()
        loadPrefsIntoState()
    }

    fun startOutgoing(peer: String, video: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (_state.value.phase != CallPhase.Idle) return@launch
            loadPrefsIntoState()
            val callId = CallSignals.newCallId()
            val dg = runCatching {
                SamDatagramSession.open(
                    sessionId = "call-${UUID.randomUUID().toString().take(10)}",
                    samHost = samHost(),
                    samPort = samPort(),
                    onDatagram = ::onDatagram,
                )
            }.getOrElse {
                _state.update { s -> s.copy(error = it.message.orEmpty()) }
                return@launch
            }
            datagram = dg
            val maxBr = (_state.value.audioBitrateKbps + _state.value.videoBitrateKbps) * 1000
            val token = dg.localDestinationB64.ifBlank { dg.localB32Hint }
            _state.update {
                it.copy(
                    phase = CallPhase.Outgoing,
                    callId = callId,
                    peer = peer,
                    wantVideo = video,
                    cam = video,
                    mic = true,
                    spk = true,
                    error = "",
                )
            }
            val ok = sendSignal(
                peer,
                CallSignals.buildInvite(callId, video, "opus,jpeg", maxBr, token),
            )
            if (!ok) {
                teardown("signal failed")
            }
        }
    }

    fun acceptIncoming() {
        scope.launch(Dispatchers.IO) {
            val snap = _state.value
            if (snap.phase != CallPhase.Incoming) return@launch
            loadPrefsIntoState()
            val dg = runCatching {
                SamDatagramSession.open(
                    sessionId = "call-${UUID.randomUUID().toString().take(10)}",
                    samHost = samHost(),
                    samPort = samPort(),
                    onDatagram = ::onDatagram,
                )
            }.getOrElse {
                teardown(it.message.orEmpty())
                return@launch
            }
            datagram = dg
            val maxBr = (_state.value.audioBitrateKbps + _state.value.videoBitrateKbps) * 1000
            val token = dg.localDestinationB64.ifBlank { dg.localB32Hint }
            remoteDestB64 = snap.let { /* set from invite */ remoteDestB64 }
            val ok = sendSignal(
                snap.peer,
                CallSignals.buildAccept(snap.callId, "opus,jpeg", maxBr, token),
            )
            if (!ok) {
                teardown("accept failed")
                return@launch
            }
            _state.update {
                it.copy(phase = CallPhase.Active, cam = it.wantVideo)
            }
            sendMediaState()
            startAudioLoop()
        }
    }

    fun rejectIncoming(reason: String = "declined") {
        scope.launch(Dispatchers.IO) {
            val snap = _state.value
            if (snap.phase == CallPhase.Incoming) {
                sendSignal(snap.peer, CallSignals.buildReject(snap.callId, reason))
            }
            teardown("rejected")
        }
    }

    fun hangup() {
        scope.launch(Dispatchers.IO) {
            val snap = _state.value
            if (snap.phase != CallPhase.Idle && snap.peer.isNotBlank()) {
                sendSignal(snap.peer, CallSignals.buildHangup(snap.callId))
            }
            teardown("hangup")
        }
    }

    fun setMinimized(minimized: Boolean) {
        _state.update { it.copy(minimized = minimized) }
    }

    fun toggleMic() = toggle { it.copy(mic = !it.mic) }
    fun toggleSpk() = toggle { it.copy(spk = !it.spk) }
    fun toggleCam() = toggle {
        val on = !it.cam
        it.copy(cam = on, screen = if (on) false else it.screen)
    }
    fun toggleScreen() = toggle {
        val on = !it.screen
        it.copy(screen = on, cam = if (on) false else it.cam)
    }

    private fun toggle(transform: (CallUiState) -> CallUiState) {
        scope.launch(Dispatchers.IO) {
            _state.update(transform)
            if (_state.value.phase == CallPhase.Active) sendMediaState()
        }
    }

    fun onCallSignal(peer: String, payload: String) {
        val parsed = CallSignals.parse(payload) ?: return
        scope.launch(Dispatchers.IO) {
            when (parsed.kind) {
                CallSignals.INVITE -> {
                    if (_state.value.phase != CallPhase.Idle) {
                        sendSignal(peer, CallSignals.buildReject(parsed.callId, "busy"))
                        return@launch
                    }
                    remoteDestB64 = parsed.datagramB32
                    _state.update {
                        CallUiState(
                            phase = CallPhase.Incoming,
                            callId = parsed.callId,
                            peer = peer,
                            wantVideo = parsed.wantVideo,
                        )
                    }
                }
                CallSignals.ACCEPT -> {
                    val snap = _state.value
                    if (snap.phase != CallPhase.Outgoing || snap.callId != parsed.callId) return@launch
                    remoteDestB64 = parsed.datagramB32
                    _state.update { it.copy(phase = CallPhase.Active) }
                    sendMediaState()
                    startAudioLoop()
                }
                CallSignals.REJECT, CallSignals.HANGUP -> {
                    if (parsed.callId == _state.value.callId) teardown(parsed.reason.ifBlank { "ended" })
                }
                CallSignals.MEDIA -> {
                    if (parsed.callId != _state.value.callId) return@launch
                    _state.update {
                        it.copy(
                            remoteMic = parsed.mic,
                            remoteCam = parsed.cam,
                            remoteScreen = parsed.screen,
                        )
                    }
                }
            }
        }
    }

    fun sendJpeg(jpeg: ByteArray) {
        val snap = _state.value
        if (snap.phase != CallPhase.Active || !(snap.cam || snap.screen)) return
        if (remoteDestB64.length < 40) return
        val dg = datagram ?: return
        val hash = CallSignals.callIdHash16(snap.callId)
        val now = (System.currentTimeMillis() and 0x7fffffff).toInt()
        for ((chunk, flags) in CallMediaFraming.fragment(jpeg)) {
            val pkt = CallMediaFraming.Packet(
                callHash = hash,
                seq = seq.incrementAndGet(),
                tsMs = now,
                type = CallMediaFraming.TYPE_VIDEO_JPEG,
                flags = flags,
                payload = chunk,
            )
            runCatching { dg.send(remoteDestB64, pkt.encode()) }
        }
    }

    private fun sendMediaState() {
        val s = _state.value
        if (s.peer.isBlank() || s.callId.isBlank()) return
        sendSignal(s.peer, CallSignals.buildMedia(s.callId, s.mic, s.cam, s.screen, s.spk))
    }

    private fun onDatagram(from: String, payload: ByteArray) {
        val pkt = CallMediaFraming.decode(payload) ?: return
        val snap = _state.value
        if (snap.callId.isNotBlank() &&
            !pkt.callHash.contentEquals(CallSignals.callIdHash16(snap.callId))
        ) {
            return
        }
        when (pkt.type) {
            CallMediaFraming.TYPE_PCM16, CallMediaFraming.TYPE_OPUS -> {
                if (!snap.spk) return
                // Opus decode not bundled; treat Opus as opaque skip, PCM16 play.
                if (pkt.type == CallMediaFraming.TYPE_PCM16) {
                    runCatching { audioTrack?.write(pkt.payload, 0, pkt.payload.size) }
                }
            }
            CallMediaFraming.TYPE_VIDEO_JPEG -> {
                _state.update { it.copy(lastRemoteJpeg = pkt.payload) }
            }
        }
    }

    private fun startAudioLoop() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val frame = sampleRate * 20 / 1000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, frame * 4),
                )
            }.getOrNull() ?: return@launch
            val outMin = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(outMin, frame * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            runCatching { recorder.startRecording() }
            val buf = ShortArray(frame)
            try {
                while (isActive && _state.value.phase == CallPhase.Active) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val snap = _state.value
                    if (!snap.mic || remoteDestB64.length < 40) continue
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                    }
                    val dg = datagram ?: continue
                    val pkt = CallMediaFraming.Packet(
                        callHash = CallSignals.callIdHash16(snap.callId),
                        seq = seq.incrementAndGet(),
                        tsMs = (System.currentTimeMillis() and 0x7fffffff).toInt(),
                        type = CallMediaFraming.TYPE_PCM16,
                        flags = 0,
                        payload = bytes,
                    )
                    runCatching { dg.send(remoteDestB64, pkt.encode()) }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                runCatching { audioTrack?.stop() }
                runCatching { audioTrack?.release() }
                audioTrack = null
            }
        }
    }

    private fun teardown(reason: String) {
        audioJob?.cancel()
        audioJob = null
        runCatching { datagram?.close() }
        datagram = null
        remoteDestB64 = ""
        _state.value = CallUiState(error = reason.takeIf { it != "hangup" && it != "ended" }.orEmpty())
        Log.i(TAG, "call ended: $reason")
    }

    companion object {
        private const val TAG = "CallController"
    }
}
