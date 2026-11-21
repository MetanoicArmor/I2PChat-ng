"""
Encrypted-profile contact list (v2): MRU peers, display names, notes, last message preview.

File: ``profiles/<profile>/<profile>.contacts.json`` (v2; migrates from v1 list-of-strings).
At rest (v1.4.x+): NaCl SecretBox keyed off the profile identity, same model as
group records. Legacy plaintext JSON is still read and re-encrypted on save.
"""

from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass, field
from typing import Any, Optional

from i2pchat.storage.sealed_json import read_sealed_json, write_sealed_json

logger = logging.getLogger("i2pchat.contacts")

_CONTACT_HOST_RE = re.compile(r"^[a-z2-7]{40,80}$")
MAX_CONTACTS = 500
MAX_PEER_HISTORY_RETENTION_DAYS = 3650
BOOK_VERSION = 2
PREVIEW_MAX_LEN = 80
CONTACTS_STORE_MAGIC = b"I2CB"
CONTACTS_STORE_DOMAIN = b"I2PCHAT-CONTACTS"


def normalize_peer_address(raw: str) -> Optional[str]:
    """Canonical peer id: lowercase base32 host only (no ``.b32.i2p`` suffix)."""
    value = (raw or "").strip().lower()
    if not value:
        return None
    if value.endswith(".b32.i2p"):
        host = value[: -len(".b32.i2p")]
    else:
        host = value
    if not _CONTACT_HOST_RE.fullmatch(host):
        return None
    return host


def same_i2p_destination(left: str, right: str) -> bool:
    """
    True if two strings denote the same I2P destination.

    Accepts either bare base32 or legacy ``…b32.i2p``; compares canonical bare ids.
    """
    nl = normalize_peer_address(left)
    nr = normalize_peer_address(right)
    if nl is not None and nr is not None:
        return nl == nr
    return (left or "").strip().lower() == (right or "").strip().lower()


def coerce_peer_retention_days(raw: Any) -> int:
    """Normalize a stored/entered per-peer retention value to ``0..MAX`` (0 = use global)."""
    if isinstance(raw, bool):
        return 0
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return 0
    return max(0, min(MAX_PEER_HISTORY_RETENTION_DAYS, value))


@dataclass
class ContactRecord:
    addr: str
    display_name: str = ""
    note: str = ""
    last_preview: str = ""
    last_activity_ts: str = ""
    # Per-peer auto-delete: drop messages older than N days. 0 = no override (use global policy).
    history_retention_days: int = 0
    # Chats list: pinned rows stay above MRU; pin_order ranks among pinned (lower = higher).
    pinned: bool = False
    pin_order: int = 0
    # Skip system notifications for this peer when True.
    notifications_muted: bool = False

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "addr": self.addr,
            "display_name": self.display_name,
            "note": self.note,
            "last_preview": self.last_preview,
            "last_activity_ts": self.last_activity_ts,
        }
        if self.history_retention_days > 0:
            out["history_retention_days"] = self.history_retention_days
        if self.pinned:
            out["pinned"] = True
            out["pin_order"] = int(self.pin_order)
        if self.notifications_muted:
            out["notifications_muted"] = True
        return out

    @staticmethod
    def from_dict(d: Any) -> Optional["ContactRecord"]:
        if not isinstance(d, dict):
            return None
        addr = normalize_peer_address(str(d.get("addr", "")))
        if not addr:
            return None
        def _s(key: str) -> str:
            v = d.get(key, "")
            return v if isinstance(v, str) else str(v) if v is not None else ""

        pinned_raw = d.get("pinned", False)
        pinned = pinned_raw is True or pinned_raw == 1 or pinned_raw == "true"
        try:
            pin_order = int(d.get("pin_order", 0) or 0)
        except (TypeError, ValueError):
            pin_order = 0
        muted_raw = d.get("notifications_muted", False)
        muted = muted_raw is True or muted_raw == 1 or muted_raw == "true"
        return ContactRecord(
            addr=addr,
            display_name=_s("display_name").strip(),
            note=_s("note").strip(),
            last_preview=_s("last_preview")[:PREVIEW_MAX_LEN],
            last_activity_ts=_s("last_activity_ts"),
            history_retention_days=coerce_peer_retention_days(
                d.get("history_retention_days")
            ),
            pinned=bool(pinned),
            pin_order=max(0, pin_order),
            notifications_muted=bool(muted),
        )


