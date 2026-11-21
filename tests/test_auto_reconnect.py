"""Background redial only for peers that were already secure this run."""

from i2pchat.core.session_manager import AutoReconnectBook, auto_reconnect_delay


def _still(delay: float) -> float:
    return 0.0


def test_delays_double_and_cap_at_three_minutes():
    assert [auto_reconnect_delay(n) for n in range(1, 8)] == [20, 40, 80, 160, 180, 180, 180]


def test_a_secure_peer_is_redialled_on_the_20_40_80_schedule():
    book = AutoReconnectBook()
    book.note_secure("peer.b32.i2p")
    assert book.tick(0, secure=set(), jitter=_still) == []
    assert book.waiting("peer.b32.i2p")
    assert book.tick(19, secure=set(), jitter=_still) == []
    assert book.tick(20, secure=set(), jitter=_still) == ["peer.b32.i2p"]
    book.finished("peer.b32.i2p", secure=False)
    assert book.tick(59, secure=set(), jitter=_still) == []
    assert book.tick(60, secure=set(), jitter=_still) == ["peer.b32.i2p"]


def test_success_resets_and_a_user_hangup_stops_the_redial():
    book = AutoReconnectBook()
    book.note_secure("peer.b32.i2p")
    book.tick(0, secure=set(), jitter=_still)
    book.tick(20, secure=set(), jitter=_still)
    book.finished("peer.b32.i2p", secure=True)
    assert book.tick(21, secure={"peer.b32.i2p"}, jitter=_still) == []
    book.tick(22, secure=set(), jitter=_still)
    assert book.tick(41, secure=set(), jitter=_still) == []
    assert book.tick(42, secure=set(), jitter=_still) == ["peer.b32.i2p"]
    book.finished("peer.b32.i2p", secure=False)
    book.note_user_disconnect("peer.b32.i2p")
    assert book.tick(1000, secure=set(), jitter=_still) == []


def test_nudge_makes_the_next_tick_dial():
    book = AutoReconnectBook()
    book.note_secure("peer.b32.i2p")
    book.tick(0, secure=set(), jitter=_still)
    assert book.tick(1, secure=set(), jitter=_still) == []
    book.nudge("peer.b32.i2p", 1)
    assert book.tick(1, secure=set(), jitter=_still) == ["peer.b32.i2p"]


def test_a_peer_that_was_never_secure_is_not_dialled():
    book = AutoReconnectBook()
    assert book.tick(1000, secure=set(), jitter=_still) == []


def test_channel_desync_nudges_reconnect_without_marking_delivery_failed():
    """Desync tear-down should dial ASAP and leave awaiting_ack as non-failed."""
    from unittest.mock import MagicMock

    from i2pchat.core.i2p_chat_core import I2PChatCore
    from i2pchat.protocol.message_delivery import DELIVERY_STATE_FAILED

    core = object.__new__(I2PChatCore)
    core._auto_reconnect = AutoReconnectBook()
    core._auto_reconnect.note_secure("peer.b32.i2p")
    core._auto_reconnect_task = MagicMock()
    core._auto_reconnect_task.done = lambda: False
    errors: list[str] = []
    delivery_updates: list[str] = []
    core._emit_error = errors.append  # type: ignore[method-assign]
    core._schedule_disconnect = MagicMock()  # type: ignore[method-assign]
    core._normalize_peer_addr = lambda p: p  # type: ignore[method-assign]
    core._peer_protocol_err_suffix = I2PChatCore._peer_protocol_err_suffix  # type: ignore[method-assign]
    core._ensure_auto_reconnect = MagicMock()  # type: ignore[method-assign]
    core._emit_outbound_delivery_update = (  # type: ignore[method-assign]
        lambda *a, **k: delivery_updates.append(str(k.get("delivery_state") or a[1:2]))
    )

    # Freeze nudge "now" so due > 0 (tick treats due<=0 as "arm backoff").
    import time as time_mod

    real_mono = time_mod.monotonic
    time_mod.monotonic = lambda: 10.0  # type: ignore[assignment]
    try:
        I2PChatCore._handle_channel_desync(
            core,
            "peer.b32.i2p",
            seq_got=5,
            seq_expected=4,
            err_peer="peer.b32.i2p",
        )
    finally:
        time_mod.monotonic = real_mono  # type: ignore[assignment]

    assert errors and "Secure channel out of sync" in errors[0]
    assert "seq got 5, expected 4" in errors[0]
    core._schedule_disconnect.assert_called_once_with("peer.b32.i2p")
    core._ensure_auto_reconnect.assert_called_once()
    assert core._auto_reconnect.tick(10.0, secure=set(), jitter=_still) == ["peer.b32.i2p"]
    assert DELIVERY_STATE_FAILED not in delivery_updates
    assert delivery_updates == []
