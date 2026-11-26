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
            require(payload.size <= MAX_PAYLOAD)
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
        if (data.size < HEADER_SIZE) return null
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

    /** Rebuilds fragmented frames; incomplete frames expire after [timeoutMs]. */
    class Reassembler(
        private val timeoutMs: Long = 1_500L,
        private val maxPending: Int = 16,
        private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    ) {
        private class Pending(val total: Int, val startedMs: Long) {
            val parts = HashMap<Int, ByteArray>()
        }

        private val pending = LinkedHashMap<Long, Pending>()
        var droppedFrames = 0
            private set

        val pendingCount: Int get() = pending.size

        @Synchronized
        fun feed(pkt: Packet): ByteArray? {
            val now = clock()
            expire(now)
            if (pkt.fragTotal <= 1) return pkt.payload
            val key = (pkt.seq.toLong() shl 8) or pkt.type.toLong()
            var p = pending[key]
            if (p == null || p.total != pkt.fragTotal) {
                if (pending.size >= maxPending) {
                    val oldest = pending.entries.minByOrNull { it.value.startedMs }?.key
                    if (oldest != null) {
                        pending.remove(oldest)
                        droppedFrames += 1
                    }
                }
                p = Pending(pkt.fragTotal, now)
                pending[key] = p
            }
            p.parts[pkt.fragIdx] = pkt.payload
            if (p.parts.size < p.total) return null
            pending.remove(key)
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
                    it.remove()
                    droppedFrames += 1
                }
            }
        }
    }
}
