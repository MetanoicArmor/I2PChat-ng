"""Call session state machine: STREAM signaling + SAM DATAGRAM media."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Awaitable, Callable, Optional

from i2pchat.core.call_prefs import CallPrefs, load_call_prefs
from i2pchat.core.call_signals import (
    CALL_ACCEPT,
    CALL_HANGUP,
    CALL_INVITE,
    CALL_MEDIA,
    CALL_REJECT,
    CallAccept,
    CallHangup,
    CallInvite,
    CallMediaState,
    CallReject,
    ParsedCallSignal,
    call_id_hash16,
    new_call_id,
    parse_call_signal,
)
from i2pchat.core.media_framing import (
    TYPE_OPUS,
    TYPE_PCM16,
    TYPE_VIDEO_JPEG,
    MediaPacket,
    decode_media_packet,
    fragment_payload,
)
from i2pchat.sam.datagram import DatagramSession, create_datagram_session, naming_lookup_b64

logger = logging.getLogger(__name__)

SendSignal = Callable[[str, str], Awaitable[bool]]
OnCallEvent = Callable[[str, dict[str, Any]], None]


class CallPhase(str, Enum):
    IDLE = "idle"
    OUTGOING = "outgoing"
    INCOMING = "incoming"
    ACTIVE = "active"
    ENDING = "ending"


@dataclass
class CallState:
    phase: CallPhase = CallPhase.IDLE
    call_id: str = ""
    peer: str = ""
    want_video: bool = False
    local_mic: bool = True
    local_cam: bool = False
    local_screen: bool = False
    local_spk: bool = True
    remote_mic: bool = True
    remote_cam: bool = False
    remote_screen: bool = False
    remote_spk: bool = True
    remote_datagram_b64: str = ""
    local_datagram_b32: str = ""
    local_datagram_b64: str = ""
    codecs: str = "opus,jpeg"
    max_bitrate: int = 320_000
    minimized: bool = False
    error: str = ""


class CallSessionManager:
    """Owns at most one call; creates TRANSIENT DATAGRAM when negotiating."""

    def __init__(
        self,
        *,
        sam_address: tuple[str, int],
        send_signal: SendSignal,
        on_event: Optional[OnCallEvent] = None,
        profile: Optional[str] = None,
    ) -> None:
        self.sam_address = sam_address
        self.send_signal = send_signal
        self.on_event = on_event
        self.profile = profile
        self.state = CallState()
        self.prefs: CallPrefs = load_call_prefs(profile)
        self._dg: Optional[DatagramSession] = None
        self._seq = 0
        self._lock = asyncio.Lock()
        self._remote_b64_cache: dict[str, str] = {}
        self._frag_buf: dict[int, list[bytes]] = {}

    def _emit(self, kind: str, **extra: Any) -> None:
        if self.on_event is None:
            return
        payload = {"kind": kind, "state": self.snapshot()}
        payload.update(extra)
        try:
            self.on_event(kind, payload)
        except Exception:
            logger.exception("call event handler failed")

    def snapshot(self) -> dict[str, Any]:
        s = self.state
        return {
            "phase": s.phase.value,
            "call_id": s.call_id,
            "peer": s.peer,
            "want_video": s.want_video,
            "mic": s.local_mic,
            "cam": s.local_cam,
            "screen": s.local_screen,
            "spk": s.local_spk,
            "remote_mic": s.remote_mic,
            "remote_cam": s.remote_cam,
            "remote_screen": s.remote_screen,
            "remote_spk": s.remote_spk,
            "minimized": s.minimized,
            "error": s.error,
            "max_bitrate": s.max_bitrate,
            "audio_bitrate_kbps": self.prefs.audio_bitrate_kbps,
            "video_bitrate_kbps": self.prefs.video_bitrate_kbps,
            "video_fps": self.prefs.video_fps,
            "video_max_edge": self.prefs.video_max_edge,
        }

    async def start_outgoing(self, peer: str, *, video: bool) -> CallState:
        async with self._lock:
            if self.state.phase != CallPhase.IDLE:
                raise RuntimeError("A call is already in progress")
            prefs = load_call_prefs(self.profile)
            self.prefs = prefs
            call_id = new_call_id()
            dg = await self._open_datagram(f"call-{uuid.uuid4().hex[:10]}")
            self._dg = dg
            self.state = CallState(
                phase=CallPhase.OUTGOING,
                call_id=call_id,
                peer=peer,
                want_video=video,
                local_mic=prefs.start_mic,
                local_cam=video,
                local_spk=prefs.start_spk,
                local_datagram_b32=dg.local_b32,
                local_datagram_b64=dg.local_destination_b64,
                max_bitrate=prefs.max_bitrate_bps,
            )
            invite = CallInvite(
                call_id=call_id,
                want_audio=True,
                want_video=video,
                codecs="opus,jpeg",
                max_bitrate=prefs.max_bitrate_bps,
                datagram_b32=dg.local_b32 or dg.local_destination_b64,
            )
            ok = await self.send_signal(peer, invite.encode())
            if not ok:
                await self._teardown_unlocked("signal failed")
                raise RuntimeError("Could not send CALL_INVITE (peer not live?)")
            self._emit("outgoing")
            return self.state

    async def accept_incoming(self) -> CallState:
        async with self._lock:
            if self.state.phase != CallPhase.INCOMING:
                raise RuntimeError("No incoming call to accept")
            prefs = load_call_prefs(self.profile)
            self.prefs = prefs
            dg = await self._open_datagram(f"call-{uuid.uuid4().hex[:10]}")
            self._dg = dg
            self.state.local_datagram_b32 = dg.local_b32
            self.state.local_datagram_b64 = dg.local_destination_b64
            self.state.local_mic = prefs.start_mic
            self.state.local_spk = prefs.start_spk
            if self.state.want_video:
                self.state.local_cam = True
            accept = CallAccept(
                call_id=self.state.call_id,
                codecs="opus,jpeg",
                max_bitrate=prefs.max_bitrate_bps,
                datagram_b32=dg.local_b32 or dg.local_destination_b64,
            )
            ok = await self.send_signal(self.state.peer, accept.encode())
            if not ok:
                await self._teardown_unlocked("accept signal failed")
                raise RuntimeError("Could not send CALL_ACCEPT")
            await self._resolve_remote_dest(self.state.remote_datagram_b64 or "")
            self.state.phase = CallPhase.ACTIVE
            self.state.max_bitrate = prefs.max_bitrate_bps
            self._emit("active")
            await self._send_media_state_unlocked()
            return self.state

    async def reject_incoming(self, reason: str = "declined") -> None:
        async with self._lock:
            if self.state.phase != CallPhase.INCOMING:
                return
            peer = self.state.peer
            call_id = self.state.call_id
            await self.send_signal(peer, CallReject(call_id, reason).encode())
            await self._teardown_unlocked("rejected")

    async def hangup(self) -> None:
        async with self._lock:
            if self.state.phase == CallPhase.IDLE:
                return
            peer = self.state.peer
            call_id = self.state.call_id
            if peer and call_id:
                try:
                    await self.send_signal(peer, CallHangup(call_id).encode())
                except Exception:
                    logger.debug("CALL_HANGUP send failed", exc_info=True)
            await self._teardown_unlocked("hangup")

    async def set_media(
        self,
        *,
        mic: Optional[bool] = None,
        cam: Optional[bool] = None,
        screen: Optional[bool] = None,
        spk: Optional[bool] = None,
    ) -> CallState:
        async with self._lock:
            if self.state.phase not in (CallPhase.ACTIVE, CallPhase.OUTGOING, CallPhase.INCOMING):
                return self.state
            if mic is not None:
                self.state.local_mic = bool(mic)
            if cam is not None:
                self.state.local_cam = bool(cam)
                if self.state.local_cam:
                    self.state.local_screen = False
            if screen is not None:
                self.state.local_screen = bool(screen)
                if self.state.local_screen:
                    self.state.local_cam = False
            if spk is not None:
                self.state.local_spk = bool(spk)
            if self.state.phase == CallPhase.ACTIVE:
                await self._send_media_state_unlocked()
            self._emit("media")
            return self.state

    def set_minimized(self, minimized: bool) -> None:
        self.state.minimized = bool(minimized)
        self._emit("minimized")

    async def handle_signal(self, peer: str, payload: str) -> bool:
        parsed = parse_call_signal(payload)
        if parsed is None:
            return False
        async with self._lock:
            if parsed.kind == CALL_INVITE:
                if self.state.phase != CallPhase.IDLE:
                    await self.send_signal(
                        peer,
                        CallReject(parsed.call_id, "busy").encode(),
                    )
                    return True
                self.state = CallState(
                    phase=CallPhase.INCOMING,
                    call_id=parsed.call_id,
                    peer=peer,
                    want_video=parsed.want_video,
                    codecs=parsed.codecs,
                    max_bitrate=parsed.max_bitrate,
                    remote_datagram_b64=parsed.datagram_b32,
                )
                self._emit("incoming")
                return True
            if parsed.kind == CALL_ACCEPT:
                if self.state.phase != CallPhase.OUTGOING or parsed.call_id != self.state.call_id:
                    return True
                self.state.remote_datagram_b64 = parsed.datagram_b32
                self.state.codecs = parsed.codecs or self.state.codecs
                self.state.max_bitrate = min(self.state.max_bitrate, parsed.max_bitrate)
                await self._resolve_remote_dest(parsed.datagram_b32)
                self.state.phase = CallPhase.ACTIVE
                self._emit("active")
                await self._send_media_state_unlocked()
                return True
            if parsed.kind == CALL_REJECT:
                if parsed.call_id and parsed.call_id == self.state.call_id:
                    self.state.error = parsed.reason or "rejected"
                    await self._teardown_unlocked("rejected")
                return True
            if parsed.kind == CALL_HANGUP:
                if parsed.call_id and parsed.call_id == self.state.call_id:
                    await self._teardown_unlocked("remote_hangup")
                return True
            if parsed.kind == CALL_MEDIA:
                if parsed.call_id != self.state.call_id:
                    return True
                self.state.remote_mic = parsed.mic
                self.state.remote_cam = parsed.cam
                self.state.remote_screen = parsed.screen
                self.state.remote_spk = parsed.spk
                self._emit("remote_media")
                return True
        return True

    async def send_audio_frame(self, pcm_or_opus: bytes, *, opus: bool, ts_ms: Optional[int] = None) -> None:
        if not self.state.local_mic:
            return
        ptype = TYPE_OPUS if opus else TYPE_PCM16
        await self._send_media(ptype, pcm_or_opus, ts_ms=ts_ms)

    async def send_jpeg_frame(self, jpeg: bytes, *, ts_ms: Optional[int] = None) -> None:
        if not (self.state.local_cam or self.state.local_screen):
            return
        await self._send_media(TYPE_VIDEO_JPEG, jpeg, ts_ms=ts_ms)

    async def _send_media(self, ptype: int, payload: bytes, *, ts_ms: Optional[int]) -> None:
        dg = self._dg
        remote = self.state.remote_datagram_b64
        if dg is None or self.state.phase != CallPhase.ACTIVE or not remote:
            return
        if len(remote) < 80:
            # still a b32 / short token — try resolve
            await self._resolve_remote_dest(remote)
            remote = self.state.remote_datagram_b64
        if len(remote) < 80:
            return
        call_hash = call_id_hash16(self.state.call_id)
        now = int(time.time() * 1000) if ts_ms is None else int(ts_ms)
        self._seq = (self._seq + 1) & 0xFFFFFFFF
        frame_seq = self._seq
        for chunk, frag_flags in fragment_payload(payload):
            pkt = MediaPacket(
                call_hash=call_hash,
                seq=frame_seq,
                ts_ms=now,
                ptype=ptype,
                flags=frag_flags,
                payload=chunk,
            )
            try:
                await dg.send(remote, pkt.encode())
            except Exception:
                logger.debug("DATAGRAM send failed", exc_info=True)
                return

    async def _on_datagram(self, from_dest: str, payload: bytes) -> None:
        from i2pchat.core.media_framing import FLAG_FRAG_MORE

        pkt = decode_media_packet(payload)
        if pkt is None:
            return
        if self.state.call_id and pkt.call_hash != call_id_hash16(self.state.call_id):
            return
        if pkt.flags & FLAG_FRAG_MORE:
            self._frag_buf.setdefault(pkt.seq, []).append(pkt.payload)
            return
        chunks = self._frag_buf.pop(pkt.seq, [])
        chunks.append(pkt.payload)
        body = b"".join(chunks)
        self._emit(
            "media_packet",
            ptype=pkt.ptype,
            ts_ms=pkt.ts_ms,
            seq=pkt.seq,
            payload=body,
            from_dest=from_dest,
        )

    async def _open_datagram(self, session_id: str) -> DatagramSession:
        return await create_datagram_session(
            session_id,
            sam_address=self.sam_address,
            destination=None,
            on_datagram=self._on_datagram,
        )

    async def _resolve_remote_dest(self, token: str) -> None:
        token = (token or "").strip()
        if not token:
            return
        if len(token) > 80 and "=" not in token[:20]:
            # likely already base64 destination
            self.state.remote_datagram_b64 = token
            return
        if token in self._remote_b64_cache:
            self.state.remote_datagram_b64 = self._remote_b64_cache[token]
            return
        name = token if token.endswith(".i2p") else f"{token}.b32.i2p" if not token.endswith(".b32.i2p") else token
        if token.endswith(".b32.i2p"):
            name = token
        elif "." not in token:
            name = f"{token}.b32.i2p"
        try:
            b64 = await naming_lookup_b64(name, sam_address=self.sam_address)
            self._remote_b64_cache[token] = b64
            self.state.remote_datagram_b64 = b64
        except Exception:
            logger.warning("DATAGRAM naming lookup failed for %s", name, exc_info=True)
            self.state.remote_datagram_b64 = token

    async def _send_media_state_unlocked(self) -> None:
        if not self.state.peer or not self.state.call_id:
            return
        body = CallMediaState(
            call_id=self.state.call_id,
            mic=self.state.local_mic,
            cam=self.state.local_cam,
            screen=self.state.local_screen,
            spk=self.state.local_spk,
        ).encode()
        await self.send_signal(self.state.peer, body)

    async def _teardown_unlocked(self, reason: str) -> None:
        self.state.phase = CallPhase.ENDING
        dg = self._dg
        self._dg = None
        if dg is not None:
            try:
                await dg.close()
            except Exception:
                logger.debug("DATAGRAM close failed", exc_info=True)
        peer = self.state.peer
        call_id = self.state.call_id
        err = self.state.error
        self.state = CallState(error=err if reason in ("rejected",) else "")
        self._frag_buf.clear()
        self._emit("ended", reason=reason, peer=peer, call_id=call_id)
