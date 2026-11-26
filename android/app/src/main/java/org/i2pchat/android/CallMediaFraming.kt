package org.i2pchat.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Call media framing, format `I2C2` (mirrors i2pchat/core/media_framing.py):
 * magic(4) callHash(16) seq(4) tsMs(4) type(1) flags(1) fragIdx(1) fragTotal(1) payload.
 *
 * MAX_PAYLOAD stays well below i2pd's 8192-byte SAM control socket buffer.
 */
object CallMediaFraming {
    val MAGIC = byteArrayOf('I'.code.toByte(), '2'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte())
    const val HEADER_SIZE = 32
    const val MAX_PAYLOAD = 3_500
    const val MAX_FRAGMENTS = 255
    const val TYPE_OPUS = 1
    const val TYPE_VIDEO_KEY = 2 // VP8 keyframe
    const val TYPE_VIDEO_JPEG = 3
    const val TYPE_VIDEO_DELTA = 4 // VP8 delta
    const val TYPE_PCM16 = 5
    const val TYPE_KEYFRAME_REQ = 6 // empty: please send a VP8 keyframe
    /**
     * XOR parity for one fragmented video frame, a single datagram (fragTotal == 1).
     * Payload: origType(1) fragTotal(1) frameLen(4, BE) xor-of-all-chunks (zero padded).
     */
    const val TYPE_PARITY = 7

    /** `seq` of audio packets is a dedicated per-stream counter, so gaps mean loss. */
    const val FLAG_AUDIO_SEQ = 0x04
    const val PARITY_HEADER_SIZE = 6
    const val MAX_PACKET_PAYLOAD = MAX_PAYLOAD + PARITY_HEADER_SIZE
    /** Largest frame we reassemble. VP8 may use 8 fragments; anything bigger is dropped. */
    const val MAX_RX_FRAME_BYTES = MAX_PAYLOAD * 8
    const val MAX_VIDEO_EDGE = 1280
    const val MAX_VIDEO_PIXELS = 1280 * 720