@dataclass
class GroupUiMeta:
    """Local Chats UI fields for a group (not part of the signed GroupState)."""

    pinned: bool = False
    pin_order: int = 0
    last_activity_ts: str = ""
    notifications_muted: bool = False

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if self.pinned:
            out["pinned"] = True
            out["pin_order"] = int(self.pin_order)
        if self.last_activity_ts:
            out["last_activity_ts"] = self.last_activity_ts
        if self.notifications_muted:
            out["notifications_muted"] = True
        return out

    @staticmethod
    def from_dict(d: Any) -> "GroupUiMeta":
        if not isinstance(d, dict):
            return GroupUiMeta()
        pinned_raw = d.get("pinned", False)
        pinned = pinned_raw is True or pinned_raw == 1 or pinned_raw == "true"
        try:
            pin_order = int(d.get("pin_order", 0) or 0)
        except (TypeError, ValueError):
            pin_order = 0
        ts = d.get("last_activity_ts", "")
        muted_raw = d.get("notifications_muted", False)
        muted = muted_raw is True or muted_raw == 1 or muted_raw == "true"
        return GroupUiMeta(
            pinned=bool(pinned),
            pin_order=max(0, pin_order),
            last_activity_ts=ts if isinstance(ts, str) else str(ts or ""),
            notifications_muted=bool(muted),
        )


@dataclass
class ContactBook:
    contacts: list[ContactRecord] = field(default_factory=list)
    last_active_peer: Optional[str] = None
    # group_id -> local pin / last-activity for the Chats list
    group_ui: dict[str, GroupUiMeta] = field(default_factory=dict)

    def peer_index(self, addr: str) -> int:
        for i, r in enumerate(self.contacts):
            if r.addr == addr:
                return i
        return -1

    def get(self, addr: str) -> Optional[ContactRecord]:
        i = self.peer_index(addr)
        return self.contacts[i] if i >= 0 else None


def _coerce_last_active(raw: Any) -> Optional[str]:
    if raw is None or raw is False:
        return None
    if not isinstance(raw, str):
        return None
    return normalize_peer_address(raw)


def parse_book_from_json(data: Any) -> ContactBook:
    if not isinstance(data, dict):
        return ContactBook()
    ver = data.get("version", 1)
    raw_contacts = data.get("contacts")
    if not isinstance(raw_contacts, list):
        return ContactBook()

    records: list[ContactRecord] = []
    seen: set[str] = set()

    if ver == 1 or all(isinstance(x, str) for x in raw_contacts):
        for item in raw_contacts:
            if not isinstance(item, str):
                continue
            a = normalize_peer_address(item)
            if not a or a in seen:
                continue
            seen.add(a)
            records.append(ContactRecord(addr=a))
    else:
        for item in raw_contacts:
            rec = ContactRecord.from_dict(item)
            if rec is None or rec.addr in seen:
                continue
            seen.add(rec.addr)
            records.append(rec)

    lap = _coerce_last_active(data.get("last_active_peer"))
    if lap is not None and lap not in seen:
        lap = None

    group_ui: dict[str, GroupUiMeta] = {}
    raw_groups = data.get("group_ui")
    if isinstance(raw_groups, dict):
        for gid, meta in raw_groups.items():
            key = str(gid).strip()
            if not key:
                continue
            parsed = GroupUiMeta.from_dict(meta)
            if parsed.pinned or parsed.last_activity_ts or parsed.notifications_muted:
                group_ui[key] = parsed

    return ContactBook(contacts=records, last_active_peer=lap, group_ui=group_ui)


def _group_ui_worth_keeping(meta: GroupUiMeta) -> bool:
    return bool(meta.pinned or meta.last_activity_ts or meta.notifications_muted)


