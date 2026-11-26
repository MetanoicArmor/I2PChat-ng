package org.i2pchat.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    val audioBitrateKbps: Int = 48,
    val videoBitrateKbps: Int = 600,
    val videoFps: Int = 15,
    val videoMaxEdge: Int = 720,
    val preset: String = "max",
    val codecs: String = CallSignals.DEFAULT_CODECS,
    /** Latest decoded picture from the peer (JPEG or VP8). */
    val remoteFrame: Bitmap? = null,
    /** Small preview of what we are sending (camera or screen). */
    val localFrame: Bitmap? = null,
    val mediaReady: Boolean = false,
    /** Live quality readout: effective edge/fps/kbps and packet counters. */
    val liveEdge: Int = 0,
    val liveFps: Int = 0,
    val liveKbps: Int = 0,
    val txPackets: Long = 0,
    val rxPackets: Long = 0,
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
    private val tunnelLength: () -> Int = { 4 },
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
    private val seq = AtomicInteger(0)
    private val audioSeq = AtomicInteger(0)
    private var audioJob: Job? = null
    private var setupJob: Job? = null

    // --- media pipeline (per call) ---
    private val playout = PlayoutBuffer()
    private val seqTracker = AudioSeqTracker()
    private var opusEnc: OpusFrameEncoder? = null
    private var opusDec: OpusFrameDecoder? = null
    @Volatile private var lastRxFrame: ShortArray = ShortArray(0)
    @Volatile private var playbackRunning = false
    private var playbackThread: Thread? = null
    private var audioRx: ExecutorService? = null
    private var videoRx: ExecutorService? = null
    private val audioRxPending = AtomicInteger(0)
    private val videoRxPending = AtomicInteger(0)
    private var vp8Dec: Vp8FrameDecoder? = null
    @Volatile private var gotRemoteKey = false
    @Volatile private var lastKeyReqMs = 0L
    private var lastDroppedSeen = 0
    private var videoSender: CallVideoSender? = null
    private var camera: CallCamera? = null
    private var statsJob: Job? = null
    private var adapt: AdaptiveController? = null
    @Volatile private var eff: EffectiveMedia = EffectiveMedia(48, 600, 15, 720)
    private var pliHits = 0
    private val txPackets = AtomicLong(0)
    private val txDropped = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val rxDropped = AtomicLong(0)
    private var lastVideoBytes = 0L

    init {
        CallRuntime.controller = this
    }
    private var generation = 0
    private val mutex = Mutex()
    private val inviteTimes = mutableListOf<Long>()
    private val invitePeerAt = HashMap<String, Long>()
    private val remoteCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val prefs by lazy {
        appContext.getSharedPreferences("call_prefs", Context.MODE_PRIVATE)
    }

    fun loadPrefsIntoState() {
        val preset = prefs.getString("preset", "max") ?: "max"
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
        adapt?.reset(
            EffectiveMedia(
                _state.value.audioBitrateKbps, _state.value.videoBitrateKbps,
                _state.value.videoFps, _state.value.videoMaxEdge,
            ),
        )
    }

    private fun presetValues(preset: String): Quadruple {
        return when (preset) {
            "economy" -> Quadruple(24, 100, 5, 240)
            "normal" -> Quadruple(32, 250, 8, 480)
            else -> Quadruple(48, 600, 15, 720) // max (default)
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
                        // Camera stays off until the user turns it on.
                        cam = false,
                        codecs = negotiated,
                        mediaReady = ready,
                    )
                }
                sendMediaState()
                startMedia()
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
        it.copy(cam = on, screen = if (on) false else it.screen, error = "")
    }

    private fun toggle(transform: (CallUiState) -> CallUiState) {
        scope.launch(Dispatchers.IO) {
            val before = _state.value
            _state.update(transform)
            if (_state.value.phase == CallPhase.Active) {
                sendMediaState()
                if (before.cam != _state.value.cam) {
                    if (_state.value.cam && before.screen) {
                        CallForegroundService.stopProjection(appContext)
                    }
                    syncVideoSources()
                }
            }
        }
    }

    fun onCallSignal(peer: String, payload: String) {
        val parsed = CallSignals.parse(payload) ?: return
        if (!CallSignals.isCallId(parsed.callId)) return
        scope.launch(Dispatchers.IO) {
            when (parsed.kind) {
                CallSignals.INVITE -> {
                    if (!CallSignals.isDatagramToken(parsed.datagramB32)) return@launch
                    mutex.withLock {
                        if (_state.value.phase != CallPhase.Idle || !inviteAllowed(peer)) {
                            sendSignal(peer, CallSignals.buildReject(parsed.callId, "busy"))
                            return@launch
                        }
                        noteInvite(peer)
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
                    if (!CallSignals.samePeer(peer, snap.peer)) return@launch
                    if (snap.phase != CallPhase.Outgoing || snap.callId != parsed.callId) return@launch
                    if (!CallSignals.isDatagramToken(parsed.datagramB32)) return@launch
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
                    startMedia()
                }
                CallSignals.REJECT, CallSignals.HANGUP -> {
                    val snap = _state.value
                    if (!CallSignals.samePeer(peer, snap.peer)) return@launch
                    if (parsed.callId == snap.callId && snap.phase != CallPhase.Idle) {
                        beginLocalTeardown(parsed.reason.ifBlank { "ended" })
                    }
                }
                CallSignals.MEDIA -> {
                    val snap = _state.value
                    if (!CallSignals.samePeer(peer, snap.peer)) return@launch
                    if (parsed.callId != snap.callId || snap.phase == CallPhase.Idle) return@launch
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

    /** True when we can encode VP8 for the peer (negotiated and a working encoder exists). */
    fun usesVp8(): Boolean =
        CallSignals.codecsInclude(_state.value.codecs, "vp8") && Vp8FrameEncoder.isSupported()

    private suspend fun outgoingSetup(gen: Int, peer: String, callId: String, video: Boolean) {
        var dg: SamDatagramSession? = null
        try {
            dg = openDatagram()
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
            dg = openDatagram()
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
        val dg = openDatagram()
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
    private fun inviteAllowed(peer: String): Boolean {
        val now = System.currentTimeMillis()
        inviteTimes.removeAll { now - it >= INVITE_WINDOW_MS }
        if (inviteTimes.size >= INVITE_BURST) return false
        val last = invitePeerAt[CallSignals.samePeerKey(peer)] ?: return true
        return now - last >= INVITE_COOLDOWN_MS
    }

    private fun noteInvite(peer: String) {
        val now = System.currentTimeMillis()
        inviteTimes.add(now)
        invitePeerAt[CallSignals.samePeerKey(peer)] = now
        if (invitePeerAt.size > 64) {
            val cutoff = now - INVITE_WINDOW_MS
            val stale = invitePeerAt.filterValues { it < cutoff }.keys
            stale.forEach { invitePeerAt.remove(it) }
        }
    }

    private fun applyRemoteToken(token: String): Boolean {
        val t = token.trim()
        if (!CallSignals.isDatagramToken(t)) {
            remoteDestB64 = ""
            return false
        }
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
            if (!CallSignals.isNamingName(name)) return@launch
            runCatching {
                SamDatagramSession.namingLookup(samHost(), samPort(), name)
            }.onSuccess { b64 ->
                if (generation == gen && remoteDestB64 == token && CallSignals.isDatagramToken(b64)) {
                    remoteCache[token] = b64
                    remoteDestB64 = b64
                    _state.update { it.copy(mediaReady = isFullDest(b64)) }
                }
            }.onFailure {
                resolveFailures += 1
                val delayMs = minOf(30_000L, 1_000L shl minOf(resolveFailures, 5))
                nextResolveAtMs = System.currentTimeMillis() + delayMs
                Log.i(TAG, "naming lookup failed (retry in ${delayMs}ms)")
            }
        }
    }

    private fun openDatagram(): SamDatagramSession {
        val len = tunnelLength().coerceIn(1, 7).toString()
        return SamDatagramSession.open(
            sessionId = "call-${UUID.randomUUID().toString().take(10)}",
            samHost = samHost(),
            samPort = samPort(),
            onDatagram = ::onDatagram,
            // Own tunnels for media: hop count follows the router setting, a few parallel
            // tunnels plus a spare so one slow tunnel does not stall the call.
            options = mapOf(
                "inbound.length" to len,
                "outbound.length" to len,
                "inbound.quantity" to "3",
                "outbound.quantity" to "3",
                "inbound.backupQuantity" to "1",
                "outbound.backupQuantity" to "1",
            ),
        )
    }

    /**
     * One media frame -> N datagrams sharing a seq (see CallMediaFraming). Fragmented video
     * frames also get one XOR parity datagram and are paced so they do not burst into a tunnel.
     * Audio uses its own seq counter, flagged so the receiver can spot lost frames.
     */
    private fun sendFramed(type: Int, payload: ByteArray, paced: Boolean = false): Boolean {
        val snap = _state.value
        if (snap.phase != CallPhase.Active) return false
        val target = remoteSendTarget()
        val dg = datagram
        if (target.isEmpty() || dg == null) {
            txDropped.incrementAndGet()
            return false
        }
        val isAudio = type == CallMediaFraming.TYPE_OPUS || type == CallMediaFraming.TYPE_PCM16
        val hash = CallSignals.callIdHash16(snap.callId)
        val now = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val frameSeq = if (isAudio) audioSeq.incrementAndGet() else seq.incrementAndGet()
        val flags = if (isAudio) CallMediaFraming.FLAG_AUDIO_SEQ else 0
        val chunks = CallMediaFraming.fragment(payload)
        val packets = ArrayList<CallMediaFraming.Packet>(chunks.size + 1)
        for ((idx, chunk) in chunks.withIndex()) {
            packets.add(
                CallMediaFraming.Packet(
                    callHash = hash, seq = frameSeq, tsMs = now, type = type, flags = flags,
                    payload = chunk, fragIdx = idx, fragTotal = chunks.size,
                ),
            )
        }
        val isVideo = type == CallMediaFraming.TYPE_VIDEO_KEY ||
            type == CallMediaFraming.TYPE_VIDEO_DELTA ||
            type == CallMediaFraming.TYPE_VIDEO_JPEG
        if (isVideo && chunks.size >= 2) {
            packets.add(
                CallMediaFraming.Packet(
                    callHash = hash, seq = frameSeq, tsMs = now,
                    type = CallMediaFraming.TYPE_PARITY, flags = 0,
                    payload = CallMediaFraming.buildParityPayload(type, chunks),
                ),
            )
        }
        for ((i, pkt) in packets.withIndex()) {
            if (paced && i > 0) Thread.sleep(FRAGMENT_PACE_MS)
            if (runCatching { dg.send(target, pkt.encode()) }.isFailure) {
                txDropped.incrementAndGet()
                return false
            }
            txPackets.incrementAndGet()
        }
        return true
    }

    private fun sendMediaState() {
        val s = _state.value
        if (s.peer.isBlank() || s.callId.isBlank()) return
        sendSignal(s.peer, CallSignals.buildMedia(s.callId, s.mic, s.cam, s.screen, s.spk))
    }

    private fun onDatagram(from: String, payload: ByteArray) {
        val snap = _state.value
        if (snap.phase != CallPhase.Active || snap.callId.isEmpty()) {
            rxDropped.incrementAndGet()
            return
        }
        if (payload.size > CallMediaFraming.HEADER_SIZE + CallMediaFraming.MAX_PACKET_PAYLOAD) {
            rxDropped.incrementAndGet()
            return
        }
        val pkt = CallMediaFraming.decode(payload)
        if (pkt == null) {
            rxDropped.incrementAndGet()
            return
        }
        if (!pkt.callHash.contentEquals(CallSignals.callIdHash16(snap.callId))) {
            rxDropped.incrementAndGet()
            return
        }
        if (!I2pDestinationUtil.sameDatagramSender(from, remoteDestB64)) {
            rxDropped.incrementAndGet()
            return
        }
        rxPackets.incrementAndGet()
        val body = reassembler.feed(pkt) ?: return
        when (pkt.type) {
            CallMediaFraming.TYPE_PCM16, CallMediaFraming.TYPE_OPUS -> {
                val ex = audioRx ?: return
                if (audioRxPending.get() >= MAX_AUDIO_BACKLOG) {
                    rxDropped.incrementAndGet()
                    return
                }
                audioRxPending.incrementAndGet()
                ex.execute {
                    try {
                        handleAudio(pkt, body)
                    } finally {
                        audioRxPending.decrementAndGet()
                    }
                }
            }
            CallMediaFraming.TYPE_VIDEO_JPEG,
            CallMediaFraming.TYPE_VIDEO_KEY,
            CallMediaFraming.TYPE_VIDEO_DELTA -> {
                val ex = videoRx ?: return
                if (videoRxPending.get() >= MAX_VIDEO_BACKLOG) {
                    // Falling behind: drop this picture and ask for a fresh keyframe.
                    rxDropped.incrementAndGet()
                    requestRemoteKeyframe()
                    return
                }
                videoRxPending.incrementAndGet()
                ex.execute {
                    try {
                        handleVideo(pkt.type, body)
                    } finally {
                        videoRxPending.decrementAndGet()
                    }
                }
            }
            CallMediaFraming.TYPE_KEYFRAME_REQ -> onKeyframeRequested()
        }
    }

    private fun handleAudio(pkt: CallMediaFraming.Packet, body: ByteArray) {
        if (!_state.value.spk) return
        if (pkt.type == CallMediaFraming.TYPE_PCM16) {
            playout.push(pcm16ToShorts(body))
            return
        }
        val dec = opusDec ?: return
        val tracked = (pkt.flags and CallMediaFraming.FLAG_AUDIO_SEQ) != 0
        val missing = seqTracker.onPacket(pkt.seq, tracked)
        if (missing < 0) return // late or duplicate
        if (missing > 0) {
            // MediaCodec offers no PLC/FEC access: fade out a repeat of the last frame instead.
            for (f in concealFrames(lastRxFrame, missing)) playout.push(f)
        }
        val pcm = dec.decode(body)
        if (pcm.isNotEmpty()) {
            lastRxFrame = if (pcm.size > CallAudioFormat.FRAME_SAMPLES) {
                pcm.copyOfRange(pcm.size - CallAudioFormat.FRAME_SAMPLES, pcm.size)
            } else {
                pcm
            }
            playout.push(pcm)
        }
    }

    private fun handleVideo(type: Int, body: ByteArray) {
        if (body.size > CallMediaFraming.MAX_RX_FRAME_BYTES) return
        if (type == CallMediaFraming.TYPE_VIDEO_JPEG) {
            val bmp = decodeBoundedJpeg(body)
            if (bmp != null) _state.update { it.copy(remoteFrame = bmp) }
            return
        }
        if (CallMediaFraming.vp8KeyframeRejected(body)) {
            requestRemoteKeyframe()
            return
        }
        if (type == CallMediaFraming.TYPE_VIDEO_KEY) gotRemoteKey = true
        if (!gotRemoteKey) {
            // A delta frame is useless before the first keyframe.
            requestRemoteKeyframe()
            return
        }
        val dec = vp8Dec ?: Vp8FrameDecoder().also { vp8Dec = it }
        val frame = dec.decode(body)
        if (frame == null) {
            requestRemoteKeyframe()
            return
        }
        val bmp = runCatching { Yuv.toBitmap(frame) }.getOrNull() ?: return
        _state.update { it.copy(remoteFrame = bmp) }
    }

    private fun decodeBoundedJpeg(body: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
        if (!CallMediaFraming.videoDimensionsAllowed(bounds.outWidth, bounds.outHeight)) return null
        return runCatching { BitmapFactory.decodeByteArray(body, 0, body.size) }.getOrNull()
    }

    /** Ask the peer for a keyframe (throttled). */
    private fun requestRemoteKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyReqMs < KEYFRAME_REQ_MIN_MS) return
        lastKeyReqMs = now
        sendFramed(CallMediaFraming.TYPE_KEYFRAME_REQ, ByteArray(0))
    }

    /** The peer cannot decode our video: send a keyframe; repeated asks mean shrink. */
    private fun onKeyframeRequested() {
        videoSender?.requestKeyframe()
        pliHits += 1
        if (pliHits >= 2) {
            pliHits = 0
            adapt?.let { eff = it.bump() }
        }
    }

    // --- video capture glue --------------------------------------------------------------

    fun onScreenFrame(frame: I420Frame) {
        if (_state.value.screen) videoSender?.submit(frame)
    }

    /** MediaProjection stopped (user, system, or error). */
    fun onScreenCaptureEnded() {
        scope.launch(Dispatchers.IO) {
            if (_state.value.screen) {
                _state.update { it.copy(screen = false, localFrame = if (it.cam) it.localFrame else null) }
                if (_state.value.phase == CallPhase.Active) sendMediaState()
                syncVideoSources()
            }
        }
    }

    /** Start/stop screen sharing; the projection token itself is handed to [CallForegroundService]. */
    fun setScreenSharing(on: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (on) {
                _state.update { it.copy(screen = true, cam = false) }
            } else {
                CallForegroundService.stopProjection(appContext)
                _state.update { it.copy(screen = false, localFrame = if (it.cam) it.localFrame else null) }
            }
            if (_state.value.phase == CallPhase.Active) sendMediaState()
            syncVideoSources()
        }
    }

    /** Turn the camera / sender on or off to match the call state. */
    private fun syncVideoSources() {
        val s = _state.value
        val active = s.phase == CallPhase.Active
        val wantCam = active && s.cam
        val wantSend = active && (s.cam || s.screen)
        val cam = camera ?: CallCamera(appContext) { f ->
            if (_state.value.cam) videoSender?.submit(f)
        }.also { camera = it }
        if (wantCam) {
            cam.minIntervalMs = 1000L / eff.fps.coerceAtLeast(1)
            if (!cam.isRunning && !cam.start(s.videoMaxEdge.coerceAtMost(CAMERA_MAX_EDGE))) {
                Log.w(TAG, "camera unavailable (permission?)")
                _state.update { it.copy(cam = false, error = "camera") }
                sendMediaState()
            }
        } else if (cam.isRunning) {
            cam.stop()
        }
        if (wantSend) {
            videoSender?.start()
        } else {
            videoSender?.stop()
            _state.update { if (it.localFrame != null) it.copy(localFrame = null) else it }
        }
        if (active) CallForegroundService.sync(appContext)
    }

    // --- audio ---------------------------------------------------------------------------

    private fun startMedia() {
        val s = _state.value
        resetMediaCounters()
        eff = EffectiveMedia(s.audioBitrateKbps, s.videoBitrateKbps, s.videoFps, s.videoMaxEdge)
        adapt = AdaptiveController(eff)
        audioRx = Executors.newSingleThreadExecutor { r -> Thread(r, "call-audio-rx") }
        videoRx = Executors.newSingleThreadExecutor { r -> Thread(r, "call-video-rx") }
        videoSender = CallVideoSender(
            effective = { eff },
            useVp8 = { usesVp8() },
            emit = { type, payload -> sendFramed(type, payload, paced = true) },
            onPreview = { bmp -> _state.update { it.copy(localFrame = bmp) } },
            onOversize = { adapt?.let { eff = it.bump() } },
        )
        if (CallSignals.codecsInclude(s.codecs, "opus")) {
            if (opusDec == null && OpusFrameDecoder.isSupported()) opusDec = OpusFrameDecoder()
            if (opusEnc == null && OpusFrameEncoder.isSupported()) {
                opusEnc = OpusFrameEncoder(s.audioBitrateKbps * 1000)
            }
        }
        startAudioLoop()
        startPlayback()
        startStatsLoop()
        syncVideoSources()
    }

    private fun resetMediaCounters() {
        txPackets.set(0); txDropped.set(0); rxPackets.set(0); rxDropped.set(0)
        audioSeq.set(0)
        seqTracker.reset()
        lastRxFrame = ShortArray(0)
        gotRemoteKey = false
        lastKeyReqMs = 0L
        lastDroppedSeen = reassembler.droppedFrames
        lastVideoBytes = 0L
        pliHits = 0
        playout.clear()
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value.phase == CallPhase.Active) {
                kotlinx.coroutines.delay(STATS_INTERVAL_MS)
                val ctrl = adapt ?: continue
                val dropped = reassembler.droppedFrames
                val rxDrop = rxDropped.get() + dropped
                val prevLevel = eff.level
                eff = ctrl.observe(txPackets.get(), txDropped.get(), rxPackets.get(), rxDrop)
                if (eff.level > prevLevel) videoSender?.requestKeyframe()
                if (dropped > lastDroppedSeen) {
                    // Frames were lost on the way: a keyframe resyncs the picture sooner.
                    lastDroppedSeen = dropped
                    if (_state.value.remoteCam || _state.value.remoteScreen) requestRemoteKeyframe()
                }
                camera?.minIntervalMs = 1000L / eff.fps.coerceAtLeast(1)
                val sent = videoSender?.bytesSent?.get() ?: 0L
                val kbps = ((sent - lastVideoBytes) * 8 / STATS_INTERVAL_MS).toInt()
                lastVideoBytes = sent
                val e = eff
                _state.update {
                    it.copy(
                        liveEdge = e.edge, liveFps = e.fps, liveKbps = kbps,
                        txPackets = txPackets.get(), rxPackets = rxPackets.get(),
                    )
                }
            }
        }
    }

    private fun startPlayback() {
        playbackRunning = false
        playbackThread?.interrupt()
        playbackRunning = true
        playbackThread = Thread({
            val rate = CallAudioFormat.SAMPLE_RATE
            val outMin = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(outMin, CallAudioFormat.FRAME_SAMPLES * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
            if (track == null) {
                playbackRunning = false
                return@Thread
            }
            runCatching { track.play() }
            try {
                while (playbackRunning) {
                    val pcm = playout.pull(CallAudioFormat.FRAME_SAMPLES)
                    if (pcm == null) {
                        Thread.sleep(5)
                        continue
                    }
                    track.write(pcm, 0, pcm.size) // blocking: paces us at real time
                }
            } catch (_: InterruptedException) {
                // stopped
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }, "call-playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun startAudioLoop() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val rate = CallAudioFormat.SAMPLE_RATE
            val frame = CallAudioFormat.FRAME_SAMPLES
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, frame * 2 * 4),
                )
            }.getOrNull() ?: return@launch
            runCatching { recorder.startRecording() }
            val buf = ShortArray(frame)
            var acc = ShortArray(frame * 8)
            var accN = 0
            val enc = opusEnc?.takeIf { it.available }
            val pcmPack = java.io.ByteArrayOutputStream()
            try {
                while (isActive && _state.value.phase == CallPhase.Active) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val snap = _state.value
                    if (!snap.mic || remoteSendTarget().isEmpty()) {
                        accN = 0
                        pcmPack.reset()
                        continue
                    }
                    if (accN + n > acc.size) acc = acc.copyOf(acc.size * 2)
                    System.arraycopy(buf, 0, acc, accN, n)
                    accN += n
                    while (accN >= frame) {
                        val chunk = acc.copyOfRange(0, frame)
                        System.arraycopy(acc, frame, acc, 0, accN - frame)
                        accN -= frame
                        if (enc != null) {
                            for (pkt in enc.encode(chunk)) sendFramed(CallMediaFraming.TYPE_OPUS, pkt)
                        } else {
                            // PCM fallback: 3 x 20 ms per datagram, less per-datagram I2P overhead.
                            pcmPack.write(shortsToPcm16(chunk))
                            if (pcmPack.size() >= frame * 2 * AUDIO_FRAMES_PER_PACKET) {
                                val bytes = pcmPack.toByteArray()
                                pcmPack.reset()
                                sendFramed(CallMediaFraming.TYPE_PCM16, bytes)
                            }
                        }
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    private fun stopMedia() {
        audioJob?.cancel()
        audioJob = null
        statsJob?.cancel()
        statsJob = null
        playbackRunning = false
        playbackThread?.interrupt()
        playbackThread = null
        videoSender?.stop()
        videoSender = null
        runCatching { camera?.stop() }
        camera = null
        audioRx?.shutdownNow()
        audioRx = null
        videoRx?.shutdownNow()
        videoRx = null
        videoRxPending.set(0)
        runCatching { vp8Dec?.release() }
        vp8Dec = null
        runCatching { opusDec?.release() }
        opusDec = null
        runCatching { opusEnc?.release() }
        opusEnc = null
        adapt = null
        playout.clear()
        CallForegroundService.stopProjection(appContext)
        CallForegroundService.stop(appContext)
    }

    private fun beginLocalTeardown(reason: String) {
        generation += 1
        setupJob?.cancel()
        setupJob = null
        teardown(reason)
    }

    private fun teardown(reason: String) {
        stopMedia()
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
            preset = _state.value.preset,
        )
        scope.launch(Dispatchers.IO) { runCatching { dg?.close() } }
        Log.i(TAG, "call ended: $reason")
    }

    companion object {
        private const val TAG = "CallController"
        private const val REMOTE_DEST_MIN = 80
        private const val AUDIO_FRAMES_PER_PACKET = 3
        private const val FRAGMENT_PACE_MS = 2L
        private const val MAX_VIDEO_BACKLOG = 3
        private const val MAX_AUDIO_BACKLOG = 12
        private const val INVITE_COOLDOWN_MS = 5_000L
        private const val INVITE_WINDOW_MS = 60_000L
        private const val INVITE_BURST = 6
        private const val KEYFRAME_REQ_MIN_MS = 1_500L
        private const val STATS_INTERVAL_MS = 2_000L
        private const val CAMERA_MAX_EDGE = 640
    }
}
