import asyncio

import pytest


@pytest.mark.parametrize("decision", [True, False])
def test_tui_file_offer_waits_for_user_decision(decision: bool) -> None:
    """The TUI must not accept a peer's file without the user saying so."""
    from i2pchat.gui.chat_python import I2PChat, TuiTrustDecisionScreen

    app = object.__new__(I2PChat)
    app.core = None
    posted: list[tuple[str, str]] = []
    pushed: list[object] = []
    app.post = lambda kind, text, *a, **k: posted.append((kind, text))  # type: ignore[method-assign]

    def _push_screen(screen, callback=None):  # type: ignore[no-untyped-def]
        pushed.append(screen)
        assert isinstance(screen, TuiTrustDecisionScreen)
        assert callback is not None
        callback(decision)

    app.push_screen = _push_screen  # type: ignore[method-assign]

    async def _run() -> bool:
        return await I2PChat.handle_file_offer(app, "../../evil[bold].exe", 1234)

    assert asyncio.run(_run()) is decision
    assert len(pushed) == 1
    # Peer-controlled name is sanitized and markup-escaped before display.
    assert "../" not in posted[0][1]
    assert "\\[bold]" in posted[0][1]
