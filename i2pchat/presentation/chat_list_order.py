"""Shared Chats-list ordering: pinned first, then MRU by last activity."""

from __future__ import annotations

from typing import Protocol


class _ChatRow(Protocol):
    pinned: bool
    pin_order: int
    last_activity_ts: str


def chat_list_sort_key(row: _ChatRow) -> tuple[int, int, str]:
    """Sort key for ascending sort: pinned (0) before unpinned (1).

    Among pinned, lower ``pin_order`` is higher. Among unpinned, newer
    ``last_activity_ts`` sorts first when the key is used with a reverse on the
    timestamp component — callers should use :func:`sort_chat_rows`.
    """
    if row.pinned:
        return (0, int(row.pin_order), "")
    # Invert lexicographic ISO timestamps via empty-last + descending in sort_chat_rows.
    return (1, 0, row.last_activity_ts or "")


def sort_chat_rows(rows: list) -> list:
    """Return a new list: pinned by pin_order, then unpinned by last_activity_ts desc."""
    pinned = [r for r in rows if getattr(r, "pinned", False)]
    unpinned = [r for r in rows if not getattr(r, "pinned", False)]
    pinned.sort(key=lambda r: int(getattr(r, "pin_order", 0)))
    unpinned.sort(
        key=lambda r: str(getattr(r, "last_activity_ts", "") or ""),
        reverse=True,
    )
    return pinned + unpinned
