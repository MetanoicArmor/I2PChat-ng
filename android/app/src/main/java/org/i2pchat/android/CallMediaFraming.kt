package org.i2pchat.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

object CallMediaFraming {
    val MAGIC = byteArrayOf('I'.code.toByte(), '2'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    const val HEADER_SIZE = 30
    const val MAX_PAYLOAD = 10_000
    const val TYPE_OPUS = 1
    const val TYPE_VIDEO_JPEG = 3
    const val TYPE_PCM16 = 5
    const val FLAG_FRAG_MORE = 0x01

    data class Packet(
        val callHash: ByteArray,
        val seq: Int,
        val tsMs: Int,
        val type: Int,
        val flags: Int,
        val payload: ByteArray,
    ) {
        fun encode(): ByteArray {
            require(callHash.size == 16)
            require(payload.size <= MAX_PAYLOAD)
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(callHash)
            buf.putInt(seq)
            buf.putInt(tsMs)
            buf.put(type.toByte())
            buf.put(flags.toByte())
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
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        return Packet(hash, seq, ts, type, flags, payload)
    }

    fun fragment(payload: ByteArray, maxChunk: Int = MAX_PAYLOAD): List<Pair<ByteArray, Int>> {
        if (payload.isEmpty()) return listOf(ByteArray(0) to 0)
        val out = ArrayList<Pair<ByteArray, Int>>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxChunk, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            offset = end
            val flags = if (offset < payload.size) FLAG_FRAG_MORE else 0
            out.add(chunk to flags)
        }
        return out
    }
}
