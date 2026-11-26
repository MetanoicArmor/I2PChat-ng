package org.i2pchat.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Minimal VP8 encode/decode via [MediaCodec] for call DATAGRAM payloads.
 * Soft-fails when the device has no VP8 encoder/decoder.
 */
class CallVp8Encoder(
    width: Int,
    height: Int,
    bitrateKbps: Int,
    fps: Int,
) {
    private val codec: MediaCodec?
    private val w = width.coerceAtLeast(16) and 1.inv()
    private val h = height.coerceAtLeast(16) and 1.inv()

    init {
        codec = runCatching {
            val c = MediaCodec.createEncoderByType("video/x-vnd.on2.vp8")
            val fmt = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(50, 800) * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(3, 20))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            c
        }.onFailure { Log.w(TAG, "VP8 encoder unavailable", it) }.getOrNull()
    }

    val available: Boolean get() = codec != null

    /** Encode one NV12/NV21-sized buffer loosely as YUV420; returns null on failure. */
    fun encode(yuv: ByteArray, forceKey: Boolean): Pair<ByteArray, Boolean>? {
        val c = codec ?: return null
        return runCatching {
            if (forceKey) {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                c.setParameters(params)
            }
            val inIx = c.dequeueInputBuffer(2_000)
            if (inIx < 0) return null
            val inBuf = c.getInputBuffer(inIx) ?: return null
            inBuf.clear()
            inBuf.put(yuv, 0, minOf(yuv.size, inBuf.capacity()))
            c.queueInputBuffer(inIx, 0, minOf(yuv.size, inBuf.capacity()), System.nanoTime() / 1000, 0)
            val info = MediaCodec.BufferInfo()
            val outIx = c.dequeueOutputBuffer(info, 2_000)
            if (outIx < 0) return null
            val outBuf = c.getOutputBuffer(outIx) ?: return null
            val bytes = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.get(bytes)
            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            c.releaseOutputBuffer(outIx, false)
            bytes to key
        }.getOrNull()
    }

    fun release() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
    }

    companion object {
        private const val TAG = "CallVp8"
        fun isSupported(): Boolean =
            runCatching {
                MediaCodec.createEncoderByType("video/x-vnd.on2.vp8").release()
                true
            }.getOrDefault(false)
    }
}

class CallVp8Decoder {
    private val codec: MediaCodec? = runCatching {
        val c = MediaCodec.createDecoderByType("video/x-vnd.on2.vp8")
        // Width/height filled on first format; use placeholders.
        val fmt = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", 320, 240)
        c.configure(fmt, null, null, 0)
        c.start()
        c
    }.onFailure { Log.w(TAG, "VP8 decoder unavailable", it) }.getOrNull()

    val available: Boolean get() = codec != null

    /** Returns decoded raw buffer (implementation-defined); UI may keep JPEG path for display. */
    fun decode(payload: ByteArray): ByteArray? {
        val c = codec ?: return null
        return runCatching {
            val inIx = c.dequeueInputBuffer(2_000)
            if (inIx < 0) return null
            val inBuf = c.getInputBuffer(inIx) ?: return null
            inBuf.clear()
            inBuf.put(payload)
            c.queueInputBuffer(inIx, 0, payload.size, System.nanoTime() / 1000, 0)
            val info = MediaCodec.BufferInfo()
            val outIx = c.dequeueOutputBuffer(info, 2_000)
            if (outIx < 0) return null
            val outBuf = c.getOutputBuffer(outIx) ?: return null
            val bytes = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.get(bytes)
            c.releaseOutputBuffer(outIx, false)
            bytes
        }.getOrNull()
    }

    fun release() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
    }

    companion object {
        private const val TAG = "CallVp8"
    }
}
