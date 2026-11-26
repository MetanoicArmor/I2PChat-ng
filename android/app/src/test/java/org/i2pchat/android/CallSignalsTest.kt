package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalsTest {
    @Test
    fun inviteAcceptRoundTrip() {
        val invite = CallSignals.buildInvite("1-abc", true, "opus,jpeg", 300_000, "xyz.b32.i2p")
        val parsed = CallSignals.parse(invite)
        assertNotNull(parsed)
        assertEquals(CallSignals.INVITE, parsed!!.kind)
        assertTrue(parsed.wantVideo)
        assertEquals("xyz.b32.i2p", parsed.datagramB32)

        val accept = CallSignals.buildAccept("1-abc", "opus,jpeg", 250_000, "peerdest")
        val parsedA = CallSignals.parse(accept)!!
        assertEquals(CallSignals.ACCEPT, parsedA.kind)
        assertEquals(250_000, parsedA.maxBitrate)
    }

    @Test
    fun callIdAndDatagramTokenRejectInjection() {
        assertTrue(CallSignals.isCallId(CallSignals.newCallId()))
        assertTrue(CallSignals.newCallId() != CallSignals.newCallId())
        assertFalse(CallSignals.isCallId("bad\nid"))
        assertFalse(CallSignals.isCallId(""))

        val b32 = "a".repeat(52)
        assertTrue(CallSignals.isDatagramToken(b32))
        assertTrue(CallSignals.isDatagramToken("$b32.b32.i2p"))
        assertTrue(CallSignals.isDatagramToken("A".repeat(120)))
        assertFalse(CallSignals.isDatagramToken("bad\nSESSION"))
        assertFalse(CallSignals.isDatagramToken("xyz.b32.i2p"))
        assertTrue(CallSignals.isNamingName("$b32.b32.i2p"))
        assertFalse(CallSignals.isNamingName("bad\nname.b32.i2p"))
        assertTrue(CallSignals.samePeer(b32, "$b32.b32.i2p"))
        assertFalse(CallSignals.samePeer(b32, "b".repeat(52)))
    }

    @Test
    fun mediaFlags() {
        val body = CallSignals.buildMedia("cid", mic = false, cam = true, screen = false, spk = true)
        val parsed = CallSignals.parse(body)!!
        assertEquals(CallSignals.MEDIA, parsed.kind)
        assertFalse(parsed.mic)
        assertTrue(parsed.cam)
    }

    @Test
    fun framingRoundTrip() {
        val hash = CallSignals.callIdHash16("hello")
        assertEquals(16, hash.size)
        val pkt = CallMediaFraming.Packet(
            callHash = hash,
            seq = 7,
            tsMs = 123,
            type = CallMediaFraming.TYPE_PCM16,
            flags = 0,
            payload = byteArrayOf(1, 2, 3),
        )
        val decoded = CallMediaFraming.decode(pkt.encode())!!
        assertEquals(7, decoded.seq)
        assertEquals(CallMediaFraming.TYPE_PCM16, decoded.type)
        assertEquals(3, decoded.payload.size)
    }

    @Test
    fun fragmentChunks() {
        val parts = CallMediaFraming.fragment(ByteArray(10) { it.toByte() }, maxChunk = 4)
        assertEquals(3, parts.size)
        assertEquals(4, parts[0].size)
        assertEquals(2, parts[2].size)
    }

    @Test
    fun reassemblerRebuildsOutOfOrderFrame() {
        val data = ByteArray(9000) { (it % 251).toByte() }
        val chunks = CallMediaFraming.fragment(data)
        val hash = CallSignals.callIdHash16("x")
        val reassembler = CallMediaFraming.Reassembler()
        var out: ByteArray? = null
        for ((idx, chunk) in chunks.withIndex().reversed()) {
            val pkt = CallMediaFraming.Packet(
                hash, 5, 1, CallMediaFraming.TYPE_VIDEO_JPEG, 0, chunk, idx, chunks.size,
            )
            val decoded = CallMediaFraming.decode(pkt.encode())!!
            out = reassembler.feed(decoded) ?: out
        }
        assertTrue(out!!.contentEquals(data))
    }

    @Test
    fun reassemblerDropsExpiredFrames() {
        var now = 0L
        val reassembler = CallMediaFraming.Reassembler(timeoutMs = 1_500L, clock = { now })
        val hash = CallSignals.callIdHash16("x")
        val first = CallMediaFraming.Packet(hash, 1, 1, 3, 0, byteArrayOf(1), 0, 2)
        assertEquals(null, reassembler.feed(first))
        now = 5_000L
        val single = CallMediaFraming.Packet(hash, 2, 1, 3, 0, byteArrayOf(9), 0, 1)
        assertTrue(reassembler.feed(single)!!.contentEquals(byteArrayOf(9)))
        assertEquals(0, reassembler.pendingCount)
        assertEquals(1, reassembler.droppedFrames)
    }
}
