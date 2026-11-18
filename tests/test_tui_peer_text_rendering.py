"""Peer-controlled text must reach the terminal without markup or control codes."""

import io
from unittest.mock import PropertyMock, patch

from rich.console import Console
from rich.text import Text

from i2pchat.gui.chat_python import I2PChat, strip_terminal_controls


def test_strip_terminal_controls_removes_control_bytes_keeps_layout() -> None:
    raw = "a\x1b]0;owned\x07b\x1b[2Jc\x9bd\ttab\nline\x00"
    out = strip_terminal_controls(raw)
    # Every C0/C1 control byte is gone; tab and newline survive. What is left
    # of the escape sequences is inert printable text.
    assert "\x1b" not in out and "\x07" not in out and "\x9b" not in out and "\x00" not in out
    assert out == "a]0;ownedb[2Jcd\ttab\nline"


class _Log:
    def __init__(self) -> None:
        self.items: list[object] = []

    def write(self, renderable, **_kw) -> None:  # type: ignore[no-untyped-def]
        self.items.append(renderable)


def _render(renderable) -> str:  # type: ignore[no-untyped-def]
    console = Console(width=80, record=True, file=io.StringIO(), color_system=None)
    console.print(renderable)
    return console.export_text()


def test_ascii_image_from_peer_is_not_rendered_in_terminal() -> None:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app._record_transfer_event = lambda *a, **k: None  # type: ignore[method-assign]
    app._peer_display_name = lambda peer: "Peer"  # type: ignore[method-assign]
    app._current_target_peer = lambda: None  # type: ignore[method-assign]
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]

    # Peer-supplied art must not be drawn; only a system note.
    art = "[/bold] [red]x[/red] \x1b]0;pwned\x07 ##"
    I2PChat.handle_image_received(app, art)

    assert len(posted) == 1
    assert posted[0][0] == "system"
    assert "not rendered" in posted[0][1]
    assert "\x1b" not in posted[0][1]
    assert "[/bold]" not in posted[0][1]


def test_post_strips_control_characters_from_peer_message() -> None:
    app = object.__new__(I2PChat)
    log = _Log()
    with patch.object(I2PChat, "chat_log", new_callable=PropertyMock, return_value=log):
        I2PChat.post(app, "system", "hello\x1b[31m [bold]world")
    assert len(log.items) == 1
    out = str(log.items[0])
    assert "\x1b" not in out
    assert "\\[bold]" in out
