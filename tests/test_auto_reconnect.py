"""Background redial only for peers that were already secure this run."""

from i2pchat.core.session_manager import AutoReconnectBook, auto_reconnect_delay


def test_delays_step_by_ten_seconds_and_cap_at_one_minute():
    assert [auto_reconnect_delay(n) for n in range(1, 8)] == [10, 20, 30, 40, 50, 60, 60]


def test_a_secure_peer_is_redialled_on_the_10_20_30_schedule():
    book = AutoReconnectBook()
    book.note_secure("peer.b32.i2p")
    assert book.tick(0, secure=set()) == []
    assert book.waiting("peer.b32.i2p")
    assert book.tick(9, secure=set()) == []
    assert book.tick(10, secure=set()) == ["peer.b32.i2p"]
    book.finished("peer.b32.i2p", secure=False)
    assert book.tick(29, secure=set()) == []
    assert book.tick(30, secure=set()) == ["peer.b32.i2p"]


def test_success_resets_and_a_user_hangup_stops_the_redial():
    book = AutoReconnectBook()
    book.note_secure("peer.b32.i2p")
    book.tick(0, secure=set())
    book.tick(10, secure=set())
    book.finished("peer.b32.i2p", secure=True)
    assert book.tick(11, secure={"peer.b32.i2p"}) == []
    book.tick(12, secure=set())
    assert book.tick(21, secure=set()) == []
    assert book.tick(22, secure=set()) == ["peer.b32.i2p"]
    book.finished("peer.b32.i2p", secure=False)
    book.note_user_disconnect("peer.b32.i2p")
    assert book.tick(1000, secure=set()) == []


def test_a_peer_that_was_never_secure_is_not_dialled():
    book = AutoReconnectBook()
    assert book.tick(1000, secure=set()) == []
