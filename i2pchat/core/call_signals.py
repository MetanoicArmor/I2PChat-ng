"""CALL_* signaling payloads carried in STREAM ``S`` frames."""

from __future__ import annotations

import hashlib
import secrets
import time
from dataclasses import dataclass
from typing import Optional

CALL_INVITE = "CALL_INVITE"
CALL_ACCEPT = "CALL_ACCEPT"
CALL_REJECT = "CALL_REJECT"
CALL_HANGUP = "CALL_HANGUP"
CALL_MEDIA = "CALL_MEDIA"

DEFAULT_CODECS = "opus,jpeg"


def new_call_id() -> str:
    return f"{int(time.time())}-{secrets.token_hex(8)}"


def call_id_hash16(call_id: str) -> bytes:
    return hashlib.sha256(call_id.encode("utf-8")).digest()[:16]


@dataclass(frozen=True)
class CallInvite:
    call_id: str
    want_audio: bool
    want_video: bool
    codecs: str
    max_bitrate: int
    datagram_b32: str

    def encode(self) -> str:
        media = "video" if self.want_video else "audio"
        return (
            f"{CALL_INVITE}|{self.call_id}|{media}|{self.codecs}|"
            f"{int(self.max_bitrate)}|{self.datagram_b32}"
        )


@dataclass(frozen=True)
class CallAccept:
    call_id: str
    codecs: str
    max_bitrate: int
    datagram_b32: str

    def encode(self) -> str:
        return (
            f"{CALL_ACCEPT}|{self.call_id}|{self.codecs}|"
            f"{int(self.max_bitrate)}|{self.datagram_b32}"
        )


@dataclass(frozen=True)
class CallReject:
    call_id: str
    reason: str = "declined"

    def encode(self) -> str:
        safe = (self.reason or "declined").replace("|", "_")[:64]
        return f"{CALL_REJECT}|{self.call_id}|{safe}"


@dataclass(frozen=True)
class CallHangup:
    call_id: str

    def encode(self) -> str:
        return f"{CALL_HANGUP}|{self.call_id}"


@dataclass(frozen=True)
class CallMediaState:
    call_id: str
    mic: bool = True
    cam: bool = False
    screen: bool = False
    spk: bool = True

    def encode(self) -> str:
        return (
            f"{CALL_MEDIA}|{self.call_id}|"
            f"mic={1 if self.mic else 0}|"
            f"cam={1 if self.cam else 0}|"
            f"screen={1 if self.screen else 0}|"
            f"spk={1 if self.spk else 0}"
        )


@dataclass(frozen=True)
class ParsedCallSignal:
    kind: str
    call_id: str
    want_audio: bool = True
    want_video: bool = False
    codecs: str = DEFAULT_CODECS
    max_bitrate: int = 320_000
    datagram_b32: str = ""
    reason: str = ""
    mic: bool = True
    cam: bool = False
    screen: bool = False
    spk: bool = True


def parse_call_signal(payload: str) -> Optional[ParsedCallSignal]:
    text = (payload or "").strip()
    if not text.startswith("CALL_"):
        return None
    parts = text.split("|")
    kind = parts[0]
    if kind == CALL_INVITE and len(parts) >= 6:
        media = parts[2].strip().lower()
        return ParsedCallSignal(
            kind=kind,
            call_id=parts[1].strip(),
            want_audio=True,
            want_video=media == "video",
            codecs=parts[3].strip() or DEFAULT_CODECS,
            max_bitrate=_int(parts[4], 320_000),
            datagram_b32=parts[5].strip(),
        )
    if kind == CALL_ACCEPT and len(parts) >= 5:
        return ParsedCallSignal(
            kind=kind,
            call_id=parts[1].strip(),
            codecs=parts[2].strip() or DEFAULT_CODECS,
            max_bitrate=_int(parts[3], 320_000),
            datagram_b32=parts[4].strip(),
        )
    if kind == CALL_REJECT and len(parts) >= 2:
        return ParsedCallSignal(
            kind=kind,
            call_id=parts[1].strip(),
            reason=parts[2].strip() if len(parts) > 2 else "declined",
        )
    if kind == CALL_HANGUP and len(parts) >= 2:
        return ParsedCallSignal(kind=kind, call_id=parts[1].strip())
    if kind == CALL_MEDIA and len(parts) >= 3:
        flags = {}
        for token in parts[2:]:
            if "=" in token:
                k, v = token.split("=", 1)
                flags[k.strip()] = v.strip() == "1"
        return ParsedCallSignal(
            kind=kind,
            call_id=parts[1].strip(),
            mic=flags.get("mic", True),
            cam=flags.get("cam", False),
            screen=flags.get("screen", False),
            spk=flags.get("spk", True),
        )
    return None


def _int(raw: str, default: int) -> int:
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        return default
