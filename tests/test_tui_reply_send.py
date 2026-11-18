"""TUI ``/reply <n> <text>`` sends quote + answer as one message; ``/reply <n>`` only fills compose."""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

pytest.importorskip("textual.app", exc_type=ImportError)

from i2pchat.gui.chat_python import I2PChat, RecentMessageRef  # noqa: E402
from i2pchat.presentation.reply_format import format_reply_quote  # noqa: E402


def _app(monkeypatch: pytest.MonkeyPatch) -> tuple[I2PChat, list[str], list[tuple[str, str]], list[str]]:
    monkeypatch.setattr(I2PChat, "compose_box", SimpleNamespace(text=""))
    app = object.__new__(I2PChat)
    sent: list[str] = []
    posted: list[tuple[str, str]] = []
    composed: list[str] = []
    app._active_group_id = None
    app._compose_draft_active_key = None
    app._compose_drafts = {}
    app._recent_messages = [  # type: ignore[assignment]
        RecentMessageRef(
            ref_id=3, sender="Peer", text="orig\nline", peer=None, timestamp="12:00:00"
        )
    ]
    app._compose_text_snapshot = lambda: ""  # type: ignore[method-assign]
    app._set_compose_text = lambda text, *_a, **_k: composed.append(text)  # type: ignore[method-assign]
    app._flush_compose_drafts_to_disk = lambda: None  # type: ignore[method-assign]
    app._refresh_status_bar = lambda: None  # type: ignore[method-assign]
    app._current_target_peer = lambda: None  # type: ignore[method-assign]
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]

    async def _send_text(body: str, peer_address=None):  # type: ignore[no-untyped-def]
        sent.append(body)
        return SimpleNamespace(accepted=True, hint="")

    app.core = SimpleNamespace(send_text=_send_text)
    return app, sent, posted, composed


def test_reply_with_text_sends_quote_and_answer(monkeypatch: pytest.MonkeyPatch) -> None:
    app, sent, posted, composed = _app(monkeypatch)
    asyncio.run(I2PChat._execute_command(app, "/reply 3 it's fine, thanks"))
    assert sent == [format_reply_quote("Peer", "orig\nline") + "it's fine, thanks"]
    assert composed == [""]
    assert not any(kind == "error" for kind, _ in posted)


def test_reply_with_text_unknown_ref_sends_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    app, sent, posted, _composed = _app(monkeypatch)
    asyncio.run(I2PChat._execute_command(app, "/reply 99 hello"))
    assert sent == []
    assert posted[-1][0] == "error"


def test_reply_without_text_inserts_quote_into_compose(monkeypatch: pytest.MonkeyPatch) -> None:
    app, sent, _posted, composed = _app(monkeypatch)
    asyncio.run(I2PChat._execute_command(app, "/reply 3"))
    assert sent == []
    assert composed == [format_reply_quote("Peer", "orig\nline")]
