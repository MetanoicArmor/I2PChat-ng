"""Status page wiring in ChatWindow: service notices stay out of the chat transcript."""

from __future__ import annotations

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from datetime import datetime, timezone

import pytest

pytest.importorskip("PyQt6.QtWidgets", exc_type=ImportError)

from PyQt6.QtWidgets import QApplication

from i2pchat.core.i2p_chat_core import ChatMessage


@pytest.fixture
def qapp() -> QApplication:
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    return app


@pytest.fixture
def window(qapp: QApplication, monkeypatch: pytest.MonkeyPatch):
    import i2pchat.gui.main_qt as main_qt

    monkeypatch.setattr(main_qt, "save_verbose_log_enabled", lambda _on: None)
    w = main_qt.ChatWindow(profile="default", theme_id=main_qt.THEME_DEFAULT)
    yield w
    w._status_refresh_timer.stop()


def _chat_texts(w) -> list[str]:
    model = w.chat_model
    return [model.item_at(i).text for i in range(model.rowCount())]


def test_window_opens_on_status_page(window) -> None:
    assert window._status_page_visible()
    window._show_chat_page()
    assert not window._status_page_visible()


def test_core_service_messages_do_not_enter_chat(window) -> None:
    window._show_chat_page()
    before = _chat_texts(window)
    window.handle_core_system("Handshake sent. Establishing secure channel... Wait")
    window.handle_core_system("Tunnels ready.")
    window.handle_error("Connection timed out while connecting to peer")
    window.handle_message(ChatMessage(
            kind="info", text="Peer disconnected.", timestamp=datetime.now(timezone.utc)
        ))
    assert _chat_texts(window) == before
    user_texts = [n.text for _, n in window._notice_log.user]
    assert "Tunnels ready." in user_texts
    assert any(t.startswith("Connection timed out") for t in user_texts)
    assert not any(t.startswith("Handshake sent") for t in user_texts)
    verbose_texts = [n.text for _, n in window._notice_log.verbose]
    assert any(t.startswith("Handshake sent") for t in verbose_texts)


def test_local_feedback_and_unknown_errors_stay_in_chat(window) -> None:
    window.handle_system("My address copied to clipboard.")
    window.handle_error("Could not open group: g1")
    texts = _chat_texts(window)
    assert "My address copied to clipboard." in texts
    assert "Could not open group: g1" in texts


def test_verbose_toggle_shows_technical_log(window) -> None:
    window.handle_core_system("BlindBox: root rotated")
    window.status_panel._verbose_check.setChecked(True)
    assert "BlindBox: root rotated" in window.status_panel._verbose.toPlainText()
