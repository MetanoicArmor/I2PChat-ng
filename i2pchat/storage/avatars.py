"""Per-profile avatar store + delivery ledger (identity-keyed at rest).

Layout under ``profiles/<name>/avatars/``:
  - ``ledger.json`` — sealed JSON (own hash, peer delivery map, re-request set)
  - ``self.bin`` — sealed own avatar bytes (raw image)
  - ``peer.<addr_hash>.bin`` — sealed peer avatar bytes

Peer avatars are never included in profile export; after import the ledger's
``need_request`` set is filled so the UI/core can pull fresh copies.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import secrets
import struct
from dataclasses import dataclass, field
from typing import Any, Optional

from i2pchat import crypto
from i2pchat.storage.blindbox_state import atomic_write_bytes
from i2pchat.storage.contact_book import normalize_peer_address
from i2pchat.storage.sealed_json import read_sealed_json, write_sealed_json

logger = logging.getLogger("i2pchat.avatars")

AVATAR_DIR_NAME = "avatars"
PREVIEW_DIR_NAME = "preview"
LEDGER_NAME = "ledger.json"
OWN_BLOB_NAME = "self.bin"
OWN_PREVIEW_NAME = "self.jpg"
PEER_BLOB_PREFIX = "peer."

AVATAR_LEDGER_MAGIC = b"I2AV"
AVATAR_LEDGER_DOMAIN = b"I2PCHAT-AVATARS"
AVATAR_BLOB_MAGIC = b"I2AB"
AVATAR_BLOB_DOMAIN = b"I2PCHAT-AVATAR-BLOB"

# Wire / on-disk filename peers use when pushing their avatar as a file.
AVATAR_WIRE_FILENAME = ".i2pchat-avatar.jpg"

MAX_AVATAR_BYTES = 256 * 1024
LEDGER_VERSION = 1


def avatars_dir(profile_data_dir: str) -> str:
    path = os.path.join(profile_data_dir, AVATAR_DIR_NAME)
    os.makedirs(path, mode=0o700, exist_ok=True)
    return path


def preview_dir(profile_data_dir: str) -> str:
    path = os.path.join(avatars_dir(profile_data_dir), PREVIEW_DIR_NAME)
    os.makedirs(path, mode=0o700, exist_ok=True)
    return path


def _peer_key(addr: str) -> Optional[str]:
    return normalize_peer_address(addr)


def _peer_blob_name(addr: str) -> Optional[str]:
    key = _peer_key(addr)
    if key is None:
        return None
    digest = hashlib.sha256(key.encode("ascii")).hexdigest()[:32]
    return f"{PEER_BLOB_PREFIX}{digest}.bin"


def _peer_preview_name(addr: str) -> Optional[str]:
    key = _peer_key(addr)
    if key is None:
        return None
    digest = hashlib.sha256(key.encode("ascii")).hexdigest()[:32]
    return f"{digest}.jpg"


def _write_preview(path: str, image_bytes: bytes) -> None:
    atomic_write_bytes(path, image_bytes)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def own_preview_path(profile_data_dir: str) -> str:
    return os.path.join(preview_dir(profile_data_dir), OWN_PREVIEW_NAME)


def peer_preview_path(profile_data_dir: str, peer_addr: str) -> Optional[str]:
    name = _peer_preview_name(peer_addr)
    if name is None:
        return None
    return os.path.join(preview_dir(profile_data_dir), name)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass
class AvatarLedger:
    own_sha256: str = ""
    # peer_addr (canonical) -> sha256 of own avatar last acknowledged/delivered
    delivered: dict[str, str] = field(default_factory=dict)
    # peers whose avatars should be re-requested after import
    need_request: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": LEDGER_VERSION,
            "own_sha256": self.own_sha256,
            "delivered": dict(self.delivered),
            "need_request": list(self.need_request),
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "AvatarLedger":
        delivered_raw = raw.get("delivered") if isinstance(raw, dict) else {}
        delivered: dict[str, str] = {}
        if isinstance(delivered_raw, dict):
            for key, value in delivered_raw.items():
                nk = _peer_key(str(key))
                if nk and isinstance(value, str) and value:
                    delivered[nk] = value
        need: list[str] = []
        for item in raw.get("need_request") or []:
            nk = _peer_key(str(item))
            if nk and nk not in need:
                need.append(nk)
        own = raw.get("own_sha256") if isinstance(raw, dict) else ""
        return cls(
            own_sha256=own if isinstance(own, str) else "",
            delivered=delivered,
            need_request=need,
        )


def ledger_path(profile_data_dir: str) -> str:
    return os.path.join(avatars_dir(profile_data_dir), LEDGER_NAME)


def load_ledger(profile_data_dir: str, identity_key: bytes | None) -> AvatarLedger:
    path = ledger_path(profile_data_dir)
    if not os.path.isfile(path):
        return AvatarLedger()
    try:
        data = read_sealed_json(
            path,
            identity_key=identity_key,
            magic=AVATAR_LEDGER_MAGIC,
            domain=AVATAR_LEDGER_DOMAIN,
        )
    except (OSError, ValueError, json.JSONDecodeError):
        logger.warning("avatar ledger unreadable; starting empty")
        return AvatarLedger()
    if not isinstance(data, dict):
        return AvatarLedger()
    return AvatarLedger.from_dict(data)


def save_ledger(
    profile_data_dir: str,
    ledger: AvatarLedger,
    identity_key: bytes | None,
) -> None:
    write_sealed_json(
        ledger_path(profile_data_dir),
        ledger.to_dict(),
        identity_key=identity_key,
        magic=AVATAR_LEDGER_MAGIC,
        domain=AVATAR_LEDGER_DOMAIN,
    )


def _seal_blob(path: str, data: bytes, identity_key: bytes | None) -> None:
    if not identity_key or not crypto.NACL_AVAILABLE:
        atomic_write_bytes(path, data)
        return
    salt = secrets.token_bytes(32)
    prk = crypto.hkdf_extract(AVATAR_BLOB_DOMAIN, identity_key)
    profile_key = crypto.hkdf_expand(prk, AVATAR_BLOB_DOMAIN + b"|profile-key", 32)
    prk2 = crypto.hkdf_extract(salt, profile_key)
    file_key = crypto.hkdf_expand(prk2, AVATAR_BLOB_DOMAIN + b"|file-key", 32)
    ciphertext = crypto.encrypt_message(file_key, data)
    header = AVATAR_BLOB_MAGIC + struct.pack(">H", 1) + salt
    atomic_write_bytes(path, header + ciphertext)


def _unseal_blob(path: str, identity_key: bytes | None) -> bytes | None:
    try:
        with open(path, "rb") as handle:
            raw = handle.read()
    except OSError:
        return None
    if not raw:
        return None
    if not identity_key or not crypto.NACL_AVAILABLE or not raw.startswith(AVATAR_BLOB_MAGIC):
        # Legacy/plaintext fallback
        if raw.startswith(AVATAR_BLOB_MAGIC):
            return None
        return raw
    if len(raw) < 4 + 2 + 32:
        return None
    salt = raw[6:38]
    ciphertext = raw[38:]
    prk = crypto.hkdf_extract(AVATAR_BLOB_DOMAIN, identity_key)
    profile_key = crypto.hkdf_expand(prk, AVATAR_BLOB_DOMAIN + b"|profile-key", 32)
    prk2 = crypto.hkdf_extract(salt, profile_key)
    file_key = crypto.hkdf_expand(prk2, AVATAR_BLOB_DOMAIN + b"|file-key", 32)
    plain = crypto.decrypt_message(file_key, ciphertext)
    if plain is None:
        logger.warning("failed to decrypt avatar blob %s", path)
    return plain


def set_own_avatar(
    profile_data_dir: str,
    identity_key: bytes | None,
    image_bytes: bytes,
) -> str:
    """Store own avatar; returns sha256 hex. Invalidates prior delivery marks."""
    if not image_bytes:
        raise ValueError("empty avatar")
    if len(image_bytes) > MAX_AVATAR_BYTES:
        raise ValueError(f"avatar larger than {MAX_AVATAR_BYTES} bytes")
    digest = sha256_hex(image_bytes)
    path = os.path.join(avatars_dir(profile_data_dir), OWN_BLOB_NAME)
    _seal_blob(path, image_bytes, identity_key)
    _write_preview(own_preview_path(profile_data_dir), image_bytes)
    ledger = load_ledger(profile_data_dir, identity_key)
    ledger.own_sha256 = digest
    ledger.delivered.clear()
    save_ledger(profile_data_dir, ledger, identity_key)
    return digest


def get_own_avatar(profile_data_dir: str, identity_key: bytes | None) -> bytes | None:
    path = os.path.join(avatars_dir(profile_data_dir), OWN_BLOB_NAME)
    data = _unseal_blob(path, identity_key)
    if data:
        return data
    preview = own_preview_path(profile_data_dir)
    if os.path.isfile(preview):
        try:
            with open(preview, "rb") as handle:
                return handle.read()
        except OSError:
            return None
    return None


def set_peer_avatar(
    profile_data_dir: str,
    identity_key: bytes | None,
    peer_addr: str,
    image_bytes: bytes,
) -> str:
    if not image_bytes:
        raise ValueError("empty avatar")
    if len(image_bytes) > MAX_AVATAR_BYTES:
        raise ValueError(f"avatar larger than {MAX_AVATAR_BYTES} bytes")
    name = _peer_blob_name(peer_addr)
    key = _peer_key(peer_addr)
    if name is None or key is None:
        raise ValueError("invalid peer address")
    digest = sha256_hex(image_bytes)
    path = os.path.join(avatars_dir(profile_data_dir), name)
    _seal_blob(path, image_bytes, identity_key)
    preview = peer_preview_path(profile_data_dir, peer_addr)
    if preview:
        _write_preview(preview, image_bytes)
    ledger = load_ledger(profile_data_dir, identity_key)
    if key in ledger.need_request:
        ledger.need_request = [p for p in ledger.need_request if p != key]
        save_ledger(profile_data_dir, ledger, identity_key)
    return digest


def get_peer_avatar(
    profile_data_dir: str,
    identity_key: bytes | None,
    peer_addr: str,
) -> bytes | None:
    name = _peer_blob_name(peer_addr)
    if name is None:
        return None
    path = os.path.join(avatars_dir(profile_data_dir), name)
    data = _unseal_blob(path, identity_key)
    if data:
        return data
    preview = peer_preview_path(profile_data_dir, peer_addr)
    if preview and os.path.isfile(preview):
        try:
            with open(preview, "rb") as handle:
                return handle.read()
        except OSError:
            return None
    return None


def clear_need_request(
    profile_data_dir: str,
    identity_key: bytes | None,
    peer_addr: str,
) -> None:
    key = _peer_key(peer_addr)
    if key is None:
        return
    ledger = load_ledger(profile_data_dir, identity_key)
    if key not in ledger.need_request:
        return
    ledger.need_request = [p for p in ledger.need_request if p != key]
    save_ledger(profile_data_dir, ledger, identity_key)


def mark_delivered(
    profile_data_dir: str,
    identity_key: bytes | None,
    peer_addr: str,
    own_sha256: str,
) -> None:
    key = _peer_key(peer_addr)
    if key is None or not own_sha256:
        return
    ledger = load_ledger(profile_data_dir, identity_key)
    ledger.delivered[key] = own_sha256
    save_ledger(profile_data_dir, ledger, identity_key)


def peers_needing_own_avatar(
    profile_data_dir: str,
    identity_key: bytes | None,
    trusted_peers: list[str],
) -> list[str]:
    """Trusted peers that have not yet received the current own avatar."""
    ledger = load_ledger(profile_data_dir, identity_key)
    if not ledger.own_sha256:
        return []
    out: list[str] = []
    for peer in trusted_peers:
        key = _peer_key(peer)
        if key is None:
            continue
        if ledger.delivered.get(key) != ledger.own_sha256:
            out.append(key)
    return out


def peers_needing_avatar_request(
    profile_data_dir: str,
    identity_key: bytes | None,
) -> list[str]:
    return list(load_ledger(profile_data_dir, identity_key).need_request)


def mark_need_request_all_contacts(
    profile_data_dir: str,
    identity_key: bytes | None,
    peer_addrs: list[str],
) -> None:
    """After profile import: drop peer blobs and queue re-request."""
    root = avatars_dir(profile_data_dir)
    for name in os.listdir(root):
        if name.startswith(PEER_BLOB_PREFIX) and name.endswith(".bin"):
            try:
                os.unlink(os.path.join(root, name))
            except OSError:
                pass
    prev = preview_dir(profile_data_dir)
    for name in os.listdir(prev):
        if name == OWN_PREVIEW_NAME:
            continue
        try:
            os.unlink(os.path.join(prev, name))
        except OSError:
            pass
    ledger = load_ledger(profile_data_dir, identity_key)
    need: list[str] = []
    for peer in peer_addrs:
        key = _peer_key(peer)
        if key and key not in need:
            need.append(key)
    ledger.need_request = need
    save_ledger(profile_data_dir, ledger, identity_key)


def export_own_avatar_b64(
    profile_data_dir: str,
    identity_key: bytes | None,
) -> Optional[str]:
    import base64

    data = get_own_avatar(profile_data_dir, identity_key)
    if not data:
        return None
    return base64.b64encode(data).decode("ascii")


def import_own_avatar_b64(
    profile_data_dir: str,
    identity_key: bytes | None,
    b64: str,
) -> str:
    import base64

    raw = base64.b64decode(b64.encode("ascii"), validate=True)
    return set_own_avatar(profile_data_dir, identity_key, raw)


def is_avatar_wire_filename(name: str) -> bool:
    base = os.path.basename(name or "").strip().lower()
    if base == AVATAR_WIRE_FILENAME:
        return True
    # Collision renames: ".i2pchat-avatar (1).jpg"
    return base.startswith(".i2pchat-avatar.") or base.startswith(
        ".i2pchat-avatar ("
    )
