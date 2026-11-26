package org.i2pchat.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream

private const val VIDEO_TAG = "CallVideo"
private const val VP8_MIME = "video/x-vnd.on2.vp8"

/** Planar YUV 4:2:0 frame (Y, then U, then V), even dimensions. */
class I420Frame(val w: Int, val h: Int, val data: ByteArray) {
    val id: Long = nextId.incrementAndGet()

    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicLong(0)
    }
}

object Yuv {
    /** Camera2/MediaCodec [Image] (YUV_420_888) -> upright I420, rotating clockwise by [rotation]. */
    fun imageToI420(img: Image, rotation: Int = 0): I420Frame {
        val w = img.width and 1.inv()
        val h = img.height and 1.inv()
        val planes = img.planes
        val y = readPlane(planes[0], w, h)
        val u = readPlane(planes[1], w / 2, h / 2)
        val v = readPlane(planes[2], w / 2, h / 2)
        return assemble(w, h, y, u, v, rotation)
    }

    /** RGBA_8888 [Image] (e.g. from a VirtualDisplay) -> I420. */
    fun rgbaImageToI420(img: Image): I420Frame {
        val p = img.planes[0]
        val w = img.width and 1.inv()
        val h = img.height and 1.inv()
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val row = ByteArray(rs.coerceAtLeast(w * ps))
        val rgba = ByteArray(w * h * 4)
        for (r in 0 until h) {
            buf.position(r * rs)
            val n = minOf(w * ps, buf.remaining())
            buf.get(row, 0, n)
            if (ps == 4) {
                System.arraycopy(row, 0, rgba, r * w * 4, w * 4)
            } else {
                for (c in 0 until w) System.arraycopy(row, c * ps, rgba, (r * w + c) * 4, 4)
            }
        }
        return rgbaToI420(rgba, w, h)
    }

