"""Cancelable call DATAGRAM setup: hangup must not wait on SAM SESSION CREATE."""

from __future__ import annotations

import asyncio
import unittest
from dataclasses import dataclass, field

from i2pchat.core.call_session import CallPhase, CallSessionManager
from i2pchat.core.call_signals import CallInvite, parse_call_signal


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


if __name__ == "__main__":
    unittest.main()
