"""TUI compose box: Enter sends, Shift/Ctrl/Alt+Enter and Ctrl+J insert a newline."""

from __future__ import annotations

import asyncio

from textual.app import App, ComposeResult

from i2pchat.gui.chat_python import ComposeTextArea


class _ComposeApp(App):
    def __init__(self) -> None:
        super().__init__()
        self.submitted: list[str] = []

    def compose(self) -> ComposeResult:
        yield ComposeTextArea("", id="box")

    def on_compose_text_area_submitted(self, event: ComposeTextArea.Submitted) -> None:
        self.submitted.append(event.text_area.text)


async def _run(keys: list[str]) -> tuple[str, list[str]]:
    app = _ComposeApp()
    async with app.run_test() as pilot:
        box = app.query_one("#box", ComposeTextArea)
        box.focus()
        await pilot.press(*keys)
        await pilot.pause()
        return box.text, app.submitted


def test_enter_submits_without_inserting_newline() -> None:
    text, submitted = asyncio.run(_run(["h", "i", "enter"]))
    assert submitted == ["hi"]
    assert text == "hi"


def test_newline_keys_insert_line_break() -> None:
    for key in ("shift+enter", "ctrl+enter", "alt+enter", "ctrl+j"):
        text, submitted = asyncio.run(_run(["a", key, "b"]))
        assert text == "a\nb", key
        assert submitted == [], key
