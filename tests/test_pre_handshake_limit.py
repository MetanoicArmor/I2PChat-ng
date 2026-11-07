"""Inbound streams that never finish the handshake are capped and time out."""

from __future__ import annotations

import asyncio
import os
import tempfile
import unittest
from unittest.mock import patch

from i2pchat.core import i2p_chat_core as core_mod
from i2pchat.core.i2p_chat_core import I2PChatCore
from i2pchat.core.live_peer_session import (
    LivePeerSession,
    max_pending_inbound_handshakes,
)


class _Writer:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def _session(peer: str, *, inbound: bool, complete: bool = False, awaiting_ui: bool = False):
    s = LivePeerSession(peer_id=peer, inbound=inbound)
    s.conn = (object(), _Writer())  # type: ignore[assignment]
    s.handshake_complete = complete
    s._hs_tofu_awaiting_ui = awaiting_ui
    return s


class PreHandshakeLimitTests(unittest.TestCase):
    def test_env_limit_is_clamped(self) -> None:
        with patch.dict(os.environ, {"I2PCHAT_MAX_PENDING_HANDSHAKES": "0"}):
            self.assertEqual(max_pending_inbound_handshakes(), 1)
        with patch.dict(os.environ, {"I2PCHAT_MAX_PENDING_HANDSHAKES": "999"}):
            self.assertEqual(max_pending_inbound_handshakes(), 16)
        with patch.dict(os.environ, {"I2PCHAT_MAX_PENDING_HANDSHAKES": "junk"}):
            self.assertEqual(max_pending_inbound_handshakes(), 3)

    def test_pending_count_ignores_outbound_complete_and_tofu_waiting(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            with patch("i2pchat.core.i2p_chat_core.get_profiles_dir", return_value=td):
                core = I2PChatCore(profile="pending", on_error=lambda _m: None)
                core._live_sessions["a" * 52] = _session("a" * 52, inbound=True)
                core._live_sessions["b" * 52] = _session("b" * 52, inbound=True)
                core._live_sessions["c" * 52] = _session("c" * 52, inbound=False)
                core._live_sessions["d" * 52] = _session("d" * 52, inbound=True, complete=True)
                core._live_sessions["e" * 52] = _session(
                    "e" * 52, inbound=True, awaiting_ui=True
                )
                self.assertEqual(core._pending_inbound_handshake_count(), 2)

    def test_deadline_drops_silent_inbound_stream(self) -> None:
        async def run() -> None:
            with tempfile.TemporaryDirectory() as td:
                with patch("i2pchat.core.i2p_chat_core.get_profiles_dir", return_value=td):
                    core = I2PChatCore(profile="deadline", on_error=lambda _m: None)
                    peer = "f" * 52
                    sess = _session(peer, inbound=True)
                    core._live_sessions[peer] = sess
                    dropped: list[str] = []

                    async def fake_disconnect(peer_id=None):
                        dropped.append(peer_id)
                        core._live_sessions.pop(peer_id, None)

                    with (
                        patch.object(core_mod, "PRE_HANDSHAKE_TIMEOUT", 0.01),
                        patch.object(core, "disconnect_peer", fake_disconnect),
                    ):
                        await core._pre_handshake_deadline(sess.conn, peer)
                    self.assertEqual(dropped, [peer])

        asyncio.run(run())

    def test_deadline_leaves_completed_handshake_alone(self) -> None:
        async def run() -> None:
            with tempfile.TemporaryDirectory() as td:
                with patch("i2pchat.core.i2p_chat_core.get_profiles_dir", return_value=td):
                    core = I2PChatCore(profile="done", on_error=lambda _m: None)
                    peer = "g" * 52
                    sess = _session(peer, inbound=True)
                    core._live_sessions[peer] = sess
                    called = False

                    async def fake_disconnect(peer_id=None):
                        nonlocal called
                        called = True

                    with (
                        patch.object(core_mod, "PRE_HANDSHAKE_TIMEOUT", 0.01),
                        patch.object(core, "disconnect_peer", fake_disconnect),
                    ):
                        sess.handshake_complete = True
                        await core._pre_handshake_deadline(sess.conn, peer)
                    self.assertFalse(called)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
