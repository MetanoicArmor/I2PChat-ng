package org.i2pchat.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Round-trips through the real device codecs used by calls. */
@RunWith(AndroidJUnit4::class)
class CallCodecInstrumentedTest {
    @Test
    fun opusEncodeDecodeRoundTrip() {
        assumeTrue(OpusFrameEncoder.isSupported() && OpusFrameDecoder.isSupported())
        val enc = OpusFrameEncoder(32_000)
        val dec = OpusFrameDecoder()
        val frame = CallAudioFormat.FRAME_SAMPLES
        val packets = ArrayList<ByteArray>()
        var t = 0
        repeat(100) {
            val pcm = ShortArray(frame) { i -> (sin(2 * PI * 440 * (t + i) / 16000.0) * 8000).toInt().toShort() }
            t += frame
            packets += enc.encode(pcm)
        }
        // the encoder may lag a couple of frames, but must produce most of them
        assertTrue("packets=${packets.size}", packets.size >= 90)
        assertTrue(packets.all { it.size in 3..400 })
        var samples = 0
        var peak = 0
        for (p in packets) {
            val out = dec.decode(p)
            samples += out.size
            for (s in out) peak = maxOf(peak, abs(s.toInt()))
        }
        // flush: the decoder may hold a frame or two
        repeat(3) { samples += dec.decode(packets.last()).size }
        assertTrue("samples=$samples", samples >= 90 * frame)
        assertTrue("peak=$peak", peak > 2000)
        enc.release()
        dec.release()
    }

    @Test
    fun vp8EncodeDecodeRoundTrip() {
        assumeTrue(Vp8FrameEncoder.isSupported() && Vp8FrameDecoder.isSupported())
        val w = 320
        val h = 240
        val enc = Vp8FrameEncoder(w, h, 400, 15)
        val dec = Vp8FrameDecoder()
        var gotKey = false
        var decoded: I420Frame? = null
        repeat(40) { n ->
            val data = ByteArray(w * h * 3 / 2)
            for (y in 0 until h) for (x in 0 until w) data[y * w + x] = ((x + y + n * 3) % 220 + 20).toByte()
            for (i in w * h until data.size) data[i] = 128.toByte()
            for ((payload, key) in enc.encode(I420Frame(w, h, data), forceKey = n == 0)) {
                if (key) gotKey = true
                assertTrue(payload.isNotEmpty())
                if (gotKey) dec.decode(payload)?.let { decoded = it }
            }
        }
        assertTrue("no keyframe", gotKey)
        val f = decoded
        assertTrue("nothing decoded", f != null)
        assertEquals(w, f!!.w)
        assertEquals(h, f.h)
        val bmp = Yuv.toBitmap(f)
        assertEquals(w, bmp.width)
        // mid-luma gradient should survive (not all black / all white)
        val mid = f.data[(h / 2) * w + w / 2].toInt() and 0xff
        assertTrue("luma=$mid", mid in 30..235)
        enc.release()
        dec.release()
    }

    @Test
    fun jpegWithinBudget() {
        val w = 640
        val h = 480
        val data = ByteArray(w * h * 3 / 2) { (it * 7 % 251).toByte() }
        val jpg = Yuv.toJpegWithin(I420Frame(w, h, data), CallMediaFraming.MAX_PAYLOAD * 4, 55)
        assertTrue(jpg != null && jpg.size <= CallMediaFraming.MAX_PAYLOAD * 4)
    }
}

/** Camera2 -> I420 -> sender -> VP8 -> decoder, on the real device camera stack. */
@RunWith(AndroidJUnit4::class)
class CallCameraPipelineInstrumentedTest {
    @Test
    fun cameraFramesFlowThroughSenderAndDecode() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
        val got = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        val previews = java.util.concurrent.atomic.AtomicInteger(0)
        val sender = CallVideoSender(
            effective = { EffectiveMedia(32, 300, 10, 480) },
            useVp8 = { Vp8FrameEncoder.isSupported() },
            emit = { type, payload -> got.add(type to payload) },
            onPreview = { previews.incrementAndGet() },
            onOversize = {},
        )
        val cam = CallCamera(ctx) { sender.submit(it) }
        cam.minIntervalMs = 100
        sender.start()
        assertTrue("camera did not start", cam.start(480))
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && got.size < 8) Thread.sleep(100)
        cam.stop()
        sender.stop()
        assertTrue("frames=${got.size}", got.size >= 5)
        assertTrue("previews=${previews.get()}", previews.get() >= 1)
        val types = got.map { it.first }
        assertTrue(types.first() in listOf(CallMediaFraming.TYPE_VIDEO_KEY, CallMediaFraming.TYPE_VIDEO_JPEG))
        if (types.first() == CallMediaFraming.TYPE_VIDEO_KEY) {
            val dec = Vp8FrameDecoder()
            var decoded = 0
            for ((_, p) in got) if (dec.decode(p) != null) decoded++
            assertTrue("decoded=$decoded", decoded >= 3)
            dec.release()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CallServiceInstrumentedTest {
    @Test
    fun callForegroundServiceStartsWithMicrophoneType() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        NotificationHelper.init(ctx)
        CallForegroundService.sync(ctx)
        Thread.sleep(1500)
        @Suppress("DEPRECATION")
        val running = (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningServices(100).any { it.service.className.endsWith("CallForegroundService") && it.foreground }
        CallForegroundService.stop(ctx)
        assertTrue("service not in foreground", running)
    }
}
