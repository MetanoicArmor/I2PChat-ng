package org.i2pchat.android

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns the newest captured frame (camera or screen) into VP8 / JPEG datagram payloads.
 *
 * Capture sources only call [submit]; a single worker thread paces encoding at the effective
 * fps, scales to the effective edge, and re-sends the last picture about once a second so a
 * static screen still produces keyframes on request.
 */
class CallVideoSender(
    private val effective: () -> EffectiveMedia,
    private val useVp8: () -> Boolean,
    /** Send one finished frame: (CallMediaFraming.TYPE_*, payload). */
    private val emit: (type: Int, payload: ByteArray) -> Unit,
    private val onPreview: (Bitmap) -> Unit,
    /** A frame did not fit the datagram budget: the caller should step quality down. */
    private val onOversize: () -> Unit,
) {
    @Volatile private var latest: I420Frame? = null
    @Volatile private var running = false
    @Volatile private var forceKey = true
    private var worker: Thread? = null
    private var enc: Vp8FrameEncoder? = null
    private var encKbps = 0
    private var vp8Broken = false
    private var lastId = -1L
    private var lastSentMs = 0L
    private var lastPreviewMs = 0L

    val bytesSent = AtomicLong(0)

    fun submit(frame: I420Frame) {
        latest = frame
    }

    fun requestKeyframe() {
        forceKey = true
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        forceKey = true
        lastId = -1L
        worker = Thread({ loop() }, "call-video-send").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = worker
        worker = null
        latest = null
        t?.interrupt()
        runCatching { t?.join(500) }
    }

    private fun loop() {
        try {
            while (running) {
                val t0 = System.currentTimeMillis()
                val eff = effective()
                val frame = latest
                if (frame != null) {
                    val fresh = frame.id != lastId
                    val keepalive = t0 - lastSentMs >= KEEPALIVE_MS
                    if (fresh || keepalive || forceKey) {
                        lastId = frame.id
                        runCatching { encodeAndSend(frame, eff) }
                            .onFailure { Log.d(TAG, "video send failed", it) }
                        lastSentMs = System.currentTimeMillis()
                    }
                }
                val interval = 1000L / eff.fps.coerceAtLeast(1)
                val wait = (interval - (System.currentTimeMillis() - t0)).coerceAtLeast(5L)
                Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
            // stop()
        } finally {
            enc?.release()
            enc = null
        }
    }

    private fun encodeAndSend(frame: I420Frame, eff: EffectiveMedia) {
        val scaled = Yuv.fitEdge(frame, eff.edge)
        maybePreview(scaled)
        if (useVp8() && !vp8Broken) {
            if (sendVp8(scaled, eff)) return
        }
        val jpg = Yuv.toJpegWithin(scaled, CallMediaFraming.MAX_PAYLOAD * JPEG_MAX_FRAGMENTS, eff.jpegQuality)
        if (jpg == null) {
            onOversize()
            return
        }
        bytesSent.addAndGet(jpg.size.toLong())
        emit(CallMediaFraming.TYPE_VIDEO_JPEG, jpg)
        forceKey = false
    }

    /** @return false when VP8 is unusable here and the caller should fall back to JPEG. */
    private fun sendVp8(frame: I420Frame, eff: EffectiveMedia): Boolean {
        var e = enc
        if (e == null || e.width != frame.w || e.height != frame.h) {
            e?.release()
            e = Vp8FrameEncoder(frame.w, frame.h, eff.videoKbps, eff.fps)
            enc = e
            encKbps = eff.videoKbps
            forceKey = true
            if (!e.available) {
                vp8Broken = true
                enc = null
                return false
            }
        } else if (eff.videoKbps != encKbps) {
            e.setBitrate(eff.videoKbps)
            encKbps = eff.videoKbps
        }
        val key = forceKey
        forceKey = false
        for ((bytes, isKey) in e.encode(frame, key)) {
            if (bytes.size > CallMediaFraming.MAX_PAYLOAD * VP8_MAX_FRAGMENTS) {
                forceKey = true
                onOversize()
                continue
            }
            bytesSent.addAndGet(bytes.size.toLong())
            emit(if (isKey) CallMediaFraming.TYPE_VIDEO_KEY else CallMediaFraming.TYPE_VIDEO_DELTA, bytes)
        }
        return true
    }

    private fun maybePreview(f: I420Frame) {
        val now = System.currentTimeMillis()
        if (now - lastPreviewMs < PREVIEW_MS) return
        lastPreviewMs = now
        val step = (maxOf(f.w, f.h) / PREVIEW_EDGE).coerceAtLeast(1)
        runCatching { onPreview(Yuv.toBitmap(f, step)) }
    }

    private companion object {
        const val TAG = "CallVideoSender"
        const val KEEPALIVE_MS = 1_000L
        const val PREVIEW_MS = 300L
        const val PREVIEW_EDGE = 240
        const val JPEG_MAX_FRAGMENTS = 4
        const val VP8_MAX_FRAGMENTS = 8
    }
}
