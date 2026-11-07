"""
Encrypted at-rest storage for the working profile identity ``.dat`` file.

Format (binary):
  4 bytes  — magic ``I2PK``
  2 bytes  — format version (big-endian uint16, currently 1)
  32 bytes — random salt
  rest     — NaCl SecretBox ciphertext wrapping UTF-8 private-key line
             (optional trailing ``\\n`` + legacy peer line is accepted on decrypt
             for migration, but new writes store only the key line)

Wrapping key:
  - Preferred: OS keyring entry ``i2pchat`` / ``{profile}__dat_wrap__``
    (base64 of 32 random bytes).
  - Fallback when keyring is unavailable: ``{profile}.dat.wrap`` next to the
    ``.dat`` (mode 0600). Same local threat model as former plaintext ``.dat``,
    but a leaked ``.dat`` alone (without the wrap sidecar / keyring) is useless.
    The sidecar is only created when the keyring cannot store the key; an
    existing one is kept so a copied profile directory stays openable.

Legacy plaintext UTF-8 ``.dat`` files are still readable; callers should
re-write them encrypted after a successful load (transparent migration).
"""

from __future__ import annotations

import base64
import logging
import os
import secrets
import struct
from typing import Callable, Optional

from i2pchat import crypto
from i2pchat.storage.blindbox_state import atomic_write_bytes, atomic_write_text

logger = logging.getLogger("i2pchat.storage.profile_dat")

PROFILE_DAT_MAGIC = b"I2PK"
PROFILE_DAT_VERSION = 1
PROFILE_DAT_VERSION_PASSWORD = 2
PROFILE_DAT_SALT_SIZE = 32
PROFILE_DAT_PASSWORD_SALT_SIZE = 16
PROFILE_DAT_HEADER_SIZE = 4 + 2 + PROFILE_DAT_SALT_SIZE


class ProfilePasswordError(ValueError):
    """The profile is sealed and the password is missing or wrong."""

DAT_WRAP_KEYRING_SUFFIX = "__dat_wrap__"
KEYRING_SERVICE = "i2pchat"


def profile_dat_version(raw: bytes) -> int:
    if len(raw) < 6 or raw[:4] != PROFILE_DAT_MAGIC:
        return 0
    return struct.unpack(">H", raw[4:6])[0]


def is_encrypted_profile_dat(raw: bytes) -> bool:
    version = profile_dat_version(raw)
    if version == PROFILE_DAT_VERSION:
        return len(raw) >= PROFILE_DAT_HEADER_SIZE
    if version == PROFILE_DAT_VERSION_PASSWORD:
        return len(raw) >= 4 + 2 + PROFILE_DAT_PASSWORD_SALT_SIZE
    return False


def profile_dat_needs_password(path: str) -> bool:
    try:
        with open(path, "rb") as handle:
            raw = handle.read(6)
    except OSError:
        return False
    return profile_dat_version(raw) == PROFILE_DAT_VERSION_PASSWORD


def profile_dat_wrap_path(profile_data_dir: str, profile: str) -> str:
    return os.path.join(profile_data_dir, f"{profile}.dat.wrap")


def _try_keyring_get_wrap(profile: str) -> Optional[bytes]:
    try:
        import keyring

        raw = keyring.get_password(KEYRING_SERVICE, f"{profile}{DAT_WRAP_KEYRING_SUFFIX}")
    except ImportError:
        return None
    except Exception as e:
        logger.debug("keyring wrap get failed (%s): %s", profile, e)
        return None
    if not raw:
        return None
    try:
        key = base64.b64decode(raw.encode("ascii"), validate=True)
    except Exception:
        return None
    if len(key) != 32:
        return None
    return key


