"""Binary framing for call media over SAM DATAGRAM (format ``I2C2``).

Every datagram carries one fragment of a media frame::

    magic(4) call_hash(16) seq(4) ts_ms(4) ptype(1) flags(1) frag_idx(1) frag_total(1) payload

A frame larger than ``MAX_PAYLOAD`` is split into ``frag_total`` datagrams that
share ``seq``. ``FrameReassembler`` rebuilds frames on the receiving side and
drops incomplete ones after a timeout (I2P datagrams may be lost or reordered).
"""

from __future__ import annotations

import struct
import time
from dataclasses import dataclass
from typing import Callable, Optional

MAGIC = b"I2C2"
HEADER_SIZE = 4 + 16 + 4 + 4 + 1 + 1 + 1 + 1  # magic + call_hash + seq + ts + type + flags + idx + total

# i2pd's SAM control socket buffer is 8192 bytes (SAM_SOCKET_BUFFER_SIZE): one
# DATAGRAM SEND (header line + payload) must fit into it. Large I2P datagrams are
# also much less reliable, so keep chunks small.
MAX_PAYLOAD = 3_500
MAX_FRAGMENTS = 255

TYPE_OPUS = 1
TYPE_VIDEO_KEY = 2  # VP8 keyframe
TYPE_VIDEO_JPEG = 3
TYPE_VIDEO_DELTA = 4  # VP8 delta / P-frame
TYPE_PCM16 = 5  # fallback when Opus is unavailable
TYPE_KEYFRAME_REQ = 6  # empty payload: peer missed a keyframe, please send one

FLAG_PCM_FALLBACK = 0x02

REASSEMBLY_TIMEOUT_S = 1.5
REASSEMBLY_MAX_PENDING = 16


@dataclass(frozen=True)
class MediaPacket:
    call_hash: bytes  # 16 bytes
    seq: int
    ts_ms: int
    ptype: int
    flags: int
    payload: bytes
    frag_idx: int = 0
    frag_total: int = 1

    def encode(self) -> bytes:
        if len(self.call_hash) != 16:
            raise ValueError("call_hash must be 16 bytes")
        if len(self.payload) > MAX_PAYLOAD:
            raise ValueError("payload too large")
        if not (1 <= self.frag_total <= MAX_FRAGMENTS and 0 <= self.frag_idx < self.frag_total):
            raise ValueError("invalid fragment index/total")
        return (
            MAGIC
            + self.call_hash
            + struct.pack(
                ">IIBBBB",
                self.seq & 0xFFFFFFFF,
                self.ts_ms & 0xFFFFFFFF,
                self.ptype & 0xFF,
                self.flags & 0xFF,
                self.frag_idx & 0xFF,
                self.frag_total & 0xFF,
            )
            + self.payload
        )


def decode_media_packet(data: bytes) -> Optional[MediaPacket]:
    if len(data) < HEADER_SIZE or data[:4] != MAGIC:
        return None
    call_hash = data[4:20]
    seq, ts_ms, ptype, flags, frag_idx, frag_total = struct.unpack(">IIBBBB", data[20:32])
    if frag_total < 1 or frag_idx >= frag_total:
        return None
    return MediaPacket(
        call_hash=call_hash,
        seq=seq,
        ts_ms=ts_ms,
        ptype=ptype,
        flags=flags,
        payload=data[HEADER_SIZE:],
        frag_idx=frag_idx,
        frag_total=frag_total,
    )


def fragment_payload(payload: bytes, *, max_chunk: int = MAX_PAYLOAD) -> list[bytes]:
    """Split ``payload`` into chunks of at most ``max_chunk`` bytes (always >= 1 chunk)."""
    if max_chunk <= 0:
        raise ValueError("max_chunk must be positive")
    if not payload:
        return [b""]
    chunks = [payload[i : i + max_chunk] for i in range(0, len(payload), max_chunk)]
    if len(chunks) > MAX_FRAGMENTS:
        raise ValueError("payload needs too many fragments")
    return chunks


def reassemble_chunks(chunks: list[bytes]) -> bytes:
    return b"".join(chunks)


@dataclass
class _Pending:
    total: int
    ptype: int
    ts_ms: int
    started: float
    parts: dict[int, bytes]


class FrameReassembler:
    """Rebuild fragmented media frames; incomplete frames expire."""

    def __init__(
        self,
        *,
        timeout_s: float = REASSEMBLY_TIMEOUT_S,
        max_pending: int = REASSEMBLY_MAX_PENDING,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.timeout_s = timeout_s
        self.max_pending = max_pending
        self._clock = clock
        self._pending: dict[tuple[int, int], _Pending] = {}
        self.dropped_frames = 0

    def __len__(self) -> int:
        return len(self._pending)

    def feed(self, pkt: MediaPacket) -> Optional[bytes]:
        """Return the full frame payload once complete, else ``None``."""
        now = self._clock()
        self._expire(now)
        if pkt.frag_total <= 1:
            return pkt.payload
        key = (pkt.seq, pkt.ptype)
        pend = self._pending.get(key)
        if pend is None or pend.total != pkt.frag_total:
            if len(self._pending) >= self.max_pending:
                oldest = min(self._pending, key=lambda k: self._pending[k].started)
                del self._pending[oldest]
                self.dropped_frames += 1
            pend = _Pending(
                total=pkt.frag_total,
                ptype=pkt.ptype,
                ts_ms=pkt.ts_ms,
                started=now,
                parts={},
            )
            self._pending[key] = pend
        pend.parts[pkt.frag_idx] = pkt.payload
        if len(pend.parts) < pend.total:
            return None
        del self._pending[key]
        return b"".join(pend.parts[i] for i in range(pend.total))

    def _expire(self, now: float) -> None:
        stale = [k for k, p in self._pending.items() if now - p.started > self.timeout_s]
        for k in stale:
            del self._pending[k]
            self.dropped_frames += 1
