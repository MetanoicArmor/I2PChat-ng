package org.i2pchat.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Call audio is 16 kHz mono PCM16 in 20 ms frames, same as the desktop client. */
object CallAudioFormat {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
}

private const val OPUS_MIME = "audio/opus"
private const val AUDIO_TAG = "CallAudio"

/** Opus decoder over [MediaCodec]; output is resampled to 16 kHz. Soft-fails to null. */
class OpusFrameDecoder {
    private val codec: MediaCodec? = runCatching {
        val c = MediaCodec.createDecoderByType(OPUS_MIME)
        val fmt = MediaFormat.createAudioFormat(OPUS_MIME, 48_000, 1)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
        fmt.setByteBuffer("csd-1", nanos(0L)) // codec delay: do not trim the first frames
        fmt.setByteBuffer("csd-2", nanos(80_000_000L)) // seek pre-roll
        c.configure(fmt, null, null, 0)
        c.start()
        c
    }.onFailure { Log.w(AUDIO_TAG, "Opus decoder unavailable", it) }.getOrNull()

    private var ptsUs = 0L
    private var outRate = 48_000

    val available: Boolean get() = codec != null

    /** Decode one Opus packet; returns 16 kHz samples (possibly empty while the codec warms up). */
    @Synchronized
    fun decode(packet: ByteArray): ShortArray {
        val c = codec ?: return ShortArray(0)
        return runCatching {
            val inIx = c.dequeueInputBuffer(IN_TIMEOUT_US)
            if (inIx < 0) return@runCatching ShortArray(0)
            val inBuf = c.getInputBuffer(inIx) ?: return@runCatching ShortArray(0)
            inBuf.clear()
            inBuf.put(packet)
            c.queueInputBuffer(inIx, 0, packet.size, ptsUs, 0)
            ptsUs += 20_000L
            drain(c)
        }.getOrElse {
            Log.d(AUDIO_TAG, "opus decode failed", it)
            ShortArray(0)
        }
    }

    private fun drain(c: MediaCodec): ShortArray {
        val info = MediaCodec.BufferInfo()
        var out = ShortArray(0)
        var timeout = OUT_TIMEOUT_US
        while (true) {
            val ix = c.dequeueOutputBuffer(info, timeout)
            when {
                ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
                ix >= 0 -> {
                    val buf = c.getOutputBuffer(ix)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val pcm = ShortArray(sb.remaining())
                        sb.get(pcm)
                        out += downsampleTo16k(pcm, outRate)
                    }
                    c.releaseOutputBuffer(ix, false)
                    timeout = 0L
                }
                else -> return out
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    companion object {
        private const val IN_TIMEOUT_US = 5_000L
        private const val OUT_TIMEOUT_US = 12_000L

        fun isSupported(): Boolean = runCatching {
            MediaCodec.createDecoderByType(OPUS_MIME).release()
            true
        }.getOrDefault(false)

        /** Opus ID header (RFC 7845); pre-skip 0 so no output is trimmed. */
        internal fun opusHead(): ByteArray {
            val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            b.put("OpusHead".toByteArray(Charsets.US_ASCII))
            b.put(1) // version
            b.put(1) // channels
            b.putShort(0) // pre-skip
            b.putInt(CallAudioFormat.SAMPLE_RATE) // original input rate (informational)
            b.putShort(0) // output gain
            b.put(0) // channel mapping family
            return b.array()
        }

        private fun nanos(v: Long): ByteBuffer {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(v)
            b.flip()
            return b
        }
    }
}

/** Opus encoder over [MediaCodec] (API 29+); 16 kHz mono, one packet per 20 ms frame. */
class OpusFrameEncoder(bitrateBps: Int) {
    private val codec: MediaCodec? = runCatching {
        val c = MediaCodec.createEncoderByType(OPUS_MIME)
        val fmt = MediaFormat.createAudioFormat(OPUS_MIME, CallAudioFormat.SAMPLE_RATE, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceIn(12_000, 64_000))
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CallAudioFormat.FRAME_SAMPLES * 2 * 4)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        c
    }.onFailure { Log.w(AUDIO_TAG, "Opus encoder unavailable", it) }.getOrNull()

    private var ptsUs = 0L

    val available: Boolean get() = codec != null

    /** Encode one 20 ms frame; returns the packets that became ready (usually exactly one). */
    @Synchronized
    fun encode(frame: ShortArray): List<ByteArray> {
        val c = codec ?: return emptyList()
        return runCatching {
            val inIx = c.dequeueInputBuffer(IN_TIMEOUT_US)
            if (inIx < 0) return@runCatching emptyList<ByteArray>()
            val inBuf = c.getInputBuffer(inIx) ?: return@runCatching emptyList<ByteArray>()
            inBuf.clear()
            inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame)
            val bytes = frame.size * 2
            c.queueInputBuffer(inIx, 0, bytes, ptsUs, 0)
            ptsUs += 20_000L
            val out = ArrayList<ByteArray>(1)
            val info = MediaCodec.BufferInfo()
            var timeout = OUT_TIMEOUT_US
            while (true) {
                val ix = c.dequeueOutputBuffer(info, timeout)
                if (ix < 0) {
                    if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    break
                }
                val buf = c.getOutputBuffer(ix)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !isConfig) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val pkt = ByteArray(info.size)
                    buf.get(pkt)
                    out.add(pkt)
                }
                c.releaseOutputBuffer(ix, false)
                timeout = 0L
            }
            out
        }.getOrElse {
            Log.d(AUDIO_TAG, "opus encode failed", it)
            emptyList()
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    companion object {
        private const val IN_TIMEOUT_US = 5_000L
        private const val OUT_TIMEOUT_US = 12_000L

        fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 && runCatching {
            MediaCodec.createEncoderByType(OPUS_MIME).release()
            true
        }.getOrDefault(false)
    }
}

