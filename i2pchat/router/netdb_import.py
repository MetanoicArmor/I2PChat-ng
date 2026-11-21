"""Validate and import a manual netDb ZIP when bundled i2pd reseed fails.

A ZIP is accepted when strictly more than half of its file entries are
RouterInfo blobs whose published timestamps are at most 270 hours old.
Identity layout follows the I2P common-structures padding (256+128 key
areas + certificate), then an 8-byte big-endian Date in milliseconds.
"""

from __future__ import annotations

import os
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, Optional

# Matches the user's reseed-import freshness window.
MAX_ROUTERINFO_AGE_HOURS = 270
MAX_ROUTERINFO_AGE_MS = MAX_ROUTERINFO_AGE_HOURS * 3600 * 1000

# Sanity bounds for published Date (ms since epoch).
_MIN_PUBLISHED_MS = 946_684_800_000  # 2000-01-01
_MAX_PUBLISHED_MS = 4_102_444_800_000  # 2100-01-01

# I2P identity key material before the certificate.
_IDENTITY_KEY_BYTES = 256 + 128


class NetDbImportError(ValueError):
    """ZIP failed validation or could not be applied to netDb."""


@dataclass(frozen=True)
class NetDbZipStats:
    total_files: int
    router_info_files: int
    fresh_router_info_files: int
    max_age_hours: int = MAX_ROUTERINFO_AGE_HOURS

    @property
    def accepted(self) -> bool:
        if self.total_files <= 0:
            return False
        return self.fresh_router_info_files * 2 > self.total_files


def router_info_published_ms(data: bytes) -> Optional[int]:
    """Return RouterInfo published time in ms, or None if data is not RI-shaped."""
    if len(data) < _IDENTITY_KEY_BYTES + 3 + 8:
        return None
    offset = _IDENTITY_KEY_BYTES
    cert_len = int.from_bytes(data[offset + 1 : offset + 3], "big")
    offset += 3 + cert_len
    if offset + 8 > len(data):
        return None
    published = int.from_bytes(data[offset : offset + 8], "big")
    if published < _MIN_PUBLISHED_MS or published > _MAX_PUBLISHED_MS:
        return None
    return published


def _is_fresh(published_ms: int, *, now_ms: Optional[int] = None) -> bool:
    now = int(time.time() * 1000) if now_ms is None else now_ms
    age = now - published_ms
    if age < -3600_000:
        # More than an hour in the future — reject as clock nonsense.
        return False
    return age <= MAX_ROUTERINFO_AGE_MS


def _iter_zip_files(zf: zipfile.ZipFile) -> Iterable[zipfile.ZipInfo]:
    for info in zf.infolist():
        if info.is_dir():
            continue
        name = info.filename.replace("\\", "/")
        if not name or name.endswith("/"):
            continue
        yield info


def _safe_ri_basename(zip_name: str, index: int) -> str:
    base = Path(zip_name.replace("\\", "/")).name
    if not base or base in {".", ".."}:
        base = f"routerInfo-import-{index}.dat"
    # i2pd expects routerInfo-* names; keep original when already shaped that way.
    if not base.lower().startswith("routerinfo"):
        base = f"routerInfo-{base}"
    # Strip path traversal leftovers.
    return base.replace("\x00", "_")


def inspect_netdb_zip(
    source: str | os.PathLike[str] | BinaryIO,
    *,
    now_ms: Optional[int] = None,
) -> NetDbZipStats:
    """Count files / RouterInfo / fresh RouterInfo in a ZIP without extracting."""
    total = 0
    ri = 0
    fresh = 0
    try:
        with zipfile.ZipFile(source, "r") as zf:
            for info in _iter_zip_files(zf):
                total += 1
                try:
                    data = zf.read(info)
                except Exception:
                    continue
                published = router_info_published_ms(data)
                if published is None:
                    continue
                ri += 1
                if _is_fresh(published, now_ms=now_ms):
                    fresh += 1
    except zipfile.BadZipFile as exc:
        raise NetDbImportError("Not a valid ZIP archive.") from exc
    except OSError as exc:
        raise NetDbImportError(f"Cannot read ZIP: {exc}") from exc
    return NetDbZipStats(
        total_files=total,
        router_info_files=ri,
        fresh_router_info_files=fresh,
    )


def validate_netdb_zip(
    source: str | os.PathLike[str] | BinaryIO,
    *,
    now_ms: Optional[int] = None,
) -> NetDbZipStats:
    """Raise NetDbImportError unless strictly more than half of entries are fresh RI."""
    stats = inspect_netdb_zip(source, now_ms=now_ms)
    if stats.total_files <= 0:
        raise NetDbImportError("The ZIP archive is empty.")
    if not stats.accepted:
        raise NetDbImportError(
            "The archive must contain more than half RouterInfo files "
            f"published within the last {MAX_ROUTERINFO_AGE_HOURS} hours "
            f"(found {stats.fresh_router_info_files} fresh of {stats.total_files} files)."
        )
    return stats


def extract_netdb_zip_to(
    zip_path: str | os.PathLike[str],
    netdb_dir: str | os.PathLike[str],
    *,
    now_ms: Optional[int] = None,
) -> NetDbZipStats:
    """Validate ``zip_path`` and write parseable RouterInfo files into ``netdb_dir``."""
    path = Path(zip_path)
    stats = validate_netdb_zip(path, now_ms=now_ms)
    dest = Path(netdb_dir)
    dest.mkdir(parents=True, exist_ok=True)
    written = 0
    with zipfile.ZipFile(path, "r") as zf:
        for index, info in enumerate(_iter_zip_files(zf)):
            try:
                data = zf.read(info)
            except Exception:
                continue
            if router_info_published_ms(data) is None:
                continue
            out_name = _safe_ri_basename(info.filename, index)
            out_path = dest / out_name
            # Avoid clobbering with path tricks; basename already sanitized.
            out_path.write_bytes(data)
            written += 1
    if written <= 0:
        raise NetDbImportError("No RouterInfo files could be written to netDb.")
    return stats


def netdb_dir_for_datadir(data_dir: str | os.PathLike[str]) -> Path:
    return Path(data_dir) / "netDb"
