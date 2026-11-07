"""TUI chat log shows user-level notices; technical ones only with verbose log on."""

from __future__ import annotations

from i2pchat.gui.chat_python import I2PChat
from i2pchat.presentation.notices import NoticeLog


def _app(verbose: bool) -> tuple[I2PChat, list[tuple[str, str]]]:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app._notice_log = NoticeLog()
    app._verbose_log_enabled = verbose
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]
    app._refresh_status_bar = lambda: None  # type: ignore[method-assign]
    return app, posted


def test_technical_notices_hidden_without_verbose() -> None:
    app, posted = _app(verbose=False)
    app.handle_system("Handshake sent. Establishing secure channel... Wait")
    app.handle_system("BlindBox: root rotated")
    assert posted == []
    assert len(app._notice_log.verbose) == 2


def test_user_notices_and_unknown_errors_reach_chat() -> None:
    app, posted = _app(verbose=False)
    app.handle_system("Tunnels ready.")
    app.handle_error("Connection timed out while connecting")
    app.handle_error("Something unexpected")
    kinds = [kind for kind, _ in posted]
    assert kinds == ["success", "info", "error"]
    assert posted[-1] == ("error", "Something unexpected")


def test_verbose_shows_raw_core_text() -> None:
    app, posted = _app(verbose=True)
    app.handle_system("Handshake sent. Establishing secure channel... Wait")
    assert posted == [("system", "Handshake sent. Establishing secure channel... Wait")]
