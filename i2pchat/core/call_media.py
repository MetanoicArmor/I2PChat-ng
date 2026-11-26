"""Capture/encode helpers for call media (PCM/Opus audio + JPEG video)."""

from __future__ import annotations

import functools
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

@functools.lru_cache(maxsize=1)
def opus_available() -> bool:
    """True when ``opuslib`` + libopus can create a decoder (what we advertise to peers)."""
    try:
        preload_bundled_opus()
        import opuslib  # type: ignore

        opuslib.Decoder(SAMPLE_RATE, CHANNELS)
        return True
    except Exception:
        return False


class AudioCodec:
    """Prefer Opus when ``opuslib`` + libopus are present; otherwise raw PCM16 LE.

    ``opuslib`` is intentionally not a hard dependency (it needs a native libopus
    that may be bundled under vendor/codecs), so PCM16 is the fallback transport.
    """

    # Max consecutive lost frames we try to conceal; a larger gap is a pause/restart.
    MAX_CONCEAL_FRAMES = 6
    DEFAULT_LOSS_PERC = 15

    def __init__(self, bitrate_bps: int = 32_000) -> None:
        self.bitrate_bps = max(12_000, min(64_000, int(bitrate_bps)))
        self._enc = None
        self._dec = None
        self.opus = False
        self.loss_perc = self.DEFAULT_LOSS_PERC
        self._last_rx_seq: Optional[int] = None
        self.fec_recovered = 0
        self.concealed = 0
        self.late_dropped = 0
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
            self._enable_fec()
            self.opus = True
        except Exception:
            logger.info("opuslib unavailable; call audio uses PCM16 fallback")

    def _enc_ctl(self, prop: str, value: int) -> bool:
        """Set an Opus encoder CTL.

        Goes through ``opuslib.api`` directly: the ``Encoder.inband_fec`` property
        setter in opuslib 3.0.1 drops the value, and assigning an unknown
        attribute would silently do nothing.
        """
        enc = self._enc
        if enc is None:
            return False
        try:
            from opuslib.api import ctl as _ctl  # type: ignore
            from opuslib.api import encoder as _api_enc  # type: ignore

            fn = getattr(_ctl, f"set_{prop}", None)
            state = getattr(enc, "encoder_state", None)
            if fn is not None and state is not None:
                _api_enc.encoder_ctl(state, fn, int(value))
                return True
        except Exception:
            logger.debug("opus %s ctl failed", prop, exc_info=True)
        return False

    def _enable_fec(self) -> None:
        ok_fec = self._enc_ctl("inband_fec", 1)
        ok_loss = self._enc_ctl("packet_loss_perc", self.loss_perc)
        logger.info("opus FEC=%s loss_hint=%s%%", ok_fec, self.loss_perc if ok_loss else "n/a")

    def set_loss_hint(self, pct: int) -> None:
        """Tell the encoder how lossy the path is (more FEC redundancy when higher)."""
        pct = max(0, min(40, int(pct)))
        if pct == self.loss_perc:
            return
        self.loss_perc = pct
        if self._enc is not None and self.opus:
            self._enc_ctl("packet_loss_perc", pct)

    def encode(self, pcm16: bytes, *, allow_opus: bool = True) -> Tuple[bytes, bool]:
        """Opus when available *and* the peer can decode it, else raw PCM16."""
        if allow_opus and self._enc is not None and self.opus:
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

    def _decode_extra(self, payload: bytes, *, fec: bool) -> bytes:
        """PLC (``payload`` empty) or in-band FEC recovery of the previous frame."""
        if self._dec is None:
            return b""
        try:
            return self._dec.decode(payload, SAMPLES_PER_FRAME, decode_fec=fec)
        except Exception:
            logger.debug("opus plc/fec decode failed", exc_info=True)
            return b""

    def reset_stream(self) -> None:
        self._last_rx_seq = None

    def decode_stream(
        self, payload: bytes, *, opus: bool, seq: int = 0, seq_tracked: bool = False
    ) -> list[bytes]:
        """Decode one received audio packet into PCM frames, concealing losses.

        Returns the frames to play in order. When ``seq_tracked`` (peer numbers
        audio packets itself) a gap is filled by Opus PLC and the last missing
        frame is recovered from this packet's in-band FEC; late/duplicate
        packets are dropped because the decoder state cannot go backwards.
        """
        if not (seq_tracked and opus and self.opus and self._dec is not None):
            pcm = self.decode(payload, opus=opus)
            return [pcm] if pcm else []
        out: list[bytes] = []
        last = self._last_rx_seq
        if last is not None:
            gap = (seq - last) & 0xFFFFFFFF
            if gap == 0 or gap >= 0x80000000:
                self.late_dropped += 1
                return []
            missing = gap - 1
            if 1 <= missing <= self.MAX_CONCEAL_FRAMES:
                for _ in range(missing - 1):
                    pcm = self._decode_extra(b"", fec=False)
                    if pcm:
                        out.append(pcm)
                        self.concealed += 1
                pcm = self._decode_extra(payload, fec=True)
                if pcm:
                    out.append(pcm)
                    self.fec_recovered += 1
                else:
                    pcm = self._decode_extra(b"", fec=False)
                    if pcm:
                        out.append(pcm)
                        self.concealed += 1
        self._last_rx_seq = seq
        pcm = self.decode(payload, opus=True)
        if pcm:
            out.append(pcm)
        return out


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
    """Decode a remote JPEG, or return None when it is too large to display safely."""
    from i2pchat.core.media_framing import MAX_RX_FRAME_BYTES
    from i2pchat.core.video_codec import video_dimensions_allowed

    if not data or len(data) > MAX_RX_FRAME_BYTES:
        return None
    from PyQt6 import QtCore, QtGui

    buf = QtCore.QBuffer()
    buf.setData(QtCore.QByteArray(data))
    if not buf.open(QtCore.QIODevice.OpenModeFlag.ReadOnly):
        return None
    reader = QtGui.QImageReader()
    reader.setDevice(buf)
    reader.setFormat(b"JPG")
    size = reader.size()
    if not video_dimensions_allowed(size.width(), size.height()):
        return None
    img = reader.read()
    return img if img is not None and not img.isNull() else None


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
