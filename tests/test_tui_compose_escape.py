"""TUI compose: ``//text`` is sent as plain text; received images are not drawn as art."""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import patch

import pytest

pytest.importorskip("textual.app", exc_type=ImportError)

from i2pchat.gui.chat_python import I2PChat  # noqa: E402


def _compose_app(text: str) -> tuple[I2PChat, list[str], list[str]]:
    app = object.__new__(I2PChat)
    sent: list[str] = []
    commands: list[str] = []
    app._active_group_id = None
    app._compose_draft_active_key = None
    app._compose_drafts = {}
    app._compose_text_snapshot = lambda: text  # type: ignore[method-assign]
    app._set_compose_text = lambda *_a, **_k: None  # type: ignore[method-assign]
    app._flush_compose_drafts_to_disk = lambda: None  # type: ignore[method-assign]
    app._refresh_status_bar = lambda: None  # type: ignore[method-assign]
    app._current_target_peer = lambda: None  # type: ignore[method-assign]
    app.post = lambda *_a, **_k: None  # type: ignore[method-assign]

    async def _execute_command(cmd: str) -> None:
        commands.append(cmd)

    async def _send_text(body: str, peer_address=None):  # type: ignore[no-untyped-def]
        sent.append(body)
        return SimpleNamespace(accepted=True, hint="")

    app._execute_command = _execute_command  # type: ignore[method-assign]
    app.core = SimpleNamespace(send_text=_send_text)
    return app, sent, commands


@pytest.mark.parametrize(
    ("typed", "expected"),
    [
        ("//help", "/help"),
        ("//", "/"),
        ("///x", "//x"),
        ("//path/to\nsecond line", "/path/to\nsecond line"),
    ],
)
def test_double_slash_is_sent_as_text_with_one_slash_stripped(typed: str, expected: str) -> None:
    app, sent, commands = _compose_app(typed)
    asyncio.run(I2PChat._submit_compose(app))
    assert sent == [expected]
    assert commands == []


def test_single_slash_still_runs_a_command() -> None:
    app, sent, commands = _compose_app("/help")
    asyncio.run(I2PChat._submit_compose(app))
    assert commands == ["/help"]
    assert sent == []


def test_unknown_command_and_help_mention_escape() -> None:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app._compose_draft_active_key = None
    app._set_compose_text = lambda *_a, **_k: None  # type: ignore[method-assign]
    app._flush_compose_drafts_to_disk = lambda: None  # type: ignore[method-assign]
    app._refresh_status_bar = lambda: None  # type: ignore[method-assign]
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]

    asyncio.run(I2PChat._execute_command(app, "/nosuchcmd"))
    assert posted[-1][0] == "error"
    assert "//" in posted[-1][1]

    posted.clear()
    I2PChat.show_help(app)
    assert any("//" in text for _kind, text in posted)


def test_received_inline_image_is_reported_not_drawn(tmp_path) -> None:  # type: ignore[no-untyped-def]
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]
    path = str(tmp_path / "photo.png")

    with patch("i2pchat.storage.media_prefs.load_media_prefs", return_value=(0, 0, False)):
        with patch.object(I2PChat, "chat_log", create=True) as log:
            I2PChat._render_inline_image(app, path, is_from_me=False, label="Peer")

    log.write.assert_not_called()
    assert posted == [("system", f"Image received from Peer, saved at {path}")]