def book_to_json_dict(book: ContactBook) -> dict[str, Any]:
    out_contacts = [r.to_dict() for r in book.contacts[:MAX_CONTACTS]]
    lap = book.last_active_peer
    if lap is not None:
        lap = normalize_peer_address(lap)
    out: dict[str, Any] = {
        "version": BOOK_VERSION,
        "last_active_peer": lap,
        "contacts": out_contacts,
    }
    group_ui = {
        gid: meta.to_dict()
        for gid, meta in book.group_ui.items()
        if _group_ui_worth_keeping(meta)
    }
    if group_ui:
        out["group_ui"] = group_ui
    return out


def load_book(path: str, *, identity_key: bytes | None = None) -> ContactBook:
    try:
        data = read_sealed_json(
            path,
            identity_key=identity_key,
            magic=CONTACTS_STORE_MAGIC,
            domain=CONTACTS_STORE_DOMAIN,
        )
    except (OSError, ValueError, json.JSONDecodeError, UnicodeDecodeError):
        return ContactBook()
    return parse_book_from_json(data)


def save_book(
    path: str,
    book: ContactBook,
    *,
    identity_key: bytes | None = None,
) -> None:
    book = trim_book(book)
    try:
        write_sealed_json(
            path,
            book_to_json_dict(book),
            identity_key=identity_key,
            magic=CONTACTS_STORE_MAGIC,
            domain=CONTACTS_STORE_DOMAIN,
        )
    except Exception:
        logger.debug("failed to save contact book", exc_info=True)


def trim_book(book: ContactBook) -> ContactBook:
    if len(book.contacts) <= MAX_CONTACTS:
        return book
    return ContactBook(
        contacts=book.contacts[:MAX_CONTACTS],
        last_active_peer=book.last_active_peer,
    )


def remember_peer(book: ContactBook, addr: str) -> bool:
    """Move peer to front (MRU). Returns True if book changed."""
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    i = book.peer_index(addr)
    if i == 0:
        return False
    if i > 0:
        rec = book.contacts.pop(i)
        book.contacts.insert(0, rec)
        return True
    book.contacts.insert(0, ContactRecord(addr=addr))
    if len(book.contacts) > MAX_CONTACTS:
        book.contacts[:] = book.contacts[:MAX_CONTACTS]
    return True


def set_last_active_peer(book: ContactBook, addr: Optional[str]) -> bool:
    """Set last active peer (normalized). None clears. Returns True if changed."""
    if addr is None or addr == "":
        if book.last_active_peer is None:
            return False
        book.last_active_peer = None
        return True
    n = normalize_peer_address(addr)
    if not n:
        return False
    if book.last_active_peer == n:
        return False
    book.last_active_peer = n
    return True


def set_peer_profile(
    book: ContactBook,
    addr: str,
    *,
    display_name: str,
    note: str,
) -> bool:
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    display_name = display_name.strip()
    note = note.strip()
    rec = book.get(addr)
    if rec is None:
        remember_peer(book, addr)
        rec = book.get(addr)
    if rec is None:
        return False
    if rec.display_name == display_name and rec.note == note:
        return False
    rec.display_name = display_name
    rec.note = note
    return True


def set_peer_pin(book: ContactBook, addr: str, *, pinned: bool, pin_order: int = 0) -> bool:
    """Pin or unpin a direct chat. Returns True if the book changed."""
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    pin_order = max(0, int(pin_order))
    rec = book.get(addr)
    if rec is None:
        if not pinned:
            return False
        remember_peer(book, addr)
        rec = book.get(addr)
    if rec is None:
        return False
    if rec.pinned == pinned and (not pinned or rec.pin_order == pin_order):
        return False
    rec.pinned = bool(pinned)
    rec.pin_order = pin_order if pinned else 0
    return True


