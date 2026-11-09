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


def test_a_peer_that_was_never_secure_is_not_dialled():
    book = AutoReconnectBook()
    assert book.tick(1000, secure=set(), jitter=_still) == []
