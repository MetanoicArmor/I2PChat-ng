"""Cancelable call DATAGRAM setup: hangup must not wait on SAM SESSION CREATE."""

from __future__ import annotations

import asyncio
import unittest
from dataclasses import dataclass, field

from unittest import mock

from i2pchat.core import call_session as call_session_mod
from i2pchat.core.call_session import CallPhase, CallSessionManager
from i2pchat.core.call_signals import CallAccept, CallInvite, call_id_hash16, parse_call_signal
from i2pchat.core.media_framing import TYPE_PCM16, MediaPacket, fragment_payload


@dataclass
class FakeDatagram:
    local_destination_b64: str = "A" * 120
    local_b32: str = "b" * 52 + ".b32.i2p"
    local_udp_port: int = 12345
    closed: bool = False
    sent: list[tuple[str, bytes]] = field(default_factory=list)

    async def send(self, destination_b64_or_b32: str, payload: bytes) -> None:
        self.sent.append((destination_b64_or_b32, payload))

    async def close(self) -> None:
        self.closed = True


class CallSessionCancelTests(unittest.IsolatedAsyncioTestCase):
    async def test_hangup_during_outgoing_setup_is_fast(self) -> None:
        opened = asyncio.Event()
        release = asyncio.Event()
        signals: list[str] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            opened.set()
            await release.wait()
            return FakeDatagram()

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        await mgr.start_outgoing("peer.b32.i2p", video=False)
        self.assertEqual(mgr.state.phase, CallPhase.OUTGOING)
        await asyncio.wait_for(opened.wait(), timeout=1.0)

        await asyncio.wait_for(mgr.hangup(), timeout=1.0)
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)
        self.assertTrue(any(s.startswith("CALL_HANGUP|") for s in signals))

        release.set()
        await asyncio.sleep(0.05)
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)
        self.assertFalse(any(s.startswith("CALL_INVITE|") for s in signals))

    async def test_accept_after_prefetch_sends_call_accept(self) -> None:
        open_count = 0
        signals: list[str] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            nonlocal open_count
            open_count += 1
            await asyncio.sleep(0.01)
            return FakeDatagram(local_destination_b64="P" * 100, local_b32="zzzz.b32.i2p")

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        events: list[str] = []

        def on_event(kind: str, payload: dict) -> None:
            events.append(kind)

        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            on_event=on_event,
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        invite = CallInvite(
            call_id="cid-1",
            want_audio=True,
            want_video=False,
            codecs="opus,jpeg",
            max_bitrate=320000,
            datagram_b32="R" * 100,
        )
        await mgr.handle_signal("peer.b32.i2p", invite.encode())
        self.assertEqual(mgr.state.phase, CallPhase.INCOMING)
        self.assertIn("incoming", events)

        for _ in range(50):
            if mgr._dg is not None:
                break
            await asyncio.sleep(0.02)
        self.assertIsNotNone(mgr._dg)

        state = await mgr.accept_incoming()
        self.assertEqual(state.phase, CallPhase.ACTIVE)
        self.assertEqual(open_count, 1)
        self.assertTrue(any(s.startswith("CALL_ACCEPT|") for s in signals))
        parsed = parse_call_signal(next(s for s in signals if s.startswith("CALL_ACCEPT|")))
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(parsed.call_id, "cid-1")

    async def test_hangup_during_accept_cancels_without_accept_signal(self) -> None:
        opened = asyncio.Event()
        release = asyncio.Event()
        signals: list[str] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            opened.set()
            await release.wait()
            return FakeDatagram()

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        invite = CallInvite(
            call_id="cid-2",
            want_audio=True,
            want_video=True,
            codecs="opus,jpeg",
            max_bitrate=320000,
            datagram_b32="Q" * 100,
        )
        await mgr.handle_signal("peer.b32.i2p", invite.encode())
        await asyncio.wait_for(opened.wait(), timeout=1.0)

        accept_task = asyncio.create_task(mgr.accept_incoming())
        await asyncio.sleep(0.05)
        await asyncio.wait_for(mgr.hangup(), timeout=1.0)
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)

        release.set()
        with self.assertRaises(Exception):
            await asyncio.wait_for(accept_task, timeout=2.0)
        self.assertFalse(any(s.startswith("CALL_ACCEPT|") for s in signals))


