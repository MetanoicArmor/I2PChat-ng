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
from collections import OrderedDict
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
# XOR parity for one fragmented video frame (single datagram, frag_total == 1).
# Payload: orig_ptype(1) frag_total(1) frame_len(4) xor_of_all_chunks (zero-padded).
# Old peers ignore the unknown type, so this is backward compatible.
TYPE_PARITY = 7

FLAG_PCM_FALLBACK = 0x02
# ``seq`` of audio packets is a dedicated per-stream counter, so gaps mean loss.
FLAG_AUDIO_SEQ = 0x04

PARITY_HEADER = struct.Struct(">BBI")
# Parity datagram = fragment-sized XOR + its small header.
MAX_PACKET_PAYLOAD = MAX_PAYLOAD + PARITY_HEADER.size
# Largest frame we will reassemble. Android VP8 may use 8 fragments; anything
# bigger is not a frame this client sends, so it is dropped before it is stored.
MAX_RX_FRAME_BYTES = MAX_PAYLOAD * 8

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
        limit = MAX_PACKET_PAYLOAD if self.ptype == TYPE_PARITY else MAX_PAYLOAD
        if len(self.payload) > limit:
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
    if len(data) > HEADER_SIZE + MAX_PACKET_PAYLOAD:
        return None
    call_hash = data[4:20]
    seq, ts_ms, ptype, flags, frag_idx, frag_total = struct.unpack(">IIBBBB", data[20:32])
    if frag_total < 1 or frag_idx >= frag_total:
        return None
    payload = data[HEADER_SIZE:]
    limit = MAX_PACKET_PAYLOAD if ptype == TYPE_PARITY else MAX_PAYLOAD
    if len(payload) > limit:
        return None
    return MediaPacket(
        call_hash=call_hash,
        seq=seq,
        ts_ms=ts_ms,
        ptype=ptype,
        flags=flags,
        payload=payload,
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


def _xor_bytes(a: bytes, b: bytes, size: int) -> bytes:
    x = int.from_bytes(a.ljust(size, b"\0"), "big") ^ int.from_bytes(b.ljust(size, b"\0"), "big")
    return x.to_bytes(size, "big")


def build_parity_payload(ptype: int, chunks: list[bytes]) -> bytes:
    """XOR parity over all ``chunks`` of one frame (needs >= 2 chunks)."""
    if len(chunks) < 2:
        raise ValueError("parity needs a fragmented frame")
    size = max(len(c) for c in chunks)
    acc = bytes(size)
    for c in chunks:
        acc = _xor_bytes(acc, c, size)
    frame_len = sum(len(c) for c in chunks)
    return PARITY_HEADER.pack(ptype & 0xFF, len(chunks) & 0xFF, frame_len) + acc


@dataclass
class _Pending:
    total: int
    ptype: int
    ts_ms: int
    started: float
    parts: dict[int, bytes]
    parity: Optional[bytes] = None
    frame_len: int = 0


class FrameReassembler:
    """Rebuild fragmented media frames; incomplete frames expire.

    A single lost fragment is recovered when the sender's XOR parity packet
    (``TYPE_PARITY``) for that frame arrived.
    """

    _DONE_MAX = 64

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
        self._done: "OrderedDict[tuple[int, int], None]" = OrderedDict()
        self.dropped_frames = 0
        self.recovered_frames = 0

    def __len__(self) -> int:
        return len(self._pending)

    def _mark_done(self, key: tuple[int, int]) -> None:
        self._done[key] = None
        while len(self._done) > self._DONE_MAX:
            self._done.popitem(last=False)

    def _get_pending(
        self, key: tuple[int, int], *, total: int, ptype: int, ts_ms: int, now: float
    ) -> _Pending:
        pend = self._pending.get(key)
        if pend is None or pend.total != total:
            if len(self._pending) >= self.max_pending:
                oldest = min(self._pending, key=lambda k: self._pending[k].started)
                del self._pending[oldest]
                self.dropped_frames += 1
            pend = _Pending(total=total, ptype=ptype, ts_ms=ts_ms, started=now, parts={})
            self._pending[key] = pend
        return pend

    def feed(self, pkt: MediaPacket) -> Optional[bytes]:
        """Return the full frame payload once complete, else ``None``."""
        now = self._clock()
        self._expire(now)
        if pkt.ptype == TYPE_PARITY:
            return self._feed_parity(pkt, now)
        if pkt.frag_total <= 1:
            if len(pkt.payload) > MAX_RX_FRAME_BYTES:
                return None
            return pkt.payload
        key = (pkt.seq, pkt.ptype)
        if key in self._done:
            return None  # late fragment of a frame we already rebuilt
        pend = self._get_pending(
            key, total=pkt.frag_total, ptype=pkt.ptype, ts_ms=pkt.ts_ms, now=now
        )
        if not self._fits(pend, pkt.frag_idx, pkt.payload):
            self._pending.pop(key, None)
            self.dropped_frames += 1
            return None
        pend.parts[pkt.frag_idx] = pkt.payload
        return self._try_complete(key, pend)

    def _feed_parity(self, pkt: MediaPacket, now: float) -> Optional[bytes]:
        data = pkt.payload
        if len(data) <= PARITY_HEADER.size:
            return None
        orig_ptype, total, frame_len = PARITY_HEADER.unpack(data[: PARITY_HEADER.size])
        if total < 2:
            return None
        key = (pkt.seq, orig_ptype)
        if key in self._done:
            return None
        pend = self._get_pending(
            key, total=total, ptype=orig_ptype, ts_ms=pkt.ts_ms, now=now
        )
        pend.parity = data[PARITY_HEADER.size :]
        pend.frame_len = frame_len
        return self._try_complete(key, pend)

    def _try_complete(self, key: tuple[int, int], pend: _Pending) -> Optional[bytes]:
        have = len(pend.parts)
        if have >= pend.total:
            del self._pending[key]
            self._mark_done(key)
            return self._bounded(b"".join(pend.parts[i] for i in range(pend.total)))
        if have == pend.total - 1 and pend.parity is not None:
            rebuilt = self._recover(pend)
            del self._pending[key]
            self._mark_done(key)
            if rebuilt is None or len(rebuilt) > MAX_RX_FRAME_BYTES:
                self.dropped_frames += 1
                return None
            self.recovered_frames += 1
            return rebuilt
        return None

    def _fits(self, pend: _Pending, idx: int, payload: bytes) -> bool:
        total = len(payload)
        for part_idx, part in pend.parts.items():
            if part_idx != idx:
                total += len(part)
        return total <= MAX_RX_FRAME_BYTES

    def _bounded(self, body: bytes) -> Optional[bytes]:
        if len(body) > MAX_RX_FRAME_BYTES:
            self.dropped_frames += 1
            return None
        return body

    @staticmethod
    def _recover(pend: _Pending) -> Optional[bytes]:
        assert pend.parity is not None
        size = len(pend.parity)
        missing = next(i for i in range(pend.total) if i not in pend.parts)
        if missing < pend.total - 1:
            miss_len = size
        else:
            miss_len = pend.frame_len - size * (pend.total - 1)
        if not (0 < miss_len <= size):
            return None
        acc = pend.parity
        for part in pend.parts.values():
            if len(part) > size:
                return None
            acc = _xor_bytes(acc, part, size)
        chunks = dict(pend.parts)
        chunks[missing] = acc[:miss_len]
        return b"".join(chunks[i] for i in range(pend.total))

    def _expire(self, now: float) -> None:
        stale = [k for k, p in self._pending.items() if now - p.started > self.timeout_s]
        for k in stale:
            pend = self._pending.pop(k)
            if pend.parts:
                self.dropped_frames += 1
