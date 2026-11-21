"""Binary framing for call media over SAM DATAGRAM."""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Optional

MAGIC = b"I2C1"
HEADER_SIZE = 4 + 16 + 4 + 4 + 1 + 1  # magic + call_hash + seq + ts + type + flags
MAX_PAYLOAD = 10_000  # stay under typical I2P datagram soft limits

TYPE_OPUS = 1
TYPE_VIDEO_KEY = 2
TYPE_VIDEO_JPEG = 3
TYPE_PCM16 = 5  # fallback when Opus is unavailable

FLAG_FRAG_MORE = 0x01
FLAG_PCM_FALLBACK = 0x02


@dataclass(frozen=True)
class MediaPacket:
    call_hash: bytes  # 16 bytes
    seq: int
    ts_ms: int
    ptype: int
    flags: int
    payload: bytes

    def encode(self) -> bytes:
        if len(self.call_hash) != 16:
            raise ValueError("call_hash must be 16 bytes")
        if len(self.payload) > MAX_PAYLOAD:
            raise ValueError("payload too large")
        return (
            MAGIC
            + self.call_hash
            + struct.pack(">IIBB", self.seq & 0xFFFFFFFF, self.ts_ms & 0xFFFFFFFF, self.ptype & 0xFF, self.flags & 0xFF)
            + self.payload
        )


def decode_media_packet(data: bytes) -> Optional[MediaPacket]:
    if len(data) < HEADER_SIZE or data[:4] != MAGIC:
        return None
    call_hash = data[4:20]
    seq, ts_ms, ptype, flags = struct.unpack(">IIBB", data[20:30])
    return MediaPacket(
        call_hash=call_hash,
        seq=seq,
        ts_ms=ts_ms,
        ptype=ptype,
        flags=flags,
        payload=data[30:],
    )


def fragment_payload(payload: bytes, *, max_chunk: int = MAX_PAYLOAD) -> list[tuple[bytes, int]]:
    """Split payload into (chunk, flags) pairs; last chunk has flags without FLAG_FRAG_MORE."""
    if max_chunk <= 0:
        raise ValueError("max_chunk must be positive")
    if not payload:
        return [(b"", 0)]
    out: list[tuple[bytes, int]] = []
    offset = 0
    while offset < len(payload):
        chunk = payload[offset : offset + max_chunk]
        offset += len(chunk)
        flags = FLAG_FRAG_MORE if offset < len(payload) else 0
        out.append((chunk, flags))
    return out


def reassemble_chunks(chunks: list[bytes]) -> bytes:
    return b"".join(chunks)