    fun videoDimensionsAllowed(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_VIDEO_EDGE || height > MAX_VIDEO_EDGE) return false
        return width.toLong() * height <= MAX_VIDEO_PIXELS
    }

    /**
     * True when [payload] must not be given to a VP8 decoder.
     * A keyframe carries width and height in its uncompressed header (RFC 6386).
     */
    fun vp8KeyframeRejected(payload: ByteArray): Boolean {
        if (payload.size > MAX_RX_FRAME_BYTES) return true
        if (payload.size < 10 || (payload[0].toInt() and 1) != 0) return false
        if (payload[3] != 0x9d.toByte() || payload[4] != 0x01.toByte() || payload[5] != 0x2a.toByte()) {
            return true
        }
        val width = (payload[6].toInt() and 0xff) or ((payload[7].toInt() and 0x3f) shl 8)
        val height = (payload[8].toInt() and 0xff) or ((payload[9].toInt() and 0x3f) shl 8)
        return !videoDimensionsAllowed(width, height)
    }

    data class Packet(
        val callHash: ByteArray,
        val seq: Int,
        val tsMs: Int,
        val type: Int,
        val flags: Int,
        val payload: ByteArray,
        val fragIdx: Int = 0,
        val fragTotal: Int = 1,
    ) {
        fun encode(): ByteArray {
            require(callHash.size == 16)
            require(payload.size <= if (type == TYPE_PARITY) MAX_PACKET_PAYLOAD else MAX_PAYLOAD)
            require(fragTotal in 1..MAX_FRAGMENTS && fragIdx in 0 until fragTotal)
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(callHash)
            buf.putInt(seq)
            buf.putInt(tsMs)
            buf.put(type.toByte())
            buf.put(flags.toByte())
            buf.put(fragIdx.toByte())
            buf.put(fragTotal.toByte())
            buf.put(payload)
            return buf.array()
        }
    }

    fun decode(data: ByteArray): Packet? {
        if (data.size < HEADER_SIZE || data.size > HEADER_SIZE + MAX_PACKET_PAYLOAD) return null
        if (!data.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val hash = ByteArray(16)
        buf.get(hash)
        val seq = buf.int
        val ts = buf.int
        val type = buf.get().toInt() and 0xff
        val flags = buf.get().toInt() and 0xff
        val idx = buf.get().toInt() and 0xff
        val total = buf.get().toInt() and 0xff
        if (total < 1 || idx >= total) return null
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        val limit = if (type == TYPE_PARITY) MAX_PACKET_PAYLOAD else MAX_PAYLOAD
        if (payload.size > limit) return null
        return Packet(hash, seq, ts, type, flags, payload, idx, total)
    }

    /** Split into chunks of at most [maxChunk] bytes (always at least one chunk). */
    fun fragment(payload: ByteArray, maxChunk: Int = MAX_PAYLOAD): List<ByteArray> {
        require(maxChunk > 0)
        if (payload.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxChunk, payload.size)
            out.add(payload.copyOfRange(offset, end))
            offset = end
        }
        require(out.size <= MAX_FRAGMENTS)
        return out
    }

    private fun xorInto(acc: ByteArray, part: ByteArray) {
        for (i in part.indices) acc[i] = (acc[i].toInt() xor part[i].toInt()).toByte()
    }

    /** XOR parity over all [chunks] of one frame (needs at least two chunks). */
    fun buildParityPayload(type: Int, chunks: List<ByteArray>): ByteArray {
        require(chunks.size in 2..MAX_FRAGMENTS)
        val size = chunks.maxOf { it.size }
        val acc = ByteArray(size)
        for (c in chunks) xorInto(acc, c)
        val frameLen = chunks.sumOf { it.size }
        val out = ByteBuffer.allocate(PARITY_HEADER_SIZE + size).order(ByteOrder.BIG_ENDIAN)
        out.put(type.toByte())
        out.put(chunks.size.toByte())
        out.putInt(frameLen)
        out.put(acc)
        return out.array()
    }

    /**
     * Rebuilds fragmented frames; incomplete frames expire after [timeoutMs].
     * A single lost fragment is recovered when the sender's [TYPE_PARITY] packet arrived.
     */
    class Reassembler(
        private val timeoutMs: Long = 1_500L,
        private val maxPending: Int = 16,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    ) {
        private class Pending(val total: Int, val type: Int, val startedMs: Long) {
            val parts = HashMap<Int, ByteArray>()
            var parity: ByteArray? = null
            var frameLen = 0
        }

        private val pending = LinkedHashMap<Long, Pending>()
        private val done = object : LinkedHashMap<Long, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > DONE_MAX
        }
        var droppedFrames = 0
            private set
        var recoveredFrames = 0
            private set

        val pendingCount: Int get() = pending.size

        private fun keyOf(seq: Int, type: Int): Long = ((seq.toLong() and 0xFFFFFFFFL) shl 8) or type.toLong()

        @Synchronized
        fun feed(pkt: Packet): ByteArray? {
            val now = clock()
            expire(now)
            if (pkt.type == TYPE_PARITY) return feedParity(pkt, now)
            if (pkt.fragTotal <= 1) {
                if (pkt.payload.size > MAX_RX_FRAME_BYTES) return null
                return pkt.payload
            }
            val key = keyOf(pkt.seq, pkt.type)
            if (done.containsKey(key)) return null // late fragment of an already rebuilt frame
            val p = pendingFor(key, pkt.fragTotal, pkt.type, now)
            if (!fits(p, pkt.fragIdx, pkt.payload)) {
                pending.remove(key)
                droppedFrames += 1
                return null
            }
            p.parts[pkt.fragIdx] = pkt.payload
            return tryComplete(key, p)
        }

        private fun feedParity(pkt: Packet, now: Long): ByteArray? {
            val d = pkt.payload
            if (d.size <= PARITY_HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN)
            val origType = bb.get().toInt() and 0xff
            val total = bb.get().toInt() and 0xff
            val frameLen = bb.int
            if (total < 2) return null
            val key = keyOf(pkt.seq, origType)
            if (done.containsKey(key)) return null
            val p = pendingFor(key, total, origType, now)
            p.parity = d.copyOfRange(PARITY_HEADER_SIZE, d.size)
            p.frameLen = frameLen
            return tryComplete(key, p)
        }

        private fun pendingFor(key: Long, total: Int, type: Int, now: Long): Pending {
            var p = pending[key]
            if (p == null || p.total != total) {
                if (pending.size >= maxPending) {
                    val oldest = pending.entries.minByOrNull { it.value.startedMs }?.key
                    if (oldest != null) {
                        pending.remove(oldest)
                        droppedFrames += 1
                    }
                }
                p = Pending(total, type, now)
                pending[key] = p
            }
            return p
        }

        private fun fits(p: Pending, idx: Int, payload: ByteArray): Boolean {
            var total = payload.size
            for ((i, part) in p.parts) if (i != idx) total += part.size
            return total <= MAX_RX_FRAME_BYTES
        }

        private fun tryComplete(key: Long, p: Pending): ByteArray? {
            if (p.parts.size >= p.total) {
                pending.remove(key)
                done[key] = true
                return bounded(join(p))
            }
            val parity = p.parity
            if (parity != null && p.parts.size == p.total - 1) {
                val rebuilt = recover(p, parity)
                pending.remove(key)
                done[key] = true
                if (rebuilt == null || rebuilt.size > MAX_RX_FRAME_BYTES) {
                    droppedFrames += 1
                    return null
                }
                recoveredFrames += 1
                return rebuilt
            }
            return null
        }

        private fun bounded(body: ByteArray): ByteArray? {
            if (body.size > MAX_RX_FRAME_BYTES) {
                droppedFrames += 1
                return null
            }
            return body
        }

        private fun recover(p: Pending, parity: ByteArray): ByteArray? {
            val size = parity.size
            val missing = (0 until p.total).first { it !in p.parts }
            val missLen = if (missing < p.total - 1) size else p.frameLen - size * (p.total - 1)
            if (missLen <= 0 || missLen > size) return null
            val acc = parity.copyOf()
            for (part in p.parts.values) {
                if (part.size > size) return null
                xorInto(acc, part)
            }
            p.parts[missing] = acc.copyOf(missLen)
            return join(p)
        }

        private fun join(p: Pending): ByteArray {
            val size = (0 until p.total).sumOf { p.parts[it]!!.size }
            val out = ByteArray(size)
            var off = 0
            for (i in 0 until p.total) {
                val part = p.parts[i]!!
                System.arraycopy(part, 0, out, off, part.size)
                off += part.size
            }
            return out
        }

        private fun expire(now: Long) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.startedMs > timeoutMs) {
                    if (e.value.parts.isNotEmpty()) droppedFrames += 1
                    it.remove()
                }
            }
        }

        private companion object {
            const val DONE_MAX = 64
        }
    }
}
