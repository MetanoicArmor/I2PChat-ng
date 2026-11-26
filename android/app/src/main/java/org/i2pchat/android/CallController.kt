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
    val preset: String = "normal",
    val codecs: String = CallSignals.DEFAULT_CODECS,
    val lastRemoteJpeg: ByteArray? = null,
    val lastRemoteVp8: ByteArray? = null,
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
    @Volatile private var remoteDestB64: String = ""
    private val reassembler = CallMediaFraming.Reassembler()
    private var resolveJob: Job? = null
    @Volatile private var resolveFailures = 0
    @Volatile private var nextResolveAtMs = 0L
    private var seq = AtomicInteger(0)
    private var audioJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var setupJob: Job? = null
    private var generation = 0
    private val mutex = Mutex()
    private val remoteCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val prefs by lazy {
        appContext.getSharedPreferences("call_prefs", Context.MODE_PRIVATE)
    }

    fun loadPrefsIntoState() {
        val preset = prefs.getString("preset", "normal") ?: "normal"
        val defaults = presetValues(preset)
        _state.update {
            it.copy(
                preset = preset,
                audioBitrateKbps = prefs.getInt("audio_bitrate_kbps", defaults.first).coerceIn(12, 64),
                videoBitrateKbps = prefs.getInt("video_bitrate_kbps", defaults.second).coerceIn(50, 800),
                videoFps = prefs.getInt("video_fps", defaults.third).coerceIn(3, 20),
                videoMaxEdge = prefs.getInt("video_max_edge", defaults.fourth).coerceIn(160, 720),
            )
        }
    }

    fun applyPreset(preset: String) {
        val (a, v, f, e) = presetValues(preset)
        savePrefs(a, v, f, e, preset)
    }

    fun savePrefs(audio: Int, video: Int, fps: Int, edge: Int, preset: String = _state.value.preset) {
        prefs.edit()
            .putString("preset", preset)
            .putInt("audio_bitrate_kbps", audio.coerceIn(12, 64))
            .putInt("video_bitrate_kbps", video.coerceIn(50, 800))
            .putInt("video_fps", fps.coerceIn(3, 20))
            .putInt("video_max_edge", edge.coerceIn(160, 720))
            .apply()
        loadPrefsIntoState()
    }

    private fun presetValues(preset: String): Quadruple {
        return when (preset) {
            "economy" -> Quadruple(24, 100, 5, 240)
            "max" -> Quadruple(48, 600, 15, 720)
            else -> Quadruple(32, 250, 8, 480)
        }
    }

    private data class Quadruple(val first: Int, val second: Int, val third: Int, val fourth: Int)

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
                // Full public destination: no NAMING LOOKUP needed on the peer (fresh LeaseSet is often unpublished).
                val token = dg.localDestinationB64.ifBlank { dg.localB32Hint }
                val negotiated = CallSignals.intersectCodecs(
                    CallSignals.localOfferCodecs(),
                    _state.value.codecs,
                )
                val ok = sendSignal(
                    peer,
                    CallSignals.buildAccept(callId, negotiated, maxBr, token),
                )
                if (!ok) {
                    teardown("accept failed")
                    return@launch
                }
                val ready = applyRemoteToken(remoteDestB64)
                _state.update {
                    it.copy(
                        phase = CallPhase.Active,
                        cam = it.wantVideo,
                        codecs = negotiated,
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
                                codecs = parsed.codecs,
                                audioBitrateKbps = it.audioBitrateKbps,
                                videoBitrateKbps = it.videoBitrateKbps,
                                videoFps = it.videoFps,
                                videoMaxEdge = it.videoMaxEdge,
                                preset = it.preset,
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
                    val ready = applyRemoteToken(parsed.datagramB32)
                    val negotiated = CallSignals.intersectCodecs(
                        CallSignals.localOfferCodecs(),
                        parsed.codecs,
                    )
                    _state.update {
                        it.copy(
                            phase = CallPhase.Active,
                            codecs = negotiated,
                            mediaReady = ready,
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
        val target = remoteSendTarget()
        if (target.isEmpty()) return
        val dg = datagram ?: return
        sendFramed(dg, target, snap.callId, CallMediaFraming.TYPE_VIDEO_JPEG, jpeg)
    }

    fun sendVp8(payload: ByteArray, keyframe: Boolean) {
        val snap = _state.value
        if (snap.phase != CallPhase.Active || !(snap.cam || snap.screen)) return
        if (!CallSignals.codecsInclude(snap.codecs, "vp8")) return
        val target = remoteSendTarget()
        if (target.isEmpty()) return
        val dg = datagram ?: return
        val type = if (keyframe) CallMediaFraming.TYPE_VIDEO_KEY else CallMediaFraming.TYPE_VIDEO_DELTA
        sendFramed(dg, target, snap.callId, type, payload)
    }

    fun usesVp8(): Boolean =
        CallSignals.codecsInclude(_state.value.codecs, "vp8") && CallVp8Encoder.isSupported()

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
            // Full public destination: no NAMING LOOKUP needed on the peer (fresh LeaseSet is often unpublished).
                val token = dg.localDestinationB64.ifBlank { dg.localB32Hint }
            val ok = sendSignal(
                peer,
                CallSignals.buildInvite(callId, video, CallSignals.localOfferCodecs(), maxBr, token),
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

    private fun isFullDest(t: String): Boolean = t.length >= REMOTE_DEST_MIN && !t.contains('.')

    private fun b32HostName(t: String): String = when {
        t.endsWith(".i2p") -> t
        '.' !in t -> "$t.b32.i2p"
        else -> "$t.b32.i2p"
    }

    /** Remember the peer's address; never blocks on a lookup. Returns true when it is a full destination. */
    private fun applyRemoteToken(token: String): Boolean {
        val t = token.trim()
        remoteDestB64 = t
        resolveFailures = 0
        nextResolveAtMs = 0L
        if (t.isEmpty()) return false
        if (isFullDest(t)) return true
        remoteCache[t]?.let {
            remoteDestB64 = it
            return true
        }
        scheduleResolve(t)
        return false
    }

    /** Destination for DATAGRAM SEND: full base64 when known, else `<b32>.b32.i2p` (i2pd 3.x resolves it). */
    private fun remoteSendTarget(): String {
        val r = remoteDestB64
        if (r.isEmpty()) return ""
        if (isFullDest(r)) return r
        remoteCache[r]?.let {
            remoteDestB64 = it
            return it
        }
        scheduleResolve(r)
        return b32HostName(r)
    }

    /** Single-flight NAMING LOOKUP with exponential backoff (never per media frame). */
    private fun scheduleResolve(token: String) {
        if (resolveJob?.isActive == true) return
        if (System.currentTimeMillis() < nextResolveAtMs) return
        val gen = generation
        resolveJob = scope.launch(Dispatchers.IO) {
            val name = b32HostName(token)
            runCatching {
                SamDatagramSession.namingLookup(samHost(), samPort(), name)
            }.onSuccess { b64 ->
                if (generation == gen && remoteDestB64 == token) {
                    remoteCache[token] = b64
                    remoteDestB64 = b64
                    _state.update { it.copy(mediaReady = isFullDest(b64)) }
                }
            }.onFailure {
                resolveFailures += 1
                val delayMs = minOf(30_000L, 1_000L shl minOf(resolveFailures, 5))
                nextResolveAtMs = System.currentTimeMillis() + delayMs
                Log.i(TAG, "naming lookup failed for $name (retry in ${delayMs}ms): ${it.message}")
            }
        }
    }

    /** One media frame -> N datagrams sharing a seq (see CallMediaFraming). */
    private fun sendFramed(dg: SamDatagramSession, target: String, callId: String, type: Int, payload: ByteArray) {
        val hash = CallSignals.callIdHash16(callId)
        val now = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val frameSeq = seq.incrementAndGet()
        val chunks = CallMediaFraming.fragment(payload)
        for ((idx, chunk) in chunks.withIndex()) {
            val pkt = CallMediaFraming.Packet(
                callHash = hash,
                seq = frameSeq,
                tsMs = now,
                type = type,
                flags = 0,
                payload = chunk,
                fragIdx = idx,
                fragTotal = chunks.size,
            )
            if (runCatching { dg.send(target, pkt.encode()) }.isFailure) return
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
        val body = reassembler.feed(pkt) ?: return
        when (pkt.type) {
            CallMediaFraming.TYPE_PCM16, CallMediaFraming.TYPE_OPUS -> {
                if (!snap.spk) return
                if (pkt.type == CallMediaFraming.TYPE_PCM16) {
                    runCatching { audioTrack?.write(body, 0, body.size) }
                }
            }
            CallMediaFraming.TYPE_VIDEO_JPEG -> {
                _state.update { it.copy(lastRemoteJpeg = body) }
            }
            CallMediaFraming.TYPE_VIDEO_KEY, CallMediaFraming.TYPE_VIDEO_DELTA -> {
                // Keep raw VP8 for optional decode path; JPEG peers still use lastRemoteJpeg.
                _state.update { it.copy(lastRemoteVp8 = body) }
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
            val pcmAcc = java.io.ByteArrayOutputStream()
            try {
                while (isActive && _state.value.phase == CallPhase.Active) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val snap = _state.value
                    if (!snap.mic) continue
                    val target = remoteSendTarget()
                    if (target.isEmpty()) {
                        pcmAcc.reset()
                        continue
                    }
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        pcmAcc.write(v and 0xff)
                        pcmAcc.write((v shr 8) and 0xff)
                    }
                    // 3 x 20 ms per datagram: fewer packets, less per-datagram I2P overhead.
                    if (pcmAcc.size() < frame * 2 * AUDIO_FRAMES_PER_PACKET) continue
                    val bytes = pcmAcc.toByteArray()
                    pcmAcc.reset()
                    val dg = datagram ?: continue
                    sendFramed(dg, target, snap.callId, CallMediaFraming.TYPE_PCM16, bytes)
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
        resolveJob?.cancel()
        resolveJob = null
        remoteCache.clear()
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
        private const val AUDIO_FRAMES_PER_PACKET = 3
    }
}
