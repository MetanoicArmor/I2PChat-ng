package org.i2pchat.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityTest {
    private val hash = ByteArray(16) { 7 }

    private fun frameData(n: Int) = ByteArray(n) { ((it * 31) % 251).toByte() }

    private fun packetsFor(data: ByteArray, seq: Int = 1, type: Int = CallMediaFraming.TYPE_VIDEO_KEY):
        Pair<List<CallMediaFraming.Packet>, CallMediaFraming.Packet> {
        val chunks = CallMediaFraming.fragment(data)
        val pkts = chunks.mapIndexed { i, c ->
            CallMediaFraming.Packet(hash, seq, 1, type, 0, c, i, chunks.size)
        }
        val parity = CallMediaFraming.Packet(
            hash, seq, 1, CallMediaFraming.TYPE_PARITY, 0,
            CallMediaFraming.buildParityPayload(type, chunks),
        )
        return pkts to parity
    }

    private fun wire(p: CallMediaFraming.Packet) = CallMediaFraming.decode(p.encode())!!

    @Test
    fun recoveredFrameKeepsOriginalVideoType() {
        val (pkts, parity) = packetsFor(frameData(CallMediaFraming.MAX_PAYLOAD + 10))
        val wiredParity = wire(parity)
        assertEquals(CallMediaFraming.TYPE_PARITY, wiredParity.type)
        assertEquals(CallMediaFraming.TYPE_VIDEO_KEY, CallMediaFraming.completedType(wiredParity))
        assertEquals(CallMediaFraming.TYPE_VIDEO_KEY, CallMediaFraming.completedType(wire(pkts[0])))
    }

    @Test
    fun parityRecoversAnySingleLostFragment() {
        val data = frameData(CallMediaFraming.MAX_PAYLOAD * 2 + 777)
        val (pkts, parity) = packetsFor(data)
        assertEquals(3, pkts.size)
        for (lost in pkts.indices) {
            val r = CallMediaFraming.Reassembler()
            var out: ByteArray? = null
            for ((i, p) in pkts.withIndex()) if (i != lost) out = r.feed(wire(p)) ?: out
            assertNull(out)
            out = r.feed(wire(parity))
            assertNotNull("lost fragment $lost", out)
            assertArrayEquals(data, out)
            assertEquals(1, r.recoveredFrames)
            assertEquals(0, r.droppedFrames)
        }
    }

    @Test
    fun parityArrivingFirstStillRecovers() {
        val data = frameData(CallMediaFraming.MAX_PAYLOAD + 500)
        val (pkts, parity) = packetsFor(data)
        val r = CallMediaFraming.Reassembler()
        assertNull(r.feed(wire(parity)))
        assertEquals(0, r.droppedFrames)
        assertArrayEquals(data, r.feed(wire(pkts[1])))
        assertEquals(1, r.recoveredFrames)
    }

    @Test
    fun lateFragmentOrParityAfterCompletionIsNotCountedAsDrop() {
        val data = frameData(CallMediaFraming.MAX_PAYLOAD * 2 + 10)
        val (pkts, parity) = packetsFor(data)
        val r = CallMediaFraming.Reassembler()
        var out: ByteArray? = null
        for (p in pkts) out = r.feed(wire(p)) ?: out
        assertArrayEquals(data, out)
        assertNull(r.feed(wire(parity)))
        assertNull(r.feed(wire(pkts[0])))
        assertEquals(0, r.pendingCount)
        assertEquals(0, r.droppedFrames)
    }

    @Test
    fun twoLostFragmentsExpire() {
        var now = 0L
        val r = CallMediaFraming.Reassembler(timeoutMs = 1_500L, clock = { now })
        val (pkts, parity) = packetsFor(frameData(CallMediaFraming.MAX_PAYLOAD * 3 + 5))
        assertNull(r.feed(wire(pkts[0])))
        assertNull(r.feed(wire(parity)))
        now = 5_000L
        r.feed(CallMediaFraming.Packet(hash, 9, 1, CallMediaFraming.TYPE_VIDEO_KEY, 0, byteArrayOf(1)))
        assertEquals(1, r.droppedFrames)
        assertEquals(0, r.recoveredFrames)
    }

    @Test
    fun parityPacketFitsOneDatagram() {
        val chunks = CallMediaFraming.fragment(frameData(CallMediaFraming.MAX_PAYLOAD + 10))
        val pkt = CallMediaFraming.Packet(
            hash, 1, 0, CallMediaFraming.TYPE_PARITY, 0,
            CallMediaFraming.buildParityPayload(CallMediaFraming.TYPE_VIDEO_KEY, chunks),
        )
        assertTrue(pkt.encode().size <= CallMediaFraming.HEADER_SIZE + CallMediaFraming.MAX_PACKET_PAYLOAD)
        assertEquals(CallMediaFraming.TYPE_PARITY, wire(pkt).type)
    }

    @Test
    fun audioSeqTrackerFindsLossAndLatePackets() {
        val t = AudioSeqTracker()
        assertEquals(0, t.onPacket(10, true))
        assertEquals(0, t.onPacket(11, true))
        assertEquals(2, t.onPacket(14, true)) // 12, 13 lost
        assertEquals(-1, t.onPacket(13, true)) // late
        assertEquals(-1, t.onPacket(14, true)) // duplicate
        assertEquals(0, t.onPacket(15, true))
        assertEquals(0, t.onPacket(500, true)) // big gap is a pause, not loss
        assertEquals(0, t.onPacket(999, false)) // untracked peers are never concealed
    }

    @Test
    fun audioSeqTrackerWrapsAround() {
        val t = AudioSeqTracker()
        assertEquals(0, t.onPacket(-1, true))
        assertEquals(0, t.onPacket(0, true))
        assertEquals(1, t.onPacket(2, true))
    }

    @Test
    fun concealFramesFadeOut() {
        val last = ShortArray(4) { 1000 }
        val out = concealFrames(last, 3)
        assertEquals(3, out.size)
        assertTrue(out[0][0] > out[1][0] && out[1][0] > out[2][0])
        assertTrue(concealFrames(ShortArray(0), 2).isEmpty())
    }

    @Test
    fun playoutBufferPrebuffersAndGrowsAfterStarvation() {
        var now = 0L
        val b = PlayoutBuffer { now }
        val frame = ShortArray(CallAudioFormat.FRAME_SAMPLES)
        assertNull(b.pull(frame.size))
        // 120 ms = 6 frames before playback starts
        repeat(5) { b.push(frame) }
        assertNull(b.pull(frame.size))
        b.push(frame)
        assertNotNull(b.pull(frame.size))
        repeat(5) { assertNotNull(b.pull(frame.size)) }
        // starve for longer than the threshold
        now += 400
        assertNull(b.pull(frame.size))
        assertEquals(PlayoutBuffer.BASE_PREBUF_MS + PlayoutBuffer.STEP_UP_MS, b.prebufMs)
        // calm for a while: the buffer relaxes again
        repeat(8) { b.push(frame) }
        assertNotNull(b.pull(frame.size))
        now += PlayoutBuffer.CALM_MS + 1
        assertNotNull(b.pull(frame.size))
        assertEquals(PlayoutBuffer.BASE_PREBUF_MS + PlayoutBuffer.STEP_UP_MS - PlayoutBuffer.STEP_DOWN_MS, b.prebufMs)
    }

    @Test
    fun playoutBufferDropsOldestAboveCap() {
        val b = PlayoutBuffer { 0L }
        val big = ShortArray(CallAudioFormat.SAMPLE_RATE) { it.toShort() } // 1 s
        b.push(big)
        val first = b.pull(1)!![0]
        val capSamples = CallAudioFormat.SAMPLE_RATE * PlayoutBuffer.MAX_BUFFER_MS / 1000
        assertEquals((big.size - capSamples).toShort(), first)
    }

    @Test
    fun downsampleAveragesGroups() {
        val src = shortArrayOf(3, 3, 3, 6, 6, 6)
        assertArrayEquals(shortArrayOf(3, 6), downsampleTo16k(src, 48_000))
        assertTrue(src === downsampleTo16k(src, 16_000))
    }

    @Test
    fun pcm16RoundTrip() {
        val s = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(s, pcm16ToShorts(shortsToPcm16(s)))
    }

    @Test
    fun opusHeadLooksRight() {
        val h = OpusFrameDecoder.opusHead()
        assertEquals(19, h.size)
        assertEquals("OpusHead", String(h, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun rotatePlaneQuarterTurns() {
        // 2 wide x 3 tall
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(byteArrayOf(5, 3, 1, 6, 4, 2), Yuv.rotatePlane(src, 2, 3, 90))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), Yuv.rotatePlane(src, 2, 3, 180))
        assertArrayEquals(byteArrayOf(2, 4, 6, 1, 3, 5), Yuv.rotatePlane(src, 2, 3, 270))
        assertTrue(src === Yuv.rotatePlane(src, 2, 3, 0))
    }

    @Test
    fun fitEdgeKeepsAspectAndEvenSizes() {
        val f = I420Frame(640, 480, ByteArray(640 * 480 * 3 / 2))
        val s = Yuv.fitEdge(f, 320)
        assertEquals(320, s.w)
        assertEquals(240, s.h)
        assertEquals(320 * 240 * 3 / 2, s.data.size)
        assertTrue(f === Yuv.fitEdge(f, 800))
    }

    @Test
    fun rgbaWhiteAndBlackToI420() {
        val w = 4
        val h = 2
        val white = ByteArray(w * h * 4) { 0xFF.toByte() }
        val f = Yuv.rgbaToI420(white, w, h)
        assertEquals(235, f.data[0].toInt() and 0xff)
        assertEquals(128, f.data[w * h].toInt() and 0xff)
        val black = ByteArray(w * h * 4) { if (it % 4 == 3) 0xFF.toByte() else 0 }
        assertEquals(16, Yuv.rgbaToI420(black, w, h).data[0].toInt() and 0xff)
    }

    @Test
    fun adaptiveControllerStepsDownAndUp() {
        val c = AdaptiveController(EffectiveMedia(48, 600, 15, 720))
        c.observe(0, 0, 0, 0)
        val worse = c.observe(100, 10, 100, 10)
        assertEquals(1, worse.level)
        assertTrue(worse.videoKbps < 600)
        var e = worse
        repeat(3) { i -> e = c.observe(200L + i * 50, 10, 200L + i * 50, 10) }
        assertEquals(0, e.level)
        assertEquals(1, c.bump().level)
        assertEquals(2, c.bump().level)
    }

    @Test
    fun oversizedFragmentIsRejected() {
        val raw = ByteArray(CallMediaFraming.HEADER_SIZE + CallMediaFraming.MAX_PACKET_PAYLOAD + 1)
        raw[0] = 'I'.code.toByte(); raw[1] = '2'.code.toByte(); raw[2] = 'C'.code.toByte(); raw[3] = '2'.code.toByte()
        raw[CallMediaFraming.HEADER_SIZE - 1] = 1 // fragTotal
        assertNull(CallMediaFraming.decode(raw))
    }

    @Test
    fun vp8KeyframeOverTheEdgeIsRejected() {
        fun header(width: Int, height: Int, key: Boolean): ByteArray {
            val payload = ByteArray(10)
            payload[0] = (if (key) 0 else 1).toByte()
            payload[3] = 0x9d.toByte()
            payload[4] = 0x01.toByte()
            payload[5] = 0x2a.toByte()
            payload[6] = (width and 0xff).toByte()
            payload[7] = ((width shr 8) and 0x3f).toByte()
            payload[8] = (height and 0xff).toByte()
            payload[9] = ((height shr 8) and 0x3f).toByte()
            return payload
        }
        assertTrue(CallMediaFraming.vp8KeyframeRejected(header(2000, 200, true)))
        assertFalse(CallMediaFraming.vp8KeyframeRejected(header(640, 480, true)))
        assertFalse(CallMediaFraming.vp8KeyframeRejected(header(2000, 200, false)))
        assertFalse(CallMediaFraming.videoDimensionsAllowed(2000, 200))
        assertTrue(CallMediaFraming.videoDimensionsAllowed(640, 480))
    }
}