def _try_keyring_set_wrap(profile: str, wrap_key: bytes) -> bool:
    if len(wrap_key) != 32:
        raise ValueError("wrap_key must be 32 bytes")
    try:
        import keyring

        keyring.set_password(
            KEYRING_SERVICE,
            f"{profile}{DAT_WRAP_KEYRING_SUFFIX}",
            base64.b64encode(wrap_key).decode("ascii"),
        )
        return True
    except ImportError:
        return False
    except Exception as e:
        logger.debug("keyring wrap set failed (%s): %s", profile, e)
        return False


def _read_wrap_sidecar(path: str) -> Optional[bytes]:
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            token = f.read().strip()
        key = base64.b64decode(token.encode("ascii"), validate=True)
    except Exception:
        return None
    if len(key) != 32:
        return None
    return key


def _write_wrap_sidecar(path: str, wrap_key: bytes) -> None:
    atomic_write_text(path, base64.b64encode(wrap_key).decode("ascii") + "\n")


def _get_or_create_dat_wrap_key(profile: str, profile_data_dir: str) -> tuple[bytes, bool]:
    """Return ``(wrap_key, in_keyring)``; the sidecar is written only as a fallback.

    The sidecar sits next to the ``.dat`` it protects, so while it exists the
    encryption adds nothing against an attacker who copies the profile
    directory. It is therefore created only when the OS keyring cannot hold
    the key. An existing sidecar is left in place (and promoted into the
    keyring) so profiles copied between machines keep opening.
    """
    existing = _try_keyring_get_wrap(profile)
    if existing is not None:
        return existing, True
    sidecar = profile_dat_wrap_path(profile_data_dir, profile)
    existing = _read_wrap_sidecar(sidecar)
    if existing is not None:
        # Best-effort promote sidecar → keyring for stronger protection.
        in_keyring = _try_keyring_set_wrap(profile, existing)
        return existing, in_keyring
    wrap_key = secrets.token_bytes(32)
    if _try_keyring_set_wrap(profile, wrap_key):
        logger.info("Profile .dat wrap key stored in OS keyring")
        return wrap_key, True
    _write_wrap_sidecar(sidecar, wrap_key)
    logger.info("Profile .dat wrap key stored in local sidecar (OS keyring unavailable)")
    return wrap_key, False


def get_or_create_dat_wrap_key(profile: str, profile_data_dir: str) -> bytes:
    """Return a stable 32-byte wrap key for this profile (keyring or sidecar)."""
    return _get_or_create_dat_wrap_key(profile, profile_data_dir)[0]


def load_dat_wrap_key(profile: str, profile_data_dir: str) -> Optional[bytes]:
    """Load an existing wrap key without creating one."""
    existing = _try_keyring_get_wrap(profile)
    if existing is not None:
        return existing
    return _read_wrap_sidecar(profile_dat_wrap_path(profile_data_dir, profile))


def _derive_file_key(wrap_key: bytes, salt: bytes) -> bytes:
    prk = crypto.hkdf_extract(b"I2PCHAT-PROFILE-DAT", wrap_key)
    profile_key = crypto.hkdf_expand(prk, b"I2PCHAT-PROFILE-DAT|profile-key", 32)
    prk2 = crypto.hkdf_extract(salt, profile_key)
    return crypto.hkdf_expand(prk2, b"I2PCHAT-PROFILE-DAT|file-key", 32)


def encrypt_profile_dat(private_key_base64: str, wrap_key: bytes) -> bytes:
    if not crypto.NACL_AVAILABLE:
        raise RuntimeError("PyNaCl is required to encrypt profile .dat")
    key = (private_key_base64 or "").strip()
    if not key:
        raise ValueError("private_key_base64 is empty")
    salt = secrets.token_bytes(PROFILE_DAT_SALT_SIZE)
    file_key = _derive_file_key(wrap_key, salt)
    plaintext = (key + "\n").encode("utf-8")
    ciphertext = crypto.encrypt_message(file_key, plaintext)
    return PROFILE_DAT_MAGIC + struct.pack(">H", PROFILE_DAT_VERSION) + salt + ciphertext


