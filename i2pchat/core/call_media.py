"""Capture/encode helpers for call media (PCM/Opus audio + JPEG video)."""

from __future__ import annotations

import io
import logging
import struct
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

SAMPLE_RATE = 16000
CHANNELS = 1
FRAME_MS = 20
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000


class AudioCodec:
    """Prefer Opus; fall back to raw PCM16 LE."""

    def __init__(self, bitrate_bps: int = 32_000) -> None:
        self.bitrate_bps = max(12_000, min(64_000, int(bitrate_bps)))
        self._enc = None
        self._dec = None
        self.opus = False
        try:
            import opuslib  # type: ignore

            self._enc = opuslib.Encoder(SAMPLE_RATE, CHANNELS, opuslib.APPLICATION_VOIP)
            self._enc.bitrate = self.bitrate_bps
            self._dec = opuslib.Decoder(SAMPLE_RATE, CHANNELS)
            self.opus = True
        except Exception:
            logger.info("opuslib unavailable; call audio uses PCM16 fallback")

    def encode(self, pcm16: bytes) -> Tuple[bytes, bool]:
        if self._enc is not None and self.opus:
            try:
                return self._enc.encode(pcm16, SAMPLES_PER_FRAME), True
            except Exception:
                logger.debug("opus encode failed", exc_info=True)
        return pcm16, False

    def decode(self, payload: bytes, *, opus: bool) -> bytes:
        if opus and self._dec is not None:
            try:
                return self._dec.decode(payload, SAMPLES_PER_FRAME)
            except Exception:
                logger.debug("opus decode failed", exc_info=True)
                return b""
        return payload


def jpeg_from_qimage(qimage, *, max_edge: int = 480, quality: int = 55) -> bytes:
    """Encode a QImage to JPEG bytes (Qt must be available)."""
    from PyQt6 import QtCore, QtGui

    img = qimage
    if img is None or img.isNull():
        return b""
    w, h = img.width(), img.height()
    edge = max(w, h)
    if edge > max_edge and edge > 0:
        scale = max_edge / float(edge)
        img = img.scaled(
            max(1, int(w * scale)),
            max(1, int(h * scale)),
            QtCore.Qt.AspectRatioMode.KeepAspectRatio,
            QtCore.Qt.TransformationMode.SmoothTransformation,
        )
    buf = QtCore.QBuffer()
    buf.open(QtCore.QIODevice.OpenModeFlag.WriteOnly)
    img.save(buf, "JPG", quality)
    return bytes(buf.data())


def qimage_from_jpeg(data: bytes):
    from PyQt6 import QtGui

    img = QtGui.QImage.fromData(data, "JPG")
    return img if not img.isNull() else None


def pcm16_silence(frames: int = SAMPLES_PER_FRAME) -> bytes:
    return b"\x00\x00" * frames


def mix_gain_pcm16(pcm: bytes, gain: float) -> bytes:
    if gain >= 0.99:
        return pcm
    out = bytearray()
    for i in range(0, len(pcm) - 1, 2):
        sample = struct.unpack_from("<h", pcm, i)[0]
        sample = int(max(-32768, min(32767, sample * gain)))
        out += struct.pack("<h", sample)
    return bytes(out)
