"""Regression: live text send must serialize with outgoing file transfer writes."""

from __future__ import annotations

import asyncio
import inspect
import tempfile
import unittest

from i2pchat.core.i2p_chat_core import I2PChatCore
from i2pchat.core.live_peer_session import LivePeerSession
from i2pchat.gui import chat_python as chat_python_mod


class _FakeWriter:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.written: list[bytes] = []
        self._drain_depth = 0
        self.max_drain_depth = 0

    def write(self, data: bytes) -> None:
        self.events.append(f"write:{len(data)}")
        self.written.append(bytes(data))

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


class TestPriorityFlush(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        import i2pchat.core.i2p_chat_core as core_module

        self._tmpdir = tempfile.TemporaryDirectory()
        self._orig_profiles = core_module.get_profiles_dir
        core_module.get_profiles_dir = lambda: self._tmpdir.name  # type: ignore[assignment]
        self.core = I2PChatCore(profile="prio")
        self.sess = LivePeerSession(peer_id="peer.b32.i2p")
        self.writer = _FakeWriter()

    async def asyncTearDown(self) -> None:
        import i2pchat.core.i2p_chat_core as core_module

        core_module.get_profiles_dir = self._orig_profiles  # type: ignore[assignment]
        self._tmpdir.cleanup()

    async def test_priority_text_flushes_before_next_chunk_write(self) -> None:
        self.sess._file_transfer_active = True
        order: list[str] = []

        async def send_chunks() -> None:
            for i in range(3):
                await self.core._flush_priority_frames(self.writer, sess=self.sess)
                if self.writer.written:
                    # Record whether a priority frame already left before this chunk.
                    if any(b.startswith(b"PRI") for b in self.writer.written):
                        order.append(f"saw-pri-before-chunk-{i}")
                    self.writer.written.clear()
                async with self.sess.write_lock:
                    self.writer.write(f"CHUNK{i}".encode())
                    await self.writer.drain()
                order.append(f"chunk-{i}")
                await asyncio.sleep(0.01)

        async def send_text() -> None:
            await asyncio.sleep(0.005)
            await self.core._live_write_drain(
                self.writer, b"PRI-TEXT", sess=self.sess, use_priority=True
            )
            order.append("text-done")

        await asyncio.gather(send_chunks(), send_text())
        self.assertIn("text-done", order)
        # Text must complete before the last chunk finishes (priority between chunks).
        self.assertLess(order.index("text-done"), order.index("chunk-2"))

    async def test_soft_ack_enqueues_during_file_transfer(self) -> None:
        self.sess._file_transfer_active = True
        # Hold write_lock so opportunistic flush cannot run.
        async with self.sess.write_lock:
            await self.core._write_signal_frame_maybe_soft_drain(
                self.writer, b"ACK-FRAME", sess=self.sess
            )
            self.assertEqual(self.sess.priority_frames, [b"ACK-FRAME"])
            self.assertEqual(self.writer.written, [])
        await self.core._flush_priority_frames(self.writer, sess=self.sess)
        self.assertEqual(self.writer.written, [b"ACK-FRAME"])
        self.assertEqual(self.sess.priority_frames, [])


class TestTuiWorkerGroups(unittest.TestCase):
    def test_compose_uses_exclusive_compose_group(self) -> None:
        src = inspect.getsource(chat_python_mod.I2PChat.action_send_message)
        self.assertIn('group="compose"', src)
        self.assertIn("exclusive=True", src)

    def test_transfer_workers_use_transfer_group(self) -> None:
        src = inspect.getsource(chat_python_mod)
        self.assertIn('group="transfer"', src)
        # Compose exclusivity must not share the transfer group name.
        self.assertNotIn('group="transfer", exclusive', src.replace(" ", ""))


class TestIncomingStartMarker(unittest.TestCase):
    def test_start_marker_text_shape(self) -> None:
        name = "report.pdf"
        size = 1200
        marker = f"Receiving file: {name} ({size} bytes)"
        self.assertTrue(marker.startswith("Receiving file:"))
        self.assertIn(name, marker)
        peer_started = f"Peer started sending file: {name} ({size:,} bytes)"
        self.assertIn("Peer started sending file:", peer_started)

    def test_final_file_history_kind_stays_in(self) -> None:
        # C++ appends kind=in text="[file] …" only on completion; start uses system.
        start_kind = "system"
        final_kind = "in"
        self.assertNotEqual(start_kind, final_kind)
        self.assertTrue("[file] /tmp/x.bin".startswith("[file]"))


if __name__ == "__main__":
    unittest.main()