def encrypt_profile_dat_with_password(private_key_base64: str, password: str) -> bytes:
    """I2PK v2: the file key is Argon2id(password), with the salt in the file."""
    if not crypto.NACL_AVAILABLE:
        raise RuntimeError("PyNaCl is required to encrypt profile .dat")
    key = (private_key_base64 or "").strip()
    if not key or not password:
        raise ValueError("A profile password and a private key are both required")
    from i2pchat.storage.profile_export import _derive_key

    salt = secrets.token_bytes(PROFILE_DAT_PASSWORD_SALT_SIZE)
    file_key = _derive_key(password, salt)
    ciphertext = crypto.encrypt_message(file_key, (key + "\n").encode("utf-8"))
    return (
        PROFILE_DAT_MAGIC
        + struct.pack(">H", PROFILE_DAT_VERSION_PASSWORD)
        + salt
        + ciphertext
    )


def decrypt_profile_dat_with_password(raw: bytes, password: str) -> str:
    if profile_dat_version(raw) != PROFILE_DAT_VERSION_PASSWORD:
        raise ValueError("Not a password-protected profile .dat")
    if not password:
        raise ProfilePasswordError("A password is required to open this profile.")
    if not crypto.NACL_AVAILABLE:
        raise RuntimeError("PyNaCl is required to decrypt profile .dat")
    from i2pchat.storage.profile_export import _derive_key

    salt = raw[6 : 6 + PROFILE_DAT_PASSWORD_SALT_SIZE]
    ciphertext = raw[6 + PROFILE_DAT_PASSWORD_SALT_SIZE :]
    file_key = _derive_key(password, salt)
    plaintext = crypto.decrypt_message(file_key, ciphertext)
    if plaintext is None:
        raise ProfilePasswordError("Wrong profile password.")
    lines = [ln.strip() for ln in plaintext.decode("utf-8").splitlines() if ln.strip()]
    if not lines:
        raise ValueError("Decrypted profile .dat is empty")
    return lines[0]


def decrypt_profile_dat(raw: bytes, wrap_key: bytes) -> str:
    if not is_encrypted_profile_dat(raw):
        raise ValueError("Not an encrypted profile .dat")
    if profile_dat_version(raw) == PROFILE_DAT_VERSION_PASSWORD:
        raise ProfilePasswordError("A password is required to open this profile.")
    if not crypto.NACL_AVAILABLE:
        raise RuntimeError("PyNaCl is required to decrypt profile .dat")
    version = struct.unpack(">H", raw[4:6])[0]
    if version != PROFILE_DAT_VERSION:
        raise ValueError(f"Unsupported encrypted profile .dat version {version}")
    salt = raw[6:PROFILE_DAT_HEADER_SIZE]
    ciphertext = raw[PROFILE_DAT_HEADER_SIZE:]
    file_key = _derive_file_key(wrap_key, salt)
    plaintext = crypto.decrypt_message(file_key, ciphertext)
    if plaintext is None:
        raise ValueError("Profile .dat decryption failed (wrong wrap key or tampered)")
    text = plaintext.decode("utf-8")
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    if not lines:
        raise ValueError("Decrypted profile .dat is empty")
    return lines[0]


def parse_plaintext_profile_dat(
    text: str,
    *,
    is_probable_peer: Optional[Callable[[str], bool]] = None,
) -> tuple[Optional[str], Optional[str]]:
    """Parse legacy UTF-8 ``.dat`` into (private_key_b64 | None, legacy_peer | None)."""
    lines = [ln.strip() for ln in (text or "").splitlines() if ln.strip()]
    if not lines:
        return None, None
    peer_check = is_probable_peer or (lambda _s: False)
    private_key: Optional[str] = None
    legacy_peer: Optional[str] = None
    if not peer_check(lines[0]):
        private_key = lines[0]
        if len(lines) > 1 and peer_check(lines[1]):
            legacy_peer = lines[1]
    elif peer_check(lines[0]):
        # keyring-only scenario: file held only the locked peer
        legacy_peer = lines[0]
    return private_key, legacy_peer


