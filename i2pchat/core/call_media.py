"""Capture/encode helpers for call media (PCM/Opus audio + JPEG video)."""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path
from typing import Optional, Tuple

from i2pchat.core.call_adapt import MAX_FRAME_BYTES, target_frame_bytes

logger = logging.getLogger(__name__)

SAMPLE_RATE = 16000
CHANNELS = 1
FRAME_MS = 20
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000


def _bundled_codec_dir() -> Optional[Path]:
    """Directory with vendor codec dylibs/so/dll next to the frozen app or repo."""
    plat = _codec_plat_subdir()
    candidates: list[Path] = []
    if getattr(sys, "frozen", False):
        exe = Path(sys.executable).resolve()
        # macOS .app: MacOS/<exe>, deps in Contents/Frameworks (ex-_internal).
        candidates.append(exe.parent / "vendor" / "codecs" / plat)
        candidates.append(exe.parent / "vendor" / "codecs")
        frameworks = exe.parent.parent / "Frameworks"
        candidates.append(frameworks / "vendor" / "codecs" / plat)
        candidates.append(frameworks / "vendor" / "codecs")
        candidates.append(frameworks)
        candidates.append(exe.parent)
    here = Path(__file__).resolve()
    # i2pchat/core/call_media.py → repo root vendor/codecs/<plat>
    repo = here.parents[2]
    if plat:
        candidates.append(repo / "vendor" / "codecs" / plat)
    for c in candidates:
        if c.is_dir() and any(
            p.suffix in {".dylib", ".so", ".dll"} or p.name.startswith("libopus")
            for p in c.iterdir()
            if p.is_file() or p.is_symlink()
        ):
            return c
        # Accept empty platform dirs only as last resort when they contain libs above.
    for c in candidates:
        if c.is_dir():
            return c
    return None


def _codec_plat_subdir() -> str:
    if sys.platform == "darwin":
        import platform

        return "darwin-x64" if platform.machine() == "x86_64" else "darwin-arm64"
    if sys.platform == "win32":
        return "win-x64"
    import platform

    mach = platform.machine()
    if mach in ("aarch64", "arm64"):
        return "linux-aarch64"
    return "linux-x86_64"


def preload_bundled_opus() -> None:
    """Load libopus from vendor/codecs before opuslib imports it.

    ``opuslib`` resolves the shared library via ``ctypes.util.find_library('opus')``,
    so we both ``CDLL``-load the bundled file and patch ``find_library`` to return
    its absolute path.
    """
    root = _bundled_codec_dir()
    if root is None:
        return
    names = (
        "libopus.0.dylib",
        "libopus.dylib",
        "libopus.so.0",
        "libopus.so",
        "opus.dll",
        "libopus-0.dll",
    )
    import ctypes
    import ctypes.util

    chosen: Optional[Path] = None
    for name in names:
        path = root / name
        if path.is_file():
            chosen = path
            break
    if chosen is None:
        return
    try:
        ctypes.CDLL(str(chosen))
    except OSError:
        logger.debug("failed loading %s", chosen, exc_info=True)
        return
    os.environ.setdefault("OPUS_BUNDLE_PATH", str(chosen))
    _orig_find = ctypes.util.find_library

    def _find_library(name: str):  # type: ignore[no-untyped-def]
        if name in ("opus", "libopus"):
            return str(chosen)
        return _orig_find(name)

    ctypes.util.find_library = _find_library  # type: ignore[assignment]
    logger.info("loaded bundled opus: %s", chosen)

class AudioCodec:
    """Prefer Opus when ``opuslib`` + libopus are present; otherwise raw PCM16 LE.

    ``opuslib`` is intentionally not a hard dependency (it needs a native libopus
    that may be bundled under vendor/codecs), so PCM16 is the fallback transport.
    """

    def __init__(self, bitrate_bps: int = 32_000) -> None:
        self.bitrate_bps = max(12_000, min(64_000, int(bitrate_bps)))
        self._enc = None
        self._dec = None
        self.opus = False
        preload_bundled_opus()
        try:
            import opuslib  # type: ignore

            self._enc = opuslib.Encoder(SAMPLE_RATE, CHANNELS, opuslib.APPLICATION_VOIP)
            self._dec = opuslib.Decoder(SAMPLE_RATE, CHANNELS)
            try:
                self._enc.bitrate = self.bitrate_bps
            except Exception:
                # Some libopus/opuslib builds reject the property setter; encode still works.
                logger.debug("opus bitrate ctl failed; using encoder default", exc_info=True)
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
        if opus:
            # Peer sent Opus but we have no decoder: raw bytes would play as noise.
            return b""
        return payload


def jpeg_from_qimage(
    qimage,
    *,
    max_edge: int = 320,
    quality: int = 40,
    target_bytes: Optional[int] = None,
) -> bytes:
    """Encode a QImage to JPEG bytes (Qt must be available).

    When ``target_bytes`` is set, quality is walked so the payload stays near the
    video bitrate budget (and under ``MAX_FRAME_BYTES``).
    """
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

    def _encode(q: int) -> bytes:
        buf = QtCore.QBuffer()
        buf.open(QtCore.QIODevice.OpenModeFlag.WriteOnly)
        img.save(buf, "JPG", max(1, min(95, int(q))))
        return bytes(buf.data())

    if target_bytes is None:
        return _encode(quality)

    budget = max(400, min(MAX_FRAME_BYTES, int(target_bytes)))
    # Walk quality downward from a high start so we meet the budget.
    q = max(25, min(70, int(quality)))
    data = _encode(q)
    if len(data) <= budget:
        # Try a bit higher quality while still under budget.
        for trial in (q + 5, q + 10, q + 15):
            if trial > 70:
                break
            cand = _encode(trial)
            if len(cand) <= budget:
                data, q = cand, trial
            else:
                break
        return data
    for trial in range(q - 5, 24, -5):
        data = _encode(trial)
        if len(data) <= budget:
            return data
    # Still too big: shrink edge once and re-encode at quality 30.
    if img.width() > 160 or img.height() > 160:
        img = img.scaled(
            max(1, img.width() // 2),
            max(1, img.height() // 2),
            QtCore.Qt.AspectRatioMode.KeepAspectRatio,
            QtCore.Qt.TransformationMode.SmoothTransformation,
        )
        data = _encode(30)
    return data


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
        sample = int.from_bytes(pcm[i : i + 2], "little", signed=True)
        sample = max(-32768, min(32767, int(sample * gain)))
        out += sample.to_bytes(2, "little", signed=True)
    return bytes(out)
