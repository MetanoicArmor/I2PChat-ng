"""VP8 encode/decode for call video (soft-deps on PyAV / libvpx via FFmpeg).

When unavailable, callers must fall back to JPEG (``TYPE_VIDEO_JPEG``).
"""

from __future__ import annotations

import logging
import time
from fractions import Fraction
from typing import Optional, Tuple

logger = logging.getLogger(__name__)


_VP8_AVAIL: Optional[bool] = None


def vp8_available() -> bool:
    global _VP8_AVAIL
    if _VP8_AVAIL is not None:
        return _VP8_AVAIL
    try:
        import av

        ctx = av.CodecContext.create("libvpx", "w")
        try:
            ctx.close()
        except Exception:
            pass
        _VP8_AVAIL = True
    except Exception:
        _VP8_AVAIL = False
    return _VP8_AVAIL

class Vp8Encoder:
    """Encode RGB24 frames to raw VP8 bitstream packets."""

    def __init__(
        self,
        *,
        width: int,
        height: int,
        fps: int = 8,
        bitrate_kbps: int = 250,
        keyframe_interval_s: float = 1.5,
    ) -> None:
        import av

        self.width = max(16, int(width) & ~1)
        self.height = max(16, int(height) & ~1)
        self.fps = max(1, int(fps))
        self.bitrate = max(50_000, int(bitrate_kbps) * 1000)
        # I2P DATAGRAM loses fragments; a short GOP recovers without a PLI, but
        # not so short that every other frame is a large keyframe.
        self.keyframe_interval_s = max(0.5, float(keyframe_interval_s))
        self._last_key = 0.0
        self._pts = 0
        self._codec = av.CodecContext.create("libvpx", "w")
        self._codec.width = self.width
        self._codec.height = self.height
        self._codec.pix_fmt = "yuv420p"
        self._codec.time_base = Fraction(1, self.fps)
        self._codec.bit_rate = self.bitrate
        # lag-in-frames=0: one packet per frame (no delayed alt-ref).
        # max-intra-rate is a percent of the average frame. 80 keeps a keyframe
        # near one or two DATAGRAMs; the first frame still often ignores it.
        self._codec.options = {
            "quality": "realtime",
            "cpu-used": "8",
            "deadline": "realtime",
            "error-resilient": "1",
            "lag-in-frames": "0",
            "max-intra-rate": "80",
        }
        self._codec.open()

    def reconfigure(
        self,
        *,
        width: Optional[int] = None,
        height: Optional[int] = None,
        fps: Optional[int] = None,
        bitrate_kbps: Optional[int] = None,
    ) -> None:
        w = max(16, int(width if width is not None else self.width) & ~1)
        h = max(16, int(height if height is not None else self.height) & ~1)
        f = max(1, int(fps if fps is not None else self.fps))
        br = max(50, int(bitrate_kbps if bitrate_kbps is not None else self.bitrate // 1000))
        if w == self.width and h == self.height and f == self.fps and br == self.bitrate // 1000:
            return
        interval = self.keyframe_interval_s
        try:
            self._codec.close()
        except Exception:
            pass
        self.__init__(
            width=w,
            height=h,
            fps=f,
            bitrate_kbps=br,
            keyframe_interval_s=interval,
        )

    def encode_rgb24(
        self, rgb: bytes, *, width: int, height: int, force_key: bool = False
    ) -> Tuple[bytes, bool]:
        """Return (payload, is_keyframe). Empty payload means drop."""
        import av
        from av.video.frame import VideoFrame

        now = time.monotonic()
        need_key = force_key or (now - self._last_key >= self.keyframe_interval_s)
        w = max(16, int(width) & ~1)
        h = max(16, int(height) & ~1)
        if w != self.width or h != self.height:
            self.reconfigure(width=w, height=h)

        expected = width * height * 3
        if len(rgb) < expected:
            rgb = rgb + b"\x00" * (expected - len(rgb))
        src = VideoFrame(width, height, "rgb24")
        src.planes[0].update(rgb[:expected])
        frame = src.reformat(width=self.width, height=self.height, format="yuv420p")
        frame.pts = self._pts
        self._pts += 1
        if need_key:
            try:
                frame.pict_type = "I"
            except Exception:
                pass
        packets = self._codec.encode(frame)
        if not packets:
            return b"", False
        data = b"".join(bytes(p) for p in packets)
        is_key = need_key or any(bool(getattr(p, "is_keyframe", False)) for p in packets)
        if is_key:
            self._last_key = now
        return data, bool(is_key)

    def close(self) -> None:
        try:
            self._codec.close()
        except Exception:
            pass


class Vp8Decoder:
    def __init__(self) -> None:
        import av

        self._codec = av.CodecContext.create("libvpx", "r")
        self._codec.open()

    def decode(self, payload: bytes) -> Optional[Tuple[bytes, int, int]]:
        """Return (rgb24, width, height) or None."""
        import av

        if not payload:
            return None
        try:
            packet = av.Packet(payload)
            frames = self._codec.decode(packet)
        except Exception:
            logger.debug("vp8 decode failed", exc_info=True)
            return None
        if not frames:
            return None
        frame = frames[-1].reformat(format="rgb24")
        w, h = frame.width, frame.height
        # plane 0 holds tightly packed rgb24 for this format in PyAV
        data = bytes(frame.planes[0])
        need = w * h * 3
        if len(data) < need:
            return None
        return data[:need], int(w), int(h)

    def close(self) -> None:
        try:
            self._codec.close()
        except Exception:
            pass


def qimage_to_rgb24(qimage) -> Tuple[bytes, int, int]:
    from PyQt6 import QtGui

    img = qimage.convertToFormat(QtGui.QImage.Format.Format_RGB888)
    w, h = img.width(), img.height()
    bpl = img.bytesPerLine()
    ptr = img.bits()
    ptr.setsize(img.sizeInBytes())
    raw = bytes(ptr)
    if bpl == w * 3:
        return raw[: w * h * 3], w, h
    rows = [raw[y * bpl : y * bpl + w * 3] for y in range(h)]
    return b"".join(rows), w, h


def rgb24_to_qimage(rgb: bytes, width: int, height: int):
    from PyQt6 import QtGui

    need = width * height * 3
    if len(rgb) < need or width <= 0 or height <= 0:
        return None
    img = QtGui.QImage(
        rgb,
        width,
        height,
        width * 3,
        QtGui.QImage.Format.Format_RGB888,
    )
    return img.copy() if not img.isNull() else None
