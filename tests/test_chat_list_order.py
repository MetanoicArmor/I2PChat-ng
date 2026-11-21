"""Unit tests for Chats list pin + MRU ordering."""

from __future__ import annotations

from dataclasses import dataclass

from i2pchat.presentation.chat_list_order import sort_chat_rows
from i2pchat.storage import contact_book as cb


PEER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PEER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PEER_C = "cccccccccccccccccccccccccccccccccccccccc"


@dataclass
class _Row:
    name: str
    pinned: bool = False
    pin_order: int = 0
    last_activity_ts: str = ""


def test_sort_chat_rows_pinned_then_mru() -> None:
    rows = [
        _Row("u1", last_activity_ts="2026-01-03T00:00:00Z"),
        _Row("p2", pinned=True, pin_order=1, last_activity_ts="2026-01-01T00:00:00Z"),
        _Row("u0", last_activity_ts="2026-01-04T00:00:00Z"),
        _Row("p1", pinned=True, pin_order=0, last_activity_ts="2026-01-09T00:00:00Z"),
        _Row("blank", last_activity_ts=""),
    ]
    assert [r.name for r in sort_chat_rows(rows)] == ["p1", "p2", "u0", "u1", "blank"]


def test_peer_pin_round_trip_json() -> None:
    book = cb.ContactBook(
        contacts=[
            cb.ContactRecord(addr=PEER_A, display_name="A", pinned=True, pin_order=2),
            cb.ContactRecord(addr=PEER_B, last_activity_ts="2026-02-01T00:00:00Z"),
        ]
    )
    raw = cb.book_to_json_dict(book)
    restored = cb.parse_book_from_json(raw)
    assert restored.contacts[0].pinned is True
    assert restored.contacts[0].pin_order == 2
    assert restored.contacts[1].pinned is False
    assert restored.contacts[1].last_activity_ts == "2026-02-01T00:00:00Z"


def test_group_ui_pin_and_activity_round_trip() -> None:
    book = cb.ContactBook()
    assert cb.set_group_pin(book, "g1", pinned=True, pin_order=0) is True
    assert cb.touch_group_activity(book, "g1", "2026-03-01T12:00:00+00:00") is True
    raw = cb.book_to_json_dict(book)
    restored = cb.parse_book_from_json(raw)
    meta = restored.group_ui["g1"]
    assert meta.pinned is True
    assert meta.pin_order == 0
    assert meta.last_activity_ts == "2026-03-01T12:00:00+00:00"


def test_set_peer_pin_updates_existing() -> None:
    book = cb.ContactBook(contacts=[cb.ContactRecord(addr=PEER_C)])
    assert cb.set_peer_pin(book, PEER_C, pinned=True, pin_order=5) is True
    assert book.contacts[0].pinned is True
    assert book.contacts[0].pin_order == 5
    assert cb.set_peer_pin(book, PEER_C, pinned=False) is True
    assert book.contacts[0].pinned is False
    assert book.contacts[0].pin_order == 0


def test_contact_records_sort_pinned_then_mru() -> None:
    pinned = cb.ContactRecord(addr=PEER_A, pinned=True, pin_order=1)
    older = cb.ContactRecord(addr=PEER_B, last_activity_ts="2026-01-01T00:00:00Z")
    newer = cb.ContactRecord(addr=PEER_C, last_activity_ts="2026-01-02T00:00:00Z")
    ordered = sort_chat_rows([older, newer, pinned])
    assert [r.addr for r in ordered] == [PEER_A, PEER_C, PEER_B]


def test_notifications_muted_peer_round_trip() -> None:
    book = cb.ContactBook(contacts=[cb.ContactRecord(addr=PEER_A)])
    assert cb.set_peer_notifications_muted(book, PEER_A, muted=True) is True
    raw = cb.book_to_json_dict(book)
    restored = cb.parse_book_from_json(raw)
    assert restored.contacts[0].notifications_muted is True
    assert cb.set_peer_notifications_muted(book, PEER_A, muted=False) is True
    assert book.contacts[0].notifications_muted is False


def test_notifications_muted_group_round_trip() -> None:
    book = cb.ContactBook()
    assert cb.set_group_notifications_muted(book, "g1", muted=True) is True
    raw = cb.book_to_json_dict(book)
    restored = cb.parse_book_from_json(raw)
    assert restored.group_ui["g1"].notifications_muted is True
    assert cb.set_group_notifications_muted(book, "g1", muted=False) is True
    assert "g1" not in book.group_ui
