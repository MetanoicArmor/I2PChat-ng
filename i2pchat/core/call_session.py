"""Call session state machine: STREAM signaling + SAM DATAGRAM media."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any, Awaitable, Callable, Optional

from i2pchat.core.call_media import opus_available
from i2pchat.core.call_prefs import CallPrefs, load_call_prefs
from i2pchat.core.call_signals import (
    CALL_ACCEPT,
    CALL_HANGUP,
    CALL_INVITE,
    CALL_MEDIA,
    CALL_REJECT,
    DEFAULT_CODECS,
    CallAccept,
    CallHangup,
    CallInvite,
    CallMediaState,
    CallReject,
    ParsedCallSignal,
    call_id_hash16,
    codecs_include,
    intersect_codecs,
    new_call_id,
    parse_call_signal,
    same_datagram_sender,
    valid_call_id,
    valid_datagram_token,
)
from i2pchat.core.media_framing import (
    FLAG_AUDIO_SEQ,
    TYPE_KEYFRAME_REQ,
    TYPE_OPUS,
    TYPE_PARITY,
    TYPE_PCM16,
    TYPE_VIDEO_DELTA,
    TYPE_VIDEO_JPEG,
    TYPE_VIDEO_KEY,
    FrameReassembler,
    MediaPacket,
    build_parity_payload,
    completed_ptype,
    decode_media_packet,
    fragment_payload,
)
from i2pchat.core.video_codec import vp8_available
from i2pchat.sam.datagram import DatagramSession, create_datagram_session, naming_lookup_b64
from i2pchat.storage.contact_book import normalize_peer_address, same_i2p_destination

logger = logging.getLogger(__name__)

SendSignal = Callable[[str, str], Awaitable[bool]]
OnCallEvent = Callable[[str, dict[str, Any]], None]
OpenDatagram = Callable[..., Awaitable[DatagramSession]]

ACCEPT_SETUP_TIMEOUT_S = 30.0
REMOTE_DEST_MIN_LEN = 80
RESOLVE_TIMEOUT_S = 20.0
RESOLVE_BACKOFF_MAX_S = 30.0

# Call media goes over its own DATAGRAM session; these are its tunnel defaults
# (hop count follows the router setting, 4 unless the user changed it).
CALL_TUNNEL_QUANTITY = 3
CALL_TUNNEL_BACKUP_QUANTITY = 1
# Spread the fragments of one video frame instead of bursting them into the tunnel.
VIDEO_FRAGMENT_PACE_S = 0.002
# One XOR parity packet per fragmented video frame (recovers any single lost fragment).
VIDEO_PARITY_ENABLED = True
_VIDEO_PTYPES = (TYPE_VIDEO_KEY, TYPE_VIDEO_DELTA, TYPE_VIDEO_JPEG)
# A malicious contact can hang up and invite again to force new tunnels.
# One invite from the same peer every few seconds, and only a handful per minute.
INVITE_COOLDOWN_S = 5.0
INVITE_WINDOW_S = 60.0
INVITE_BURST = 6


def is_full_destination(token: str) -> bool:
    """True for a full public I2P base64 destination (not a short b32 hash/name)."""
    t = (token or "").strip()
    return len(t) >= REMOTE_DEST_MIN_LEN and "." not in t


def b32_host_name(token: str) -> str:
    """Normalise a b32 token (with or without suffix) to ``<b32>.b32.i2p``."""
    t = (token or "").strip()
    if not t:
        return ""
    if t.endswith(".b32.i2p"):
        return t
    if "." not in t:
        return f"{t}.b32.i2p"
    return t if t.endswith(".i2p") else f"{t}.b32.i2p"


def datagram_token(dg: Any) -> str:
    """Address advertised to the peer: full public base64 first (no NAMING LOOKUP
    needed, a fresh TRANSIENT LeaseSet is usually not published yet), b32 as fallback."""
    return (getattr(dg, "local_destination_b64", "") or getattr(dg, "local_b32", "") or "").strip()


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
    codecs: str = DEFAULT_CODECS
    max_bitrate: int = 320_000
    minimized: bool = False
    error: str = ""
    media_ready: bool = False


def local_offer_codecs() -> str:
    """Advertise codecs this build can actually encode/decode."""
    parts: list[str] = []
    if opus_available():
        parts.append("opus")
    if vp8_available():
        parts.append("vp8")
    parts.append("jpeg")
    return ",".join(parts)


class CallSessionManager:
    """Owns at most one call; DATAGRAM setup is cancelable and off the signal path."""

    def __init__(
        self,
        *,
        sam_address: tuple[str, int],
        send_signal: SendSignal,
        on_event: Optional[OnCallEvent] = None,
        profile: Optional[str] = None,
        open_datagram: Optional[OpenDatagram] = None,
        tunnel_length: Optional[int] = None,
        tunnel_quantity: int = CALL_TUNNEL_QUANTITY,
    ) -> None:
        self.sam_address = sam_address
        self.tunnel_length = tunnel_length
        self.tunnel_quantity = tunnel_quantity
        self.send_signal = send_signal
        self.on_event = on_event
        self.profile = profile
        self._open_datagram_impl: OpenDatagram = open_datagram or self._default_open_datagram
        self.state = CallState()
        self.prefs: CallPrefs = load_call_prefs(profile)
        self._dg: Optional[DatagramSession] = None
        self._seq = 0
        self._audio_seq = 0
        self._lock = asyncio.Lock()
        self._remote_b64_cache: dict[str, str] = {}
        self._reassembler = FrameReassembler()
        self._resolve_task: Optional[asyncio.Task[None]] = None
        self._resolve_failures = 0
        self._next_resolve_at = 0.0
        self._reset_stats()
        self._setup_task: Optional[asyncio.Task[Optional[DatagramSession]]] = None
        self._dg_close_task: Optional[asyncio.Task[None]] = None
        self._generation = 0
        self._invite_times: list[float] = []
        self._invite_peer_at: dict[str, float] = {}

    def _reset_stats(self) -> None:
        self._tx_packets = 0
        self._tx_dropped = 0
        self._rx_packets = 0
        self._rx_frames = 0
        self._rx_dropped = 0
        self._first_rx_logged = False
        self._from_mismatch_logged = False
        self._control_closed_logged = False

    def stats(self) -> dict[str, Any]:
        dg = self._dg
        dg_stats: dict[str, Any] = {}
        if dg is not None and hasattr(dg, "stats"):
            try:
                dg_stats = dict(dg.stats())
            except Exception:
                dg_stats = {}
        return {
            "tx_packets": self._tx_packets,
            "tx_dropped": self._tx_dropped,
            "rx_packets": self._rx_packets,
            "rx_frames": self._rx_frames,
            "rx_dropped": self._rx_dropped + self._reassembler.dropped_frames,
            "rx_recovered": self._reassembler.recovered_frames,
            "remote_resolved": is_full_destination(self.state.remote_datagram_b64),
            "control_closed": bool(dg_stats.get("control_closed", False)),
        }

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
            "media_ready": s.media_ready,
            "codecs": s.codecs,
            "uses_vp8": self.uses_vp8(),
            "uses_opus": self.uses_opus(),
            "audio_bitrate_kbps": self.prefs.audio_bitrate_kbps,
            "video_bitrate_kbps": self.prefs.video_bitrate_kbps,
            "video_fps": self.prefs.video_fps,
            "video_max_edge": self.prefs.video_max_edge,
            "stats": self.stats(),
        }

    async def start_outgoing(self, peer: str, *, video: bool) -> CallState:
        async with self._lock:
            if self.state.phase != CallPhase.IDLE:
                raise RuntimeError("A call is already in progress")
            prefs = load_call_prefs(self.profile)
            self.prefs = prefs
            call_id = new_call_id()
            self._generation += 1
            gen = self._generation
            self._reset_call_runtime()
            self.state = CallState(
                phase=CallPhase.OUTGOING,
                call_id=call_id,
                peer=peer,
                want_video=video,
                local_mic=prefs.start_mic,
                local_cam=video,
                local_spk=prefs.start_spk,
                codecs=local_offer_codecs(),
                max_bitrate=prefs.max_bitrate_bps,
            )
            self._emit("outgoing")
            self._setup_task = asyncio.create_task(
                self._outgoing_setup(gen, peer, call_id, video, prefs),
                name="call-outgoing-setup",
            )
            return self.state

    async def accept_incoming(self) -> CallState:
        async with self._lock:
            if self.state.phase != CallPhase.INCOMING:
                raise RuntimeError("No incoming call to accept")
            prefs = load_call_prefs(self.profile)
            self.prefs = prefs
            peer = self.state.peer
            call_id = self.state.call_id
            remote_token = self.state.remote_datagram_b64
            gen = self._generation
            setup = self._setup_task

        dg: Optional[DatagramSession] = self._dg
        if dg is None and setup is not None:
            try:
                dg = await asyncio.wait_for(setup, timeout=ACCEPT_SETUP_TIMEOUT_S)
            except asyncio.TimeoutError as exc:
                raise RuntimeError("DATAGRAM setup timed out") from exc
            except asyncio.CancelledError as exc:
                raise RuntimeError("Call ended while accepting") from exc
            except Exception as exc:
                raise RuntimeError(f"DATAGRAM setup failed: {exc}") from exc
        if dg is None:
            # Prefetch missing/failed — open now (hangup bumps generation).
            try:
                await self._await_previous_dg_close()
                dg = await self._open_datagram_impl(f"call-{uuid.uuid4().hex[:10]}")
            except asyncio.CancelledError as exc:
                raise RuntimeError("Call ended while accepting") from exc
            except Exception as exc:
                raise RuntimeError(f"DATAGRAM setup failed: {exc}") from exc

        async with self._lock:
            if self._generation != gen or self.state.phase != CallPhase.INCOMING:
                if dg is not None and dg is not self._dg:
                    self._schedule_dg_close(dg)
                raise RuntimeError("Call ended while accepting")
            assert dg is not None
            self._dg = dg
            self._setup_task = None
            self.state.local_datagram_b32 = dg.local_b32
            self.state.local_datagram_b64 = dg.local_destination_b64
            self.state.local_mic = prefs.start_mic
            self.state.local_spk = prefs.start_spk
            # Accepting a video call turns the camera on. The incoming screen says
            # so before the user accepts; a voice call leaves the camera off.
            self.state.local_cam = bool(self.state.want_video)
            self.state.max_bitrate = prefs.max_bitrate_bps
            token = datagram_token(dg)
            negotiated = intersect_codecs(local_offer_codecs(), self.state.codecs)
            self.state.codecs = negotiated
            accept = CallAccept(
                call_id=call_id,
                codecs=negotiated,
                max_bitrate=prefs.max_bitrate_bps,
                datagram_b32=token,
            )

        ok = await self.send_signal(peer, accept.encode())
        async with self._lock:
            if self._generation != gen or self.state.phase != CallPhase.INCOMING:
                raise RuntimeError("Call ended while accepting")
            if not ok:
                await self._teardown_unlocked("accept signal failed")
                raise RuntimeError("Could not send CALL_ACCEPT")
            self._apply_remote_token(remote_token or "", gen)
            self.state.phase = CallPhase.ACTIVE
            self._emit("active")
            await self._send_media_state_unlocked()
            return self.state

    async def reject_incoming(self, reason: str = "declined") -> None:
        peer = ""
        call_id = ""
        async with self._lock:
            if self.state.phase != CallPhase.INCOMING:
                return
            peer = self.state.peer
            call_id = self.state.call_id
            await self._begin_teardown_unlocked("rejected")
        if peer and call_id:
            try:
                await self.send_signal(peer, CallReject(call_id, reason).encode())
            except Exception:
                logger.debug("CALL_REJECT send failed", exc_info=True)
        await self._finish_teardown("rejected")

    async def hangup(self) -> None:
        peer = ""
        call_id = ""
        async with self._lock:
            if self.state.phase == CallPhase.IDLE:
                return
            peer = self.state.peer
            call_id = self.state.call_id
            await self._begin_teardown_unlocked("hangup")
        if peer and call_id:
            try:
                await self.send_signal(peer, CallHangup(call_id).encode())
            except Exception:
                logger.debug("CALL_HANGUP send failed", exc_info=True)
        await self._finish_teardown("hangup")

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

    def _peer_key(self, peer: str) -> str:
        return normalize_peer_address(peer) or (peer or "").strip().lower()

    def _invite_allowed(self, peer: str) -> bool:
        now = time.monotonic()
        self._invite_times = [t for t in self._invite_times if now - t < INVITE_WINDOW_S]
        if len(self._invite_times) >= INVITE_BURST:
            return False
        last = self._invite_peer_at.get(self._peer_key(peer))
        if last is not None and now - last < INVITE_COOLDOWN_S:
            return False
        return True

    def _note_invite(self, peer: str) -> None:
        now = time.monotonic()
        self._invite_times.append(now)
        self._invite_peer_at[self._peer_key(peer)] = now
        if len(self._invite_peer_at) > 64:
            cutoff = now - INVITE_WINDOW_S
            self._invite_peer_at = {
                key: seen for key, seen in self._invite_peer_at.items() if seen >= cutoff
            }

    async def handle_signal(self, peer: str, payload: str) -> bool:
        parsed = parse_call_signal(payload)
        if parsed is None:
            return False
        if not valid_call_id(parsed.call_id):
            return True
        if parsed.kind == CALL_INVITE:
            if not valid_datagram_token(parsed.datagram_b32):
                logger.debug("Ignoring call invite with a rejected datagram address")
                return True
            async with self._lock:
                if self.state.phase != CallPhase.IDLE or not self._invite_allowed(peer):
                    busy_peer = peer
                    busy_id = parsed.call_id
                else:
                    self._note_invite(peer)
                    self._generation += 1
                    gen = self._generation
                    self._reset_call_runtime()
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
                    self._setup_task = asyncio.create_task(
                        self._prefetch_datagram(gen),
                        name="call-incoming-prefetch",
                    )
                    return True
            await self.send_signal(busy_peer, CallReject(busy_id, "busy").encode())
            return True

        async with self._lock:
            if not same_i2p_destination(peer, self.state.peer):
                return True
            if parsed.kind == CALL_ACCEPT:
                if self.state.phase != CallPhase.OUTGOING or parsed.call_id != self.state.call_id:
                    return True
                if not valid_datagram_token(parsed.datagram_b32):
                    return True
                self.state.remote_datagram_b64 = parsed.datagram_b32
                self.state.codecs = intersect_codecs(
                    local_offer_codecs(), parsed.codecs or self.state.codecs
                )
                self.state.max_bitrate = min(self.state.max_bitrate, parsed.max_bitrate)
                self._apply_remote_token(parsed.datagram_b32, self._generation)
                self.state.phase = CallPhase.ACTIVE
                self._emit("active")
                await self._send_media_state_unlocked()
                return True
            if parsed.kind == CALL_REJECT:
                if parsed.call_id and parsed.call_id == self.state.call_id:
                    self.state.error = parsed.reason or "rejected"
                    await self._begin_teardown_unlocked("rejected")
                    # finish outside would need care; do full teardown here for signal path
                    await self._finish_teardown_unlocked("rejected")
                return True
            if parsed.kind == CALL_HANGUP:
                if parsed.call_id and parsed.call_id == self.state.call_id:
                    await self._begin_teardown_unlocked("remote_hangup")
                    await self._finish_teardown_unlocked("remote_hangup")
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

    async def send_vp8_frame(
        self, payload: bytes, *, keyframe: bool, ts_ms: Optional[int] = None
    ) -> None:
        if not (self.state.local_cam or self.state.local_screen):
            return
        if not codecs_include(self.state.codecs, "vp8"):
            return
        ptype = TYPE_VIDEO_KEY if keyframe else TYPE_VIDEO_DELTA
        await self._send_media(ptype, payload, ts_ms=ts_ms)

    async def request_keyframe(self) -> None:
        """Ask the peer for a VP8 keyframe. Empty datagram, no camera required."""
        if self.state.phase != CallPhase.ACTIVE:
            return
        await self._send_media(TYPE_KEYFRAME_REQ, b"", ts_ms=None)

    def uses_vp8(self) -> bool:
        return codecs_include(self.state.codecs, "vp8") and vp8_available()

    def uses_opus(self) -> bool:
        """Send Opus only when the peer advertised it (Android without an Opus codec does not)."""
        return codecs_include(self.state.codecs, "opus") and opus_available()

    async def _outgoing_setup(
        self,
        gen: int,
        peer: str,
        call_id: str,
        video: bool,
        prefs: CallPrefs,
    ) -> Optional[DatagramSession]:
        dg: Optional[DatagramSession] = None
        try:
            await self._await_previous_dg_close()
            dg = await self._open_datagram_impl(f"call-{uuid.uuid4().hex[:10]}")
            if self._generation != gen:
                await self._close_dg(dg)
                return None
            token = datagram_token(dg)
            invite = CallInvite(
                call_id=call_id,
                want_audio=True,
                want_video=video,
                codecs=local_offer_codecs(),
                max_bitrate=prefs.max_bitrate_bps,
                datagram_b32=token,
            )
            ok = await self.send_signal(peer, invite.encode())
            async with self._lock:
                if self._generation != gen or self.state.phase != CallPhase.OUTGOING:
                    await self._close_dg(dg)
                    return None
                if not ok:
                    self.state.error = "signal failed"
                    await self._begin_teardown_unlocked("signal failed")
                    await self._finish_teardown_unlocked("signal failed")
                    return None
                self._dg = dg
                self.state.local_datagram_b32 = dg.local_b32
                self.state.local_datagram_b64 = dg.local_destination_b64
                self._setup_task = None
                self._emit("outgoing")
                return dg
        except asyncio.CancelledError:
            if dg is not None:
                await self._close_dg(dg)
            raise
        except Exception as exc:
            logger.warning("Outgoing DATAGRAM setup failed: %s", exc, exc_info=True)
            if dg is not None:
                await self._close_dg(dg)
            async with self._lock:
                if self._generation == gen and self.state.phase == CallPhase.OUTGOING:
                    self.state.error = str(exc)
                    await self._begin_teardown_unlocked("setup failed")
                    await self._finish_teardown_unlocked("setup failed")
            return None

    async def _prefetch_datagram(self, gen: int) -> Optional[DatagramSession]:
        dg: Optional[DatagramSession] = None
        try:
            await self._await_previous_dg_close()
            dg = await self._open_datagram_impl(f"call-{uuid.uuid4().hex[:10]}")
            async with self._lock:
                if self._generation != gen or self.state.phase != CallPhase.INCOMING:
                    await self._close_dg(dg)
                    return None
                self._dg = dg
                self.state.local_datagram_b32 = dg.local_b32
                self.state.local_datagram_b64 = dg.local_destination_b64
                return dg
        except asyncio.CancelledError:
            if dg is not None:
                await self._close_dg(dg)
            raise
        except Exception as exc:
            logger.warning("Incoming DATAGRAM prefetch failed: %s", exc, exc_info=True)
            if dg is not None:
                await self._close_dg(dg)
            async with self._lock:
                if self._generation == gen and self._setup_task is asyncio.current_task():
                    self._setup_task = None
            return None

    async def _send_media(self, ptype: int, payload: bytes, *, ts_ms: Optional[int]) -> None:
        dg = self._dg
        if dg is None or self.state.phase != CallPhase.ACTIVE:
            return
        target = self._remote_send_target()
        if not target:
            self._tx_dropped += 1
            return
        if getattr(dg, "control_closed", False):
            self._tx_dropped += 1
            if not self._control_closed_logged:
                self._control_closed_logged = True
                logger.warning("Dropping media: SAM DATAGRAM control socket is closed")
                self.state.error = "datagram session closed"
                self._emit("media_error")
            return
        call_hash = call_id_hash16(self.state.call_id)
        now = int(time.time() * 1000) if ts_ms is None else int(ts_ms)
        is_audio = ptype in (TYPE_OPUS, TYPE_PCM16)
        if is_audio:
            # Dedicated counter: the receiver reads gaps as lost audio frames.
            self._audio_seq = (self._audio_seq + 1) & 0xFFFFFFFF
            frame_seq = self._audio_seq
            flags = FLAG_AUDIO_SEQ
        else:
            self._seq = (self._seq + 1) & 0xFFFFFFFF
            frame_seq = self._seq
            flags = 0
        chunks = fragment_payload(payload)
        total = len(chunks)
        packets = [
            MediaPacket(
                call_hash=call_hash,
                seq=frame_seq,
                ts_ms=now,
                ptype=ptype,
                flags=flags,
                payload=chunk,
                frag_idx=idx,
                frag_total=total,
            )
            for idx, chunk in enumerate(chunks)
        ]
        if VIDEO_PARITY_ENABLED and total >= 2 and ptype in _VIDEO_PTYPES:
            packets.append(
                MediaPacket(
                    call_hash=call_hash,
                    seq=frame_seq,
                    ts_ms=now,
                    ptype=TYPE_PARITY,
                    flags=0,
                    payload=build_parity_payload(ptype, chunks),
                )
            )
        for i, pkt in enumerate(packets):
            # Audio is never paced; only multi-packet video frames are spread out.
            if i and not is_audio:
                await asyncio.sleep(VIDEO_FRAGMENT_PACE_S)
            try:
                await dg.send(target, pkt.encode())
                self._tx_packets += 1
            except Exception:
                self._tx_dropped += 1
                logger.debug("DATAGRAM send failed", exc_info=True)
                return

    async def _on_datagram(self, from_dest: str, payload: bytes) -> None:
        if self.state.phase != CallPhase.ACTIVE or not self.state.call_id:
            self._rx_dropped += 1
            return
        pkt = decode_media_packet(payload)
        if pkt is None:
            self._rx_dropped += 1
            return
        if pkt.call_hash != call_id_hash16(self.state.call_id):
            self._rx_dropped += 1
            return
        if not same_datagram_sender(from_dest, self.state.remote_datagram_b64):
            self._rx_dropped += 1
            if not self._from_mismatch_logged:
                self._from_mismatch_logged = True
                logger.info("Dropping call media: sender does not match the peer")
            return
        self._rx_packets += 1
        if not self._first_rx_logged:
            self._first_rx_logged = True
            logger.info("Call media: first packet received (ptype=%d)", pkt.ptype)
            self._emit("media_flowing")
        body = self._reassembler.feed(pkt)
        if body is None:
            return
        # A frame finished by its parity datagram must be delivered as video
        # (or audio), not as TYPE_PARITY — the UI ignores that type.
        ptype = completed_ptype(pkt)
        self._rx_frames += 1
        self._emit(
            "media_packet",
            ptype=ptype,
            ts_ms=pkt.ts_ms,
            seq=pkt.seq,
            flags=0 if pkt.ptype == TYPE_PARITY else pkt.flags,
            payload=body,
            from_dest=from_dest,
        )

    async def _default_open_datagram(self, session_id: str) -> DatagramSession:
        return await create_datagram_session(
            session_id,
            sam_address=self.sam_address,
            destination=None,
            options=self._tunnel_options(),
            on_datagram=self._on_datagram,
        )

    def _tunnel_options(self) -> dict[str, str]:
        opts: dict[str, str] = {}
        if self.tunnel_length:
            length = str(max(1, min(7, int(self.tunnel_length))))
            opts["inbound.length"] = length
            opts["outbound.length"] = length
        qty = max(1, int(self.tunnel_quantity))
        opts["inbound.quantity"] = str(qty)
        opts["outbound.quantity"] = str(qty)
        opts["inbound.backupQuantity"] = str(CALL_TUNNEL_BACKUP_QUANTITY)
        opts["outbound.backupQuantity"] = str(CALL_TUNNEL_BACKUP_QUANTITY)
        return opts

    def _reset_call_runtime(self) -> None:
        self._reset_stats()
        self._reassembler = FrameReassembler()
        self._remote_b64_cache.clear()
        self._resolve_failures = 0
        self._next_resolve_at = 0.0
        self._control_closed_logged = False
        task = self._resolve_task
        self._resolve_task = None
        if task is not None and not task.done():
            task.cancel()

    def _apply_remote_token(self, token: str, gen: int) -> None:
        """Remember the peer's datagram address; never block the signal path on lookups."""
        token = (token or "").strip()
        if not valid_datagram_token(token):
            self.state.remote_datagram_b64 = ""
            self.state.media_ready = False
            self._emit("media_pending")
            return
        self.state.remote_datagram_b64 = token
        if is_full_destination(token):
            self.state.media_ready = True
            return
        self.state.media_ready = False
        if token:
            logger.info("Peer sent a short DATAGRAM address; resolving in background")
            self._schedule_resolve(token, gen)
        self._emit("media_pending")

    def _remote_send_target(self) -> str:
        """Destination string for ``DATAGRAM SEND`` (full base64 when known)."""
        remote = self.state.remote_datagram_b64
        if not remote:
            return ""
        if is_full_destination(remote):
            return remote
        cached = self._remote_b64_cache.get(remote)
        if cached:
            self.state.remote_datagram_b64 = cached
            self.state.media_ready = True
            return cached
        # i2pd 3.x resolves ``<b32>.b32.i2p`` itself in DATAGRAM SEND.
        self._schedule_resolve(remote, self._generation)
        target = b32_host_name(remote)
        if not valid_datagram_token(target):
            return ""
        return target

    def _schedule_resolve(self, token: str, gen: int) -> None:
        """Single-flight NAMING LOOKUP with exponential backoff."""
        task = self._resolve_task
        if task is not None and not task.done():
            return
        if time.monotonic() < self._next_resolve_at:
            return
        try:
            self._resolve_task = asyncio.get_running_loop().create_task(
                self._resolve_worker(token, gen),
                name="call-resolve-remote",
            )
        except RuntimeError:
            self._resolve_task = None

    async def _resolve_worker(self, token: str, gen: int) -> None:
        name = b32_host_name(token)
        try:
            b64 = await asyncio.wait_for(
                naming_lookup_b64(name, sam_address=self.sam_address),
                timeout=RESOLVE_TIMEOUT_S,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self._resolve_failures += 1
            delay = min(RESOLVE_BACKOFF_MAX_S, 2.0 ** self._resolve_failures)
            self._next_resolve_at = time.monotonic() + delay
            logger.info(
                "DATAGRAM naming lookup failed (%s); retry in %.0fs",
                type(exc).__name__,
                delay,
            )
            return
        if self._generation != gen or self.state.remote_datagram_b64 != token:
            return
        if not valid_datagram_token(b64):
            return
        self._remote_b64_cache[token] = b64
        self.state.remote_datagram_b64 = b64
        self.state.media_ready = is_full_destination(b64)
        self._emit("media_ready")

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

    def _cancel_setup_task(self) -> Optional[asyncio.Task[Optional[DatagramSession]]]:
        task = self._setup_task
        self._setup_task = None
        if task is not None and not task.done():
            task.cancel()
        return task

    async def _begin_teardown_unlocked(self, reason: str) -> None:
        """Bump generation, cancel setup, emit ending — close DG in finish."""
        self._generation += 1
        self.state.phase = CallPhase.ENDING
        self._cancel_setup_task()

    async def _finish_teardown(self, reason: str) -> None:
        async with self._lock:
            await self._finish_teardown_unlocked(reason)

    async def _finish_teardown_unlocked(self, reason: str) -> None:
        dg = self._dg
        self._dg = None
        peer = self.state.peer
        call_id = self.state.call_id
        err = self.state.error
        keep_err = reason in ("rejected", "signal failed", "setup failed")
        self.state = CallState(error=err if keep_err else "")
        self._reassembler = FrameReassembler()
        task = self._resolve_task
        self._resolve_task = None
        if task is not None and not task.done():
            task.cancel()
        self._emit("ended", reason=reason, peer=peer, call_id=call_id)
        if dg is not None:
            # Close off the signal path so hangup stays snappy, but the next
            # call must wait: opening a new DATAGRAM while the old reader is
            # still dying makes one direction (usually RX) go silent.
            self._schedule_dg_close(dg)

    async def _teardown_unlocked(self, reason: str) -> None:
        await self._begin_teardown_unlocked(reason)
        await self._finish_teardown_unlocked(reason)

    def _schedule_dg_close(self, dg: DatagramSession) -> None:
        prev = self._dg_close_task

        async def _run() -> None:
            if prev is not None and not prev.done():
                try:
                    await asyncio.wait_for(prev, timeout=2.0)
                except Exception:
                    logger.debug("previous datagram close", exc_info=True)
            await self._close_dg(dg)

        try:
            self._dg_close_task = asyncio.create_task(_run(), name="call-dg-close")
        except RuntimeError:
            self._dg_close_task = None

    async def _await_previous_dg_close(self) -> None:
        task = self._dg_close_task
        if task is None or task.done():
            return
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=2.5)
        except asyncio.TimeoutError:
            logger.info("previous call datagram still closing; opening a new session")
        except Exception:
            logger.debug("previous datagram close", exc_info=True)

    @staticmethod
    async def _close_dg(dg: DatagramSession) -> None:
        try:
            await dg.close()
        except Exception:
            logger.debug("DATAGRAM close failed", exc_info=True)
