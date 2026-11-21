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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
// media / signaling helpers live in CallSignals + CallMediaFraming

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
    val mediaReady: Boolean = false,
)

/**
 * 1:1 call orchestration: STREAM signaling via [sendSignal], media via SAM DATAGRAM.
 *
 * DATAGRAM SESSION CREATE is cancelable; hangup/reject tear down locally immediately.
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
    private var setupJob: Job? = null
    private var generation = 0
    private val mutex = Mutex()
    private val remoteCache = HashMap<String, String>()
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
            mutex.withLock {
                if (_state.value.phase != CallPhase.Idle) return@launch
                loadPrefsIntoState()
                val callId = CallSignals.newCallId()
                generation += 1
                val gen = generation
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
                        mediaReady = false,
                    )
                }
                setupJob?.cancel()
                setupJob = scope.launch(Dispatchers.IO) {
                    outgoingSetup(gen, peer, callId, video)
                }
            }
        }
    }

    fun acceptIncoming() {
        scope.launch(Dispatchers.IO) {
            val snap = _state.value
            if (snap.phase != CallPhase.Incoming) return@launch
            loadPrefsIntoState()
            val gen = generation
            val peer = snap.peer
            val callId = snap.callId
            try {
                val dg = awaitDatagramReady(gen)
                    ?: throw IllegalStateException("DATAGRAM setup failed")
                if (generation != gen || _state.value.phase != CallPhase.Incoming) {
                    return@launch
                }
                val maxBr = (_state.value.audioBitrateKbps + _state.value.videoBitrateKbps) * 1000
                val token = dg.localB32Hint.ifBlank { dg.localDestinationB64 }
                val ok = sendSignal(
                    peer,
                    CallSignals.buildAccept(callId, "opus,jpeg", maxBr, token),
                )
                if (!ok) {
                    teardown("accept failed")
                    return@launch
                }
                resolveRemoteLocked(remoteDestB64.ifBlank { snap.let { remoteDestB64 } })
                val ready = remoteDestB64.length >= REMOTE_DEST_MIN
                _state.update {
                    it.copy(
                        phase = CallPhase.Active,
                        cam = it.wantVideo,
                        mediaReady = ready,
                    )
                }
                sendMediaState()
                startAudioLoop()
            } catch (t: Throwable) {
                if (generation == gen) {
                    Log.w(TAG, "accept failed", t)
                    teardown(t.message.orEmpty().ifBlank { "accept failed" })
                }
            }
        }
    }

    fun rejectIncoming(reason: String = "declined") {
        val snap = _state.value
        val peer = snap.peer
        val callId = snap.callId
        beginLocalTeardown("rejected")
        if (snap.phase == CallPhase.Incoming && peer.isNotBlank() && callId.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { sendSignal(peer, CallSignals.buildReject(callId, reason)) }
            }
        }
    }

    fun hangup() {
        val snap = _state.value
        val peer = snap.peer
        val callId = snap.callId
        if (snap.phase == CallPhase.Idle) return
        beginLocalTeardown("hangup")
        if (peer.isNotBlank() && callId.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { sendSignal(peer, CallSignals.buildHangup(callId)) }
            }
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
                    mutex.withLock {
                        if (_state.value.phase != CallPhase.Idle) {
                            sendSignal(peer, CallSignals.buildReject(parsed.callId, "busy"))
                            return@launch
                        }
                        generation += 1
                        val gen = generation
                        remoteDestB64 = parsed.datagramB32
                        _state.update {
                            CallUiState(
                                phase = CallPhase.Incoming,
                                callId = parsed.callId,
                                peer = peer,
                                wantVideo = parsed.wantVideo,
                                audioBitrateKbps = it.audioBitrateKbps,
                                videoBitrateKbps = it.videoBitrateKbps,
                                videoFps = it.videoFps,
                                videoMaxEdge = it.videoMaxEdge,
                            )
                        }
                        setupJob?.cancel()
                        setupJob = scope.launch(Dispatchers.IO) {
                            prefetchDatagram(gen)
                        }
                    }
                }
                CallSignals.ACCEPT -> {
                    val snap = _state.value
                    if (snap.phase != CallPhase.Outgoing || snap.callId != parsed.callId) return@launch
                    remoteDestB64 = parsed.datagramB32
                    resolveRemoteLocked(parsed.datagramB32)
                    _state.update {
                        it.copy(
                            phase = CallPhase.Active,
                            mediaReady = remoteDestB64.length >= REMOTE_DEST_MIN,
                        )
                    }
                    sendMediaState()
                    startAudioLoop()
                }
                CallSignals.REJECT, CallSignals.HANGUP -> {
                    if (parsed.callId == _state.value.callId) {
                        beginLocalTeardown(parsed.reason.ifBlank { "ended" })
                    }
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
        ensureRemoteResolved()
        if (remoteDestB64.length < REMOTE_DEST_MIN) return
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

    private suspend fun outgoingSetup(gen: Int, peer: String, callId: String, video: Boolean) {
        var dg: SamDatagramSession? = null
        try {
            dg = SamDatagramSession.open(
                sessionId = "call-${UUID.randomUUID().toString().take(10)}",
                samHost = samHost(),
                samPort = samPort(),
                onDatagram = ::onDatagram,
            )
            if (generation != gen) {
                dg.close()
                return
            }
            datagram = dg
            val maxBr = (_state.value.audioBitrateKbps + _state.value.videoBitrateKbps) * 1000
            val token = dg.localB32Hint.ifBlank { dg.localDestinationB64 }
            val ok = sendSignal(
                peer,
                CallSignals.buildInvite(callId, video, "opus,jpeg", maxBr, token),
            )
            if (!ok) {
                if (generation == gen) teardown("signal failed")
            }
        } catch (t: Throwable) {
            dg?.close()
            if (generation == gen && _state.value.phase == CallPhase.Outgoing) {
                Log.w(TAG, "outgoing setup failed", t)
                teardown(t.message.orEmpty().ifBlank { "setup failed" })
            }
        }
    }

    private suspend fun prefetchDatagram(gen: Int) {
        var dg: SamDatagramSession? = null
        try {
            dg = SamDatagramSession.open(
                sessionId = "call-${UUID.randomUUID().toString().take(10)}",
                samHost = samHost(),
                samPort = samPort(),
                onDatagram = ::onDatagram,
            )
            if (generation != gen || _state.value.phase != CallPhase.Incoming) {
                dg.close()
                return
            }
            datagram = dg
        } catch (t: Throwable) {
            dg?.close()
            Log.w(TAG, "incoming prefetch failed", t)
        }
    }

    private suspend fun awaitDatagramReady(gen: Int): SamDatagramSession? {
        datagram?.let { return it }
        val job = setupJob
        if (job != null) {
            withTimeout(30_000L) { job.join() }
        }
        if (generation != gen) return null
        datagram?.let { return it }
        // Prefetch missed — open now.
        val dg = SamDatagramSession.open(
            sessionId = "call-${UUID.randomUUID().toString().take(10)}",
            samHost = samHost(),
            samPort = samPort(),
            onDatagram = ::onDatagram,
        )
        if (generation != gen) {
            dg.close()
            return null
        }
        datagram = dg
        return dg
    }

    private fun resolveRemoteLocked(token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        if (t.length >= REMOTE_DEST_MIN && !t.contains('.')) {
            remoteDestB64 = t
            return
        }
        remoteCache[t]?.let {
            remoteDestB64 = it
            return
        }
        val name = when {
            t.endsWith(".b32.i2p") -> t
            t.endsWith(".i2p") -> t
            '.' !in t -> "$t.b32.i2p"
            else -> t
        }
        runCatching {
            SamDatagramSession.namingLookup(samHost(), samPort(), name)
        }.onSuccess { b64 ->
            remoteCache[t] = b64
            remoteDestB64 = b64
        }.onFailure {
            Log.w(TAG, "naming lookup failed for $name", it)
            remoteDestB64 = t
        }
    }

    private fun ensureRemoteResolved() {
        if (remoteDestB64.length >= REMOTE_DEST_MIN) return
        resolveRemoteLocked(remoteDestB64)
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
                    if (!snap.mic) continue
                    ensureRemoteResolved()
                    if (remoteDestB64.length < REMOTE_DEST_MIN) continue
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

    private fun beginLocalTeardown(reason: String) {
        generation += 1
        setupJob?.cancel()
        setupJob = null
        teardown(reason)
    }

    private fun teardown(reason: String) {
        audioJob?.cancel()
        audioJob = null
        val dg = datagram
        datagram = null
        remoteDestB64 = ""
        _state.value = CallUiState(
            error = reason.takeIf { it != "hangup" && it != "ended" && it != "remote_hangup" }.orEmpty(),
            audioBitrateKbps = _state.value.audioBitrateKbps,
            videoBitrateKbps = _state.value.videoBitrateKbps,
            videoFps = _state.value.videoFps,
            videoMaxEdge = _state.value.videoMaxEdge,
        )
        scope.launch(Dispatchers.IO) { runCatching { dg?.close() } }
        Log.i(TAG, "call ended: $reason")
    }

    companion object {
        private const val TAG = "CallController"
        private const val REMOTE_DEST_MIN = 80
    }
}
