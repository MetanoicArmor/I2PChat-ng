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
    fun fragmentFlags() {
        val parts = CallMediaFraming.fragment(ByteArray(10) { it.toByte() }, maxChunk = 4)
        assertEquals(3, parts.size)
        assertTrue(parts[0].second and CallMediaFraming.FLAG_FRAG_MORE != 0)
        assertEquals(0, parts[2].second)
    }
}