class CallMediaPathTests(unittest.IsolatedAsyncioTestCase):
    async def _active_outgoing(self, accept_token: str, **kw):
        dg = FakeDatagram()
        signals: list[str] = []
        events: list[tuple[str, dict]] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            return dg

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            on_event=lambda k, p: events.append((k, p)),
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        await mgr.start_outgoing("peer.b32.i2p", video=False)
        for _ in range(50):
            if mgr._dg is not None:
                break
            await asyncio.sleep(0.01)
        call_id = mgr.state.call_id
        accept = CallAccept(call_id, "opus,jpeg", 250_000, accept_token)
        await mgr.handle_signal("peer.b32.i2p", accept.encode())
        return mgr, dg, signals, events

    async def test_invite_advertises_full_public_destination(self) -> None:
        mgr, dg, signals, _ = await self._active_outgoing("R" * 100)
        invite = next(s for s in signals if s.startswith("CALL_INVITE|"))
        parsed = parse_call_signal(invite)
        assert parsed is not None
        self.assertEqual(parsed.datagram_b32, dg.local_destination_b64)
        self.assertEqual(mgr.state.phase, CallPhase.ACTIVE)
        self.assertTrue(mgr.state.media_ready)
        await mgr.hangup()

    async def test_short_remote_token_sends_via_b32_and_looks_up_once(self) -> None:
        lookups: list[str] = []

        async def failing_lookup(name: str, *, sam_address):  # noqa: ANN001
            lookups.append(name)
            raise RuntimeError("Key not found")

        with mock.patch.object(call_session_mod, "naming_lookup_b64", failing_lookup):
            token = "q" * 52
            mgr, dg, _, _ = await self._active_outgoing(token)
            mgr._rx_packets = 1  # path is up; this test is about the send target
            self.assertFalse(mgr.state.media_ready)
            for _ in range(30):
                await mgr.send_audio_frame(b"\x00" * 640, opus=False)
                await asyncio.sleep(0)
            await asyncio.sleep(0.05)
            self.assertEqual(len(dg.sent), 30)
            self.assertTrue(all(dest == f"{token}.b32.i2p" for dest, _ in dg.sent))
            self.assertEqual(len(lookups), 1)  # single-flight + backoff, not per frame
            await mgr.hangup()

    async def test_lookup_success_switches_to_full_destination(self) -> None:
        full = "F" * 200

        async def ok_lookup(name: str, *, sam_address):  # noqa: ANN001
            return full

        with mock.patch.object(call_session_mod, "naming_lookup_b64", ok_lookup):
            mgr, dg, _, events = await self._active_outgoing("q" * 52)
            mgr._rx_packets = 1
            await mgr.send_audio_frame(b"\x00" * 640, opus=False)
            for _ in range(50):
                if mgr.state.media_ready:
                    break
                await asyncio.sleep(0.01)
            self.assertTrue(mgr.state.media_ready)
            await mgr.send_audio_frame(b"\x00" * 640, opus=False)
            self.assertEqual(dg.sent[-1][0], full)
            self.assertIn("media_ready", [k for k, _ in events])
            await mgr.hangup()

    async def test_large_frame_is_fragmented_and_reassembled_on_receive(self) -> None:
        mgr, dg, _, events = await self._active_outgoing("R" * 100)
        frame = bytes(range(256)) * 50  # 12.8 KB
        await mgr.send_jpeg_frame(frame)  # cam off -> ignored
        mgr.state.local_cam = True
        await mgr.send_jpeg_frame(frame)
        from i2pchat.core.media_framing import MAX_PAYLOAD

        self.assertGreater(len(dg.sent), 1)
        self.assertTrue(all(len(p) <= MAX_PAYLOAD + 64 for _, p in dg.sent))
        for _, raw in reversed(dg.sent):  # out-of-order delivery
            await mgr._on_datagram("R" * 100, raw)
        media = [p for k, p in events if k == "media_packet"]
        self.assertEqual(len(media), 1)
        self.assertEqual(media[0]["payload"], frame)
        self.assertEqual(media[0]["ptype"], 3)  # TYPE_VIDEO_JPEG
        self.assertIn("media_flowing", [k for k, _ in events])
        self.assertEqual(mgr.stats()["rx_frames"], 1)
        await mgr.hangup()

    async def test_parity_recovered_frame_is_delivered_as_video(self) -> None:
        from i2pchat.core.media_framing import TYPE_PARITY, TYPE_VIDEO_JPEG, decode_media_packet

        mgr, dg, _, events = await self._active_outgoing("R" * 100)
        frame = bytes(range(256)) * 20  # two fragments + parity
        mgr.state.local_cam = True
        await mgr.send_jpeg_frame(frame)
        decoded = [decode_media_packet(raw) for _, raw in dg.sent]
        video = [p for p in decoded if p is not None and p.ptype != TYPE_PARITY]
        parity = next(p for p in decoded if p is not None and p.ptype == TYPE_PARITY)
        self.assertGreaterEqual(len(video), 2)
        for pkt in video[:-1]:
            await mgr._on_datagram("R" * 100, pkt.encode())
        await mgr._on_datagram("R" * 100, parity.encode())
        media = [p for k, p in events if k == "media_packet"]
        self.assertEqual(len(media), 1)
        self.assertEqual(media[0]["ptype"], TYPE_VIDEO_JPEG)
        self.assertEqual(media[0]["payload"], frame)
        await mgr.hangup()

    async def test_cold_path_sends_one_frame_per_second(self) -> None:
        mgr, dg, _, _ = await self._active_outgoing("R" * 100)
        mgr.state.local_cam = True
        for _ in range(8):
            await mgr.send_audio_frame(b"\x00" * 640, opus=False)
            await mgr.send_jpeg_frame(b"\xff" * 20)
        self.assertEqual(len(dg.sent), 1)
        mgr._probe_at = 0.0
        await mgr.send_audio_frame(b"\x00" * 640, opus=False)
        self.assertEqual(len(dg.sent), 2)
        mgr._rx_packets = 1
        await mgr.send_audio_frame(b"\x00" * 640, opus=False)
        await mgr.send_audio_frame(b"\x00" * 640, opus=False)
        self.assertEqual(len(dg.sent), 4)
        await mgr.hangup()

    async def test_foreign_call_hash_is_dropped(self) -> None:
        mgr, _, _, events = await self._active_outgoing("R" * 100)
        pkt = MediaPacket(
            call_hash=call_id_hash16("other-call"),
            seq=1,
            ts_ms=1,
            ptype=TYPE_PCM16,
            flags=0,
            payload=b"\x00" * 10,
        )
        await mgr._on_datagram("R" * 100, pkt.encode())
        self.assertFalse(any(k == "media_packet" for k, _ in events))
        self.assertEqual(mgr.stats()["rx_dropped"], 1)
        await mgr.hangup()

    async def test_accept_from_another_peer_does_not_redirect_media(self) -> None:
        mgr, dg, _, _ = await self._active_outgoing("R" * 100)
        # _active_outgoing already completed the real accept. A second accept
        # from someone else must not move the call.
        evil = "E" * 120
        await mgr.handle_signal("mallory.b32.i2p", CallAccept(mgr.state.call_id, "opus,jpeg", 1, evil).encode())
        self.assertEqual(mgr.state.phase, CallPhase.ACTIVE)
        self.assertNotEqual(mgr.state.remote_datagram_b64, evil)
        await mgr.send_audio_frame(b"\x00" * 640, opus=False)
        self.assertTrue(dg.sent)
        self.assertNotEqual(dg.sent[-1][0], evil)
        await mgr.hangup()

    async def test_hangup_from_another_peer_is_ignored(self) -> None:
        from i2pchat.core.call_signals import CallHangup

        mgr, _, _, _ = await self._active_outgoing("R" * 100)
        await mgr.handle_signal("mallory.b32.i2p", CallHangup(mgr.state.call_id).encode())
        self.assertEqual(mgr.state.phase, CallPhase.ACTIVE)
        await mgr.hangup()

    async def test_datagram_from_another_destination_is_dropped(self) -> None:
        mgr, _, _, events = await self._active_outgoing("R" * 100)
        pkt = MediaPacket(
            call_hash=call_id_hash16(mgr.state.call_id),
            seq=1,
            ts_ms=1,
            ptype=TYPE_PCM16,
            flags=0,
            payload=b"\x00" * 10,
        )
        await mgr._on_datagram("S" * 100, pkt.encode())
        self.assertFalse(any(k == "media_packet" for k, _ in events))
        await mgr.hangup()

    async def test_media_before_accept_is_dropped(self) -> None:
        signals: list[str] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            return FakeDatagram()

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        events: list[tuple[str, dict]] = []
        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            on_event=lambda k, p: events.append((k, p)),
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        invite = CallInvite("cid-9", True, True, "opus,jpeg", 320000, "R" * 100)
        await mgr.handle_signal("peer.b32.i2p", invite.encode())
        self.assertEqual(mgr.state.phase, CallPhase.INCOMING)
        pkt = MediaPacket(
            call_hash=call_id_hash16("cid-9"),
            seq=1,
            ts_ms=1,
            ptype=TYPE_PCM16,
            flags=0,
            payload=b"\x00" * 8,
        )
        await mgr._on_datagram("R" * 100, pkt.encode())
        self.assertFalse(any(k == "media_packet" for k, _ in events))
        state = await mgr.accept_incoming()
        self.assertTrue(state.local_cam)
        self.assertTrue(state.want_video)
        await mgr.hangup()

    async def test_bad_invite_does_not_open_tunnels_and_repeat_is_busy(self) -> None:
        opened = 0
        signals: list[str] = []

        async def open_dg(session_id: str) -> FakeDatagram:
            nonlocal opened
            opened += 1
            return FakeDatagram()

        async def send_signal(peer: str, body: str) -> bool:
            signals.append(body)
            return True

        mgr = CallSessionManager(
            sam_address=("127.0.0.1", 7656),
            send_signal=send_signal,
            open_datagram=open_dg,  # type: ignore[arg-type]
        )
        poisoned = CallInvite("cid-3", True, False, "opus,jpeg", 1, "bad\nSESSION")
        await mgr.handle_signal("peer.b32.i2p", poisoned.encode())
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)
        self.assertEqual(opened, 0)

        ok = CallInvite("cid-4", True, False, "opus,jpeg", 1, "R" * 100)
        await mgr.handle_signal("peer.b32.i2p", ok.encode())
        self.assertEqual(mgr.state.phase, CallPhase.INCOMING)
        for _ in range(50):
            if opened:
                break
            await asyncio.sleep(0.01)
        self.assertEqual(opened, 1)
        await mgr.reject_incoming()
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)

        again = CallInvite("cid-5", True, False, "opus,jpeg", 1, "R" * 100)
        await mgr.handle_signal("peer.b32.i2p", again.encode())
        self.assertEqual(mgr.state.phase, CallPhase.IDLE)
        self.assertTrue(any(s.endswith("|busy") for s in signals))
        self.assertEqual(opened, 1)


if __name__ == "__main__":
    unittest.main()
