import asyncio

import pytest


def test_tui_file_offer_auto_accepts() -> None:
    """Incoming files auto-accept into downloads; no modal (Kennedy g2387)."""
    from i2pchat.gui.chat_python import I2PChat

    app = object.__new__(I2PChat)
    app.core = None
    posted: list[tuple[str, str]] = []
    app.post = lambda kind, text, *a, **k: posted.append((kind, text))  # type: ignore[method-assign]

    async def _run() -> bool | str:
        return await I2PChat.handle_file_offer(app, "../../evil[bold].exe", 1234)

    assert asyncio.run(_run()) is True
    assert posted
    assert "../" not in posted[0][1]
    assert "\\[bold]" in posted[0][1]
    assert "downloads" in posted[0][1].lower()