    /** Tightly packed RGBA bytes -> I420 (BT.601, chroma from the top-left pixel of each 2x2). */
    fun rgbaToI420(rgba: ByteArray, w: Int, h: Int): I420Frame {
        val out = ByteArray(w * h * 3 / 2)
        val uOff = w * h
        val vOff = uOff + w * h / 4
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                val r = rgba[i].toInt() and 0xff
                val g = rgba[i + 1].toInt() and 0xff
                val b = rgba[i + 2].toInt() and 0xff
                out[y * w + x] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val ci = (y / 2) * (w / 2) + x / 2
                    out[uOff + ci] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                    out[vOff + ci] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                }
            }
        }
        return I420Frame(w, h, out)
    }

    private fun clamp(v: Int): Byte = v.coerceIn(0, 255).toByte()

    private fun readPlane(p: Image.Plane, w: Int, h: Int): ByteArray {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val out = ByteArray(w * h)
        if (ps == 1 && rs == w) {
            val dup = buf.duplicate()
            dup.position(0)
            dup.get(out, 0, minOf(out.size, dup.remaining()))
        } else if (ps == 1) {
            for (r in 0 until h) {
                val dup = buf.duplicate()
                dup.position(r * rs)
                dup.get(out, r * w, w)
            }
        } else {
            for (r in 0 until h) {
                for (c in 0 until w) out[r * w + c] = buf.get(r * rs + c * ps)
            }
        }
        return out
    }

    private fun assemble(w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray, rotation: Int): I420Frame {
        val rot = ((rotation % 360) + 360) % 360
        val ry = rotatePlane(y, w, h, rot)
        val ru = rotatePlane(u, w / 2, h / 2, rot)
        val rv = rotatePlane(v, w / 2, h / 2, rot)
        val ow = if (rot == 90 || rot == 270) h else w
        val oh = if (rot == 90 || rot == 270) w else h
        val out = ByteArray(ow * oh * 3 / 2)
        System.arraycopy(ry, 0, out, 0, ry.size)
        System.arraycopy(ru, 0, out, ry.size, ru.size)
        System.arraycopy(rv, 0, out, ry.size + ru.size, rv.size)
        return I420Frame(ow, oh, out)
    }

    /** Rotate a w x h plane clockwise by [rot] (0/90/180/270); 90/270 swap the dimensions. */
    fun rotatePlane(src: ByteArray, w: Int, h: Int, rot: Int): ByteArray {
        if (rot == 0) return src
        val dst = ByteArray(src.size)
        when (rot) {
            90 -> for (y in 0 until h) for (x in 0 until w) dst[x * h + (h - 1 - y)] = src[y * w + x]
            180 -> for (y in 0 until h) for (x in 0 until w) dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
            270 -> for (y in 0 until h) for (x in 0 until w) dst[(w - 1 - x) * h + y] = src[y * w + x]
            else -> return src
        }
        return dst
    }

    /** Nearest-neighbour resize to even [nw] x [nh]. */
    fun scale(f: I420Frame, nw: Int, nh: Int): I420Frame {
        if (nw == f.w && nh == f.h) return f
        val out = ByteArray(nw * nh * 3 / 2)
        scalePlane(f.data, 0, f.w, f.h, out, 0, nw, nh)
        scalePlane(f.data, f.w * f.h, f.w / 2, f.h / 2, out, nw * nh, nw / 2, nh / 2)
        scalePlane(
            f.data, f.w * f.h + f.w * f.h / 4, f.w / 2, f.h / 2,
            out, nw * nh + nw * nh / 4, nw / 2, nh / 2,
        )
        return I420Frame(nw, nh, out)
    }

    private fun scalePlane(
        src: ByteArray, so: Int, sw: Int, sh: Int,
        dst: ByteArray, dof: Int, dw: Int, dh: Int,
    ) {
        for (y in 0 until dh) {
            val sy = y * sh / dh
            for (x in 0 until dw) dst[dof + y * dw + x] = src[so + sy * sw + x * sw / dw]
        }
    }

    /** Shrink so the longer side is at most [edge] (keeps aspect, even sizes, never upscales). */
    fun fitEdge(f: I420Frame, edge: Int): I420Frame {
        val longest = maxOf(f.w, f.h)
        if (longest <= edge) return f
        val ratio = edge.toDouble() / longest
        val nw = ((f.w * ratio).toInt() and 1.inv()).coerceAtLeast(16)
        val nh = ((f.h * ratio).toInt() and 1.inv()).coerceAtLeast(16)
        return scale(f, nw, nh)
    }

    fun toNv21(f: I420Frame): ByteArray {
        val ySize = f.w * f.h
        val out = ByteArray(f.data.size)
        System.arraycopy(f.data, 0, out, 0, ySize)
        val uOff = ySize
        val vOff = ySize + ySize / 4
        var o = ySize
        for (i in 0 until ySize / 4) {
            out[o++] = f.data[vOff + i]
            out[o++] = f.data[uOff + i]
        }
        return out
    }

    fun toJpeg(f: I420Frame, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(f.data.size / 8)
        YuvImage(toNv21(f), ImageFormat.NV21, f.w, f.h, null)
            .compressToJpeg(Rect(0, 0, f.w, f.h), quality.coerceIn(10, 95), out)
        return out.toByteArray()
    }

    /** Smallest quality-first JPEG that fits [budget] bytes; shrinks the picture if quality alone is not enough. */
    fun toJpegWithin(f: I420Frame, budget: Int, startQuality: Int): ByteArray? {
        var frame = f
        var q = startQuality.coerceIn(25, 80)
        repeat(8) {
            val jpg = toJpeg(frame, q)
            if (jpg.size <= budget) return jpg
            if (q > 35) {
                q -= 12
            } else {
                val nw = (frame.w * 3 / 4) and 1.inv()
                val nh = (frame.h * 3 / 4) and 1.inv()
                if (nw < 64 || nh < 64) return null
                frame = scale(frame, nw, nh)
            }
        }
        return null
    }

    /** I420 -> ARGB bitmap, sampling every [step] pixels (UI preview / remote picture). */
    fun toBitmap(f: I420Frame, step: Int = 1): Bitmap {
        val ow = f.w / step
        val oh = f.h / step
        val px = IntArray(ow * oh)
        val uOff = f.w * f.h
        val vOff = uOff + f.w * f.h / 4
        val cw = f.w / 2
        for (y in 0 until oh) {
            val sy = y * step
            for (x in 0 until ow) {
                val sx = x * step
                val yy = (f.data[sy * f.w + sx].toInt() and 0xff) - 16
                val ci = (sy / 2) * cw + sx / 2
                val u = (f.data[uOff + ci].toInt() and 0xff) - 128
                val v = (f.data[vOff + ci].toInt() and 0xff) - 128
                val c = 298 * yy
                val r = ((c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * u + 128) shr 8).coerceIn(0, 255)
                px[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }
}

/** VP8 encoder over [MediaCodec] fed through input [Image]s (works with any flexible layout). */
class Vp8FrameEncoder(val width: Int, val height: Int, bitrateKbps: Int, fps: Int) {
    private val codec: MediaCodec? = runCatching {
        val c = MediaCodec.createEncoderByType(VP8_MIME)
        val fmt = MediaFormat.createVideoFormat(VP8_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(50, 1500) * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(3, 30))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        c
    }.onFailure { Log.w(VIDEO_TAG, "VP8 encoder unavailable", it) }.getOrNull()

    private var ptsUs = 0L

    val available: Boolean get() = codec != null

    fun setBitrate(kbps: Int) {
        val c = codec ?: return
        runCatching {
            val b = android.os.Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps.coerceIn(50, 1500) * 1000)
            c.setParameters(b)
        }
    }

    /** Encode [frame] (must be [width] x [height]); returns (payload, isKeyframe) pairs ready now. */
    @Synchronized
    fun encode(frame: I420Frame, forceKey: Boolean): List<Pair<ByteArray, Boolean>> {
        val c = codec ?: return emptyList()
        return runCatching {
            if (forceKey) {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                c.setParameters(params)
            }
            val inIx = c.dequeueInputBuffer(IN_TIMEOUT_US)
            if (inIx < 0) return@runCatching emptyList<Pair<ByteArray, Boolean>>()
            val image = c.getInputImage(inIx)
            val size = frame.data.size
            if (image != null) {
                writeToImage(image, frame)
            } else {
                val buf = c.getInputBuffer(inIx) ?: return@runCatching emptyList<Pair<ByteArray, Boolean>>()
                buf.clear()
                buf.put(frame.data, 0, minOf(size, buf.capacity()))
            }
            c.queueInputBuffer(inIx, 0, size, ptsUs, 0)
            ptsUs += 33_333L
            val out = ArrayList<Pair<ByteArray, Boolean>>(1)
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
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    out.add(bytes to ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                }
                c.releaseOutputBuffer(ix, false)
                timeout = 0L
            }
            out
        }.getOrElse {
            Log.d(VIDEO_TAG, "vp8 encode failed", it)
            emptyList()
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    private fun writeToImage(img: Image, f: I420Frame) {
        copyPlane(img.planes[0], f.data, 0, f.w, f.h)
        copyPlane(img.planes[1], f.data, f.w * f.h, f.w / 2, f.h / 2)
        copyPlane(img.planes[2], f.data, f.w * f.h + f.w * f.h / 4, f.w / 2, f.h / 2)
    }

    private fun copyPlane(p: Image.Plane, src: ByteArray, off: Int, w: Int, h: Int) {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        if (ps == 1) {
            for (r in 0 until h) {
                buf.position(r * rs)
                buf.put(src, off + r * w, w)
            }
        } else {
            for (r in 0 until h) for (c in 0 until w) buf.put(r * rs + c * ps, src[off + r * w + c])
        }
    }

    companion object {
        private const val IN_TIMEOUT_US = 4_000L
        private const val OUT_TIMEOUT_US = 15_000L

        fun isSupported(): Boolean = runCatching {
            MediaCodec.createEncoderByType(VP8_MIME).release()
            true
        }.getOrDefault(false)
    }
}

/** VP8 decoder over [MediaCodec]; returns I420 frames. Needs a keyframe first. */
class Vp8FrameDecoder {
    private val codec: MediaCodec? = runCatching {
        val c = MediaCodec.createDecoderByType(VP8_MIME)
        c.configure(MediaFormat.createVideoFormat(VP8_MIME, 320, 240), null, null, 0)
        c.start()
        c
    }.onFailure { Log.w(VIDEO_TAG, "VP8 decoder unavailable", it) }.getOrNull()

    private var ptsUs = 0L

    val available: Boolean get() = codec != null

    @Synchronized
    fun decode(payload: ByteArray): I420Frame? {
        val c = codec ?: return null
        if (CallMediaFraming.vp8KeyframeRejected(payload)) return null
        return runCatching {
            val inIx = c.dequeueInputBuffer(IN_TIMEOUT_US)
            if (inIx < 0) return@runCatching null
            val inBuf = c.getInputBuffer(inIx) ?: return@runCatching null
            inBuf.clear()
            inBuf.put(payload)
            c.queueInputBuffer(inIx, 0, payload.size, ptsUs, 0)
            ptsUs += 33_333L
            val info = MediaCodec.BufferInfo()
            var frame: I420Frame? = null
            var timeout = OUT_TIMEOUT_US
            while (true) {
                val ix = c.dequeueOutputBuffer(info, timeout)
                if (ix < 0) {
                    if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    break
                }
                val img = c.getOutputImage(ix)
                if (img != null && info.size > 0) {
                    if (!CallMediaFraming.videoDimensionsAllowed(img.width, img.height)) {
                        img.close()
                        c.releaseOutputBuffer(ix, false)
                        return@runCatching null
                    }
                    frame = runCatching { Yuv.imageToI420(img) }.getOrNull() ?: frame
                }
                img?.close()
                c.releaseOutputBuffer(ix, false)
                timeout = 0L
            }
            frame
        }.getOrElse {
            Log.d(VIDEO_TAG, "vp8 decode failed", it)
            null
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    companion object {
        private const val IN_TIMEOUT_US = 4_000L
        private const val OUT_TIMEOUT_US = 20_000L

        fun isSupported(): Boolean = runCatching {
            MediaCodec.createDecoderByType(VP8_MIME).release()
            true
        }.getOrDefault(false)
    }
}

/** Effective (possibly reduced) encode settings, mirrors the desktop `EffectiveMedia`. */
data class EffectiveMedia(
    val audioKbps: Int,
    val videoKbps: Int,
    val fps: Int,
    val edge: Int,
    val jpegQuality: Int = 55,
    val level: Int = 0,
)

/** Moves the effective quality down on loss and back up when the path is clean. */
class AdaptiveController(ceiling: EffectiveMedia) {
    var ceiling: EffectiveMedia = ceiling
        private set
    var effective: EffectiveMedia = ceiling.copy(level = 0)
        private set

    private var prevTx = -1L
    private var prevTxDrop = 0L
    private var prevRx = 0L
    private var prevRxDrop = 0L
    private var goodStreak = 0

    fun reset(newCeiling: EffectiveMedia) {
        ceiling = newCeiling
        apply(effective.level)
    }

    /** Step down one level right away (e.g. the peer keeps asking for keyframes). */
    fun bump(): EffectiveMedia {
        apply((effective.level + 1).coerceAtMost(MAX_LEVEL))
        return effective
    }

    fun observe(tx: Long, txDrop: Long, rx: Long, rxDrop: Long): EffectiveMedia {
        if (prevTx < 0) {
            prevTx = tx; prevTxDrop = txDrop; prevRx = rx; prevRxDrop = rxDrop
            return effective
        }
        val dTx = (tx - prevTx).coerceAtLeast(0)
        val dTxDrop = (txDrop - prevTxDrop).coerceAtLeast(0)
        val dRx = (rx - prevRx).coerceAtLeast(0)
        val dRxDrop = (rxDrop - prevRxDrop).coerceAtLeast(0)
        prevTx = tx; prevTxDrop = txDrop; prevRx = rx; prevRxDrop = rxDrop

        val total = dTx + dRx
        val ratio = if (total > 0) (dTxDrop + dRxDrop).toDouble() / total else 0.0
        // Zero RX before the first packet means the LeaseSet is not ready, not loss.
        val starving = rx > 0 && dTx >= 8 && dRx == 0L
        val bad = starving || dTxDrop >= 2 || dRxDrop >= 2 || ratio >= 0.08
        val good = !bad && dTxDrop == 0L && dRxDrop == 0L && (dRx > 0 || dTx < 4)
        if (bad) {
            goodStreak = 0
            apply((effective.level + 1).coerceAtMost(MAX_LEVEL))
        } else if (good) {
            goodStreak += 1
            if (goodStreak >= 3) {
                goodStreak = 0
                apply((effective.level - 1).coerceAtLeast(0))
            }
        } else {
            goodStreak = 0
        }
        return effective
    }

    private fun apply(level: Int) {
        val l = level.coerceIn(0, MAX_LEVEL)
        val c = ceiling
        val factor = maxOf(0.35, 1.0 - 0.11 * l)
        val stepEdge = EDGE_STEPS[minOf(l, EDGE_STEPS.size - 1)]
        val edge = if (c.edge >= stepEdge) stepEdge else maxOf(160, (c.edge * factor).toInt())
        effective = EffectiveMedia(
            audioKbps = c.audioKbps,
            videoKbps = minOf(c.videoKbps, maxOf(50, (c.videoKbps * factor).toInt())),
            fps = minOf(c.fps, maxOf(3, (c.fps * factor + 0.5).toInt())),
            edge = minOf(c.edge, edge),
            jpegQuality = maxOf(25, minOf(70, c.jpegQuality - 5 * l)),
            level = l,
        )
    }

    companion object {
        const val MAX_LEVEL = 6
        private val EDGE_STEPS = intArrayOf(720, 640, 480, 400, 320, 240, 160)
    }
}