/** Box-filter downsample of PCM16 from [rate] to 16 kHz (factor must be an integer). */
fun downsampleTo16k(pcm: ShortArray, rate: Int): ShortArray {
    if (rate == CallAudioFormat.SAMPLE_RATE || rate <= 0) return pcm
    val factor = (rate / CallAudioFormat.SAMPLE_RATE).coerceAtLeast(1)
    if (factor == 1) return pcm
    val n = pcm.size / factor
    val out = ShortArray(n)
    for (i in 0 until n) {
        var acc = 0
        for (k in 0 until factor) acc += pcm[i * factor + k].toInt()
        out[i] = (acc / factor).toShort()
    }
    return out
}

/** Little-endian PCM16 bytes -> samples. */
fun pcm16ToShorts(bytes: ByteArray): ShortArray {
    val n = bytes.size / 2
    val out = ShortArray(n)
    for (i in 0 until n) {
        out[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }
    return out
}

fun shortsToPcm16(samples: ShortArray, count: Int = samples.size): ByteArray {
    val out = ByteArray(count * 2)
    for (i in 0 until count) {
        val v = samples[i].toInt()
        out[2 * i] = (v and 0xff).toByte()
        out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
    }
    return out
}

/**
 * Tracks the peer's audio sequence numbers (only when it flags them with
 * [CallMediaFraming.FLAG_AUDIO_SEQ]) to spot lost and late packets.
 */
class AudioSeqTracker {
    private var last: Long = -1L

    fun reset() {
        last = -1L
    }

    /**
     * @return -1 for a late/duplicate packet (drop it), otherwise the number of frames
     * lost right before this one (0 when in order, untracked, or the gap is a pause).
     */
    @Synchronized
    fun onPacket(seq: Int, tracked: Boolean): Int {
        if (!tracked) return 0
        val s = seq.toLong() and 0xFFFFFFFFL
        val prev = last
        last = s
        if (prev < 0) return 0
        val gap = (s - prev) and 0xFFFFFFFFL
        if (gap == 0L || gap >= 0x80000000L) {
            last = prev
            return -1
        }
        val missing = (gap - 1).toInt()
        return if (missing in 1..MAX_CONCEAL) missing else 0
    }

    companion object {
        const val MAX_CONCEAL = 6
    }
}

/** Cheap loss concealment for codecs without PLC access: repeat the last frame, fading out. */
fun concealFrames(last: ShortArray, missing: Int): List<ShortArray> {
    if (last.isEmpty() || missing <= 0) return emptyList()
    val out = ArrayList<ShortArray>(missing)
    var gain = 0.7f
    repeat(missing) {
        val f = ShortArray(last.size) { i -> (last[i] * gain).toInt().toShort() }
        out.add(f)
        gain *= 0.7f
    }
    return out
}

/**
 * Jitter buffer for playback with an adaptive depth: it waits for [prebufMs] before
 * starting, grows after an underrun and relaxes after a calm stretch (mirrors the desktop).
 */
class PlayoutBuffer(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var buf = ShortArray(CallAudioFormat.SAMPLE_RATE) // 1 s
    private var start = 0
    private var end = 0
    private var started = false
    private var lastDataMs = 0L
    private var changedAtMs = 0L

    var prebufMs = BASE_PREBUF_MS
        private set

    private val size: Int get() = end - start

    @Synchronized
    fun push(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        lastDataMs = clockMs()
        ensureCapacity(pcm.size)
        System.arraycopy(pcm, 0, buf, end, pcm.size)
        end += pcm.size
        val maxSamples = CallAudioFormat.SAMPLE_RATE * MAX_BUFFER_MS / 1000
        val overflow = size - maxSamples
        if (overflow > 0) start += overflow // drop the oldest audio, keep latency bounded
    }

    /** Next [samples] to play, or null while pre-buffering / starved. */
    @Synchronized
    fun pull(samples: Int): ShortArray? {
        val now = clockMs()
        if (!started) {
            if (size < CallAudioFormat.SAMPLE_RATE * prebufMs / 1000) return null
            started = true
        }
        if (size == 0) {
            if (now - lastDataMs > STARVE_MS) {
                started = false
                prebufMs = (prebufMs + STEP_UP_MS).coerceAtMost(MAX_PREBUF_MS)
                changedAtMs = now
            }
            return null
        }
        if (prebufMs > BASE_PREBUF_MS && now - changedAtMs > CALM_MS) {
            prebufMs = (prebufMs - STEP_DOWN_MS).coerceAtLeast(BASE_PREBUF_MS)
            changedAtMs = now
        }
        val n = minOf(samples, size)
        val out = buf.copyOfRange(start, start + n)
        start += n
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    @Synchronized
    fun clear() {
        start = 0
        end = 0
        started = false
        prebufMs = BASE_PREBUF_MS
        changedAtMs = 0L
    }

    private fun ensureCapacity(extra: Int) {
        if (end + extra <= buf.size) return
        if (start > 0) {
            System.arraycopy(buf, start, buf, 0, size)
            end -= start
            start = 0
        }
        if (end + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + extra))
    }

    companion object {
        const val BASE_PREBUF_MS = 120
        const val MAX_PREBUF_MS = 260
        const val MAX_BUFFER_MS = 500
        const val STEP_UP_MS = 40
        const val STEP_DOWN_MS = 20
        const val CALM_MS = 20_000L
        const val STARVE_MS = 300L
    }
}
