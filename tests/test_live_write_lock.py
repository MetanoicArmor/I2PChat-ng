"""Regression: live text send must serialize with outgoing file transfer writes."""

from __future__ import annotations

import asyncio
import unittest
from unittest.mock import MagicMock

from i2pchat.core.live_peer_session import LivePeerSession


class _FakeWriter:
    def __init__(self) -> None:
        self.events: list[str] = []
        self._drain_depth = 0
        self.max_drain_depth = 0

    def write(self, data: bytes) -> None:
        self.events.append(f"write:{len(data)}")

    async def drain(self) -> None:
        self._drain_depth += 1
        self.max_drain_depth = max(self.max_drain_depth, self._drain_depth)
        self.events.append("drain_enter")
        await asyncio.sleep(0.02)
        self.events.append("drain_leave")
        self._drain_depth -= 1


class TestLiveWriteLock(unittest.IsolatedAsyncioTestCase):
    async def test_concurrent_drains_do_not_overlap(self) -> None:
        sess = LivePeerSession(peer_id="peer.b32.i2p")
        writer = _FakeWriter()

        async def chunk_loop(n: int) -> None:
            for _ in range(n):
                async with sess.write_lock:
                    writer.write(b"x" * 16)
                    await writer.drain()

        await asyncio.gather(chunk_loop(5), chunk_loop(5))
        self.assertEqual(writer.max_drain_depth, 1)
        self.assertEqual(writer.events.count("drain_enter"), 10)

    async def test_file_send_lock_queues_second_sender(self) -> None:
        sess = LivePeerSession(peer_id="peer.b32.i2p")
        order: list[str] = []

        async def hold(name: str) -> None:
            async with sess.file_send_lock:
                order.append(f"{name}-start")
                await asyncio.sleep(0.03)
                order.append(f"{name}-end")

        await asyncio.gather(hold("a"), hold("b"))
        self.assertEqual(order, ["a-start", "a-end", "b-start", "b-end"])


if __name__ == "__main__":
    unittest.main()