def set_group_pin(
    book: ContactBook, group_id: str, *, pinned: bool, pin_order: int = 0
) -> bool:
    gid = (group_id or "").strip()
    if not gid:
        return False
    pin_order = max(0, int(pin_order))
    meta = book.group_ui.get(gid) or GroupUiMeta()
    if meta.pinned == pinned and (not pinned or meta.pin_order == pin_order):
        return False
    meta.pinned = bool(pinned)
    meta.pin_order = pin_order if pinned else 0
    if _group_ui_worth_keeping(meta):
        book.group_ui[gid] = meta
    else:
        book.group_ui.pop(gid, None)
    return True


def set_peer_notifications_muted(book: ContactBook, addr: str, *, muted: bool) -> bool:
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    rec = book.get(addr)
    if rec is None:
        if not muted:
            return False
        remember_peer(book, addr)
        rec = book.get(addr)
    if rec is None:
        return False
    if rec.notifications_muted == bool(muted):
        return False
    rec.notifications_muted = bool(muted)
    return True


def set_group_notifications_muted(
    book: ContactBook, group_id: str, *, muted: bool
) -> bool:
    gid = (group_id or "").strip()
    if not gid:
        return False
    meta = book.group_ui.get(gid) or GroupUiMeta()
    if meta.notifications_muted == bool(muted):
        return False
    meta.notifications_muted = bool(muted)
    if _group_ui_worth_keeping(meta):
        book.group_ui[gid] = meta
    else:
        book.group_ui.pop(gid, None)
    return True


def touch_group_activity(book: ContactBook, group_id: str, ts_iso: str) -> bool:
    gid = (group_id or "").strip()
    ts_iso = (ts_iso or "").strip()
    if not gid or not ts_iso:
        return False
    meta = book.group_ui.get(gid) or GroupUiMeta()
    if meta.last_activity_ts == ts_iso:
        return False
    meta.last_activity_ts = ts_iso
    book.group_ui[gid] = meta
    return True


def set_peer_history_retention_days(book: ContactBook, addr: str, days: int) -> bool:
    """Set per-peer auto-delete age in days (0 = use global). Returns True if changed."""
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    days = coerce_peer_retention_days(days)
    rec = book.get(addr)
    if rec is None:
        if days == 0:
            return False
        remember_peer(book, addr)
        rec = book.get(addr)
    if rec is None or rec.history_retention_days == days:
        return False
    rec.history_retention_days = days
    return True


def get_peer_history_retention_days(book: ContactBook, addr: str) -> int:
    """Per-peer override in days; 0 when unset or peer unknown."""
    n = normalize_peer_address(addr) or ""
    rec = book.get(n) if n else None
    return rec.history_retention_days if rec else 0


def touch_peer_message_meta(
    book: ContactBook,
    addr: str,
    preview: str,
    ts_iso: str,
) -> bool:
    """Update last_preview / last_activity_ts for peer. Returns True if changed."""
    addr = normalize_peer_address(addr) or ""
    if not addr:
        return False
    preview = (preview or "").replace("\n", " ").strip()
    if len(preview) > PREVIEW_MAX_LEN:
        preview = preview[: PREVIEW_MAX_LEN - 1] + "…"
    ts_iso = (ts_iso or "").strip()
    rec = book.get(addr)
    if rec is None:
        remember_peer(book, addr)
        rec = book.get(addr)
    if rec is None:
        return False
    if rec.last_preview == preview and rec.last_activity_ts == ts_iso:
        return False
    rec.last_preview = preview
    rec.last_activity_ts = ts_iso
    return True


def remove_peer(book: ContactBook, addr: str) -> bool:
    """Удалить контакт из книги. Сбрасывает last_active_peer, если он совпал. Возвращает True, если запись была."""
    n = normalize_peer_address(addr) or ""
    if not n:
        return False
    i = book.peer_index(n)
    if i < 0:
        return False
    book.contacts.pop(i)
    if book.last_active_peer == n:
        book.last_active_peer = None
    return True


def has_peer(book: ContactBook, addr: str) -> bool:
    a = normalize_peer_address(addr) or ""
    return bool(a) and book.peer_index(a) >= 0


def ordered_peer_addrs(book: ContactBook) -> list[str]:
    return [r.addr for r in book.contacts]