def read_profile_dat_file(
    path: str,
    *,
    profile: str,
    profile_data_dir: str,
    is_probable_peer: Optional[Callable[[str], bool]] = None,
    create_wrap_key: bool = True,
    password: str = "",
) -> tuple[Optional[str], Optional[str], bool]:
    """
    Read a profile ``.dat``.

    Returns ``(private_key_b64 | None, legacy_peer | None, was_plaintext)``.
    ``was_plaintext`` is True when the on-disk file was legacy UTF-8 (caller
    should re-encrypt).
    """
    if not os.path.isfile(path):
        return None, None, False
    with open(path, "rb") as f:
        raw = f.read()
    if not raw:
        return None, None, False
    if profile_dat_version(raw) == PROFILE_DAT_VERSION_PASSWORD:
        key = decrypt_profile_dat_with_password(raw, password)
        return key, None, False
    if is_encrypted_profile_dat(raw):
        wrap = (
            get_or_create_dat_wrap_key(profile, profile_data_dir)
            if create_wrap_key
            else load_dat_wrap_key(profile, profile_data_dir)
        )
        if wrap is None:
            raise ValueError(
                f"Encrypted profile .dat at {path} but no wrap key is available "
                "(OS keyring entry missing and no .dat.wrap sidecar)"
            )
        key = decrypt_profile_dat(raw, wrap)
        return key, None, False
    # Legacy plaintext UTF-8
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise ValueError(f"Profile .dat is neither encrypted nor valid UTF-8: {e}") from e
    private_key, legacy_peer = parse_plaintext_profile_dat(
        text, is_probable_peer=is_probable_peer
    )
    return private_key, legacy_peer, True


def write_encrypted_profile_dat(
    path: str,
    private_key_base64: str,
    *,
    profile: str,
    profile_data_dir: str,
    password: str = "",
) -> None:
    """Encrypt and atomically write the identity key; creates wrap key if needed."""
    if password:
        atomic_write_bytes(path, encrypt_profile_dat_with_password(private_key_base64, password))
        return
    if not crypto.NACL_AVAILABLE:
        # Last-resort fallback: keep previous plaintext behaviour so the app
        # remains usable without PyNaCl (should be rare in real installs).
        logger.warning(
            "PyNaCl unavailable — writing profile .dat without at-rest encryption"
        )
        atomic_write_text(path, (private_key_base64 or "").strip() + "\n")
        return
    wrap, in_keyring = _get_or_create_dat_wrap_key(profile, profile_data_dir)
    # The 0600 sidecar is a fallback for hosts without a usable OS keyring. When
    # the keyring holds the key, no sidecar is created, so a copied profile
    # directory (or a leaked .dat) stays opaque. An already-present sidecar is
    # kept in step with the key in use rather than deleted.
    sidecar = profile_dat_wrap_path(profile_data_dir, profile)
    existing_sidecar = _read_wrap_sidecar(sidecar)
    if existing_sidecar is None:
        if not in_keyring:
            _write_wrap_sidecar(sidecar, wrap)
    elif existing_sidecar != wrap:
        _write_wrap_sidecar(sidecar, wrap)
    blob = encrypt_profile_dat(private_key_base64, wrap)
    atomic_write_bytes(path, blob)


def plaintext_key_bytes_for_backup(
    path: str,
    *,
    profile: str,
    profile_data_dir: str,
) -> bytes:
    """
    Return UTF-8 plaintext key bytes suitable for inclusion in a passphrase-
    protected backup bundle (portable across machines without the wrap key).
    """
    key, _peer, _was_plain = read_profile_dat_file(
        path,
        profile=profile,
        profile_data_dir=profile_data_dir,
        create_wrap_key=False,
    )
    if not key:
        raise ValueError(f"No private key found in {path}")
    return (key.strip() + "\n").encode("utf-8")
