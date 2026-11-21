"""Tests for manual netDb ZIP validation and extraction."""

from __future__ import annotations

import io
import time
import zipfile
from pathlib import Path

import pytest

from i2pchat.router.diagnostics import ACTION_IMPORT_NETDB_ZIP, diagnose_log_text
from i2pchat.router.netdb_import import (
    MAX_ROUTERINFO_AGE_HOURS,
    NetDbImportError,
    extract_netdb_zip_to,
    inspect_netdb_zip,
    router_info_published_ms,
    validate_netdb_zip,
)


def _ri(published_ms: int, pad: int = 32) -> bytes:
    keys = b"\x00" * (256 + 128)
    cert = bytes([0, 0, 0])  # NULL certificate
    date = int(published_ms).to_bytes(8, "big")
    return keys + cert + date + (b"\x00" * pad)


def _zip_bytes(files: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    return buf.getvalue()


def test_router_info_published_ms_roundtrip():
    now = int(time.time() * 1000)
    assert router_info_published_ms(_ri(now)) == now
    assert router_info_published_ms(b"short") is None
    assert router_info_published_ms(_ri(0)) is None


def test_accepts_majority_fresh_routerinfo(tmp_path: Path):
    now = int(time.time() * 1000)
    hour = 3600_000
    files = {
        "netDb/routerInfo-a.dat": _ri(now - hour),
        "netDb/routerInfo-b.dat": _ri(now - 2 * hour),
        "readme.txt": b"not a router info",
    }
    raw = _zip_bytes(files)
    zpath = tmp_path / "ok.zip"
    zpath.write_bytes(raw)
    stats = validate_netdb_zip(zpath, now_ms=now)
    assert stats.accepted
    assert stats.total_files == 3
    assert stats.fresh_router_info_files == 2

    dest = tmp_path / "netDb"
    extract_netdb_zip_to(zpath, dest, now_ms=now)
    written = list(dest.iterdir())
    assert len(written) == 2
    assert all(p.name.lower().startswith("routerinfo") for p in written)


def test_rejects_when_fresh_not_majority(tmp_path: Path):
    now = int(time.time() * 1000)
    stale = now - (MAX_ROUTERINFO_AGE_HOURS + 1) * 3600_000
    files = {
        "a.dat": _ri(stale),
        "b.dat": _ri(stale),
        "c.dat": _ri(now),
    }
    zpath = tmp_path / "stale.zip"
    zpath.write_bytes(_zip_bytes(files))
    stats = inspect_netdb_zip(zpath, now_ms=now)
    assert stats.fresh_router_info_files == 1
    assert stats.total_files == 3
    assert not stats.accepted
    with pytest.raises(NetDbImportError):
        validate_netdb_zip(zpath, now_ms=now)


def test_reseed_failed_offers_import_action():
    log = (
        "Reseed: SU3 download failed\n"
        "Reseed: SU3 download failed\n"
        "Reseed: SU3 download failed\n"
        "Reseed: Failed to reseed from servers. Waiting for 45 seconds\n"
    )
    issue = diagnose_log_text(log)
    assert issue is not None
    assert issue.code == "reseed_failed"
    assert ACTION_IMPORT_NETDB_ZIP in issue.actions
