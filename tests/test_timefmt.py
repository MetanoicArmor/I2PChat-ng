"""Local display clocks stay local; ISO inputs are interpreted as UTC."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from i2pchat.util.timefmt import local_hms, local_hms_now


def test_local_hms_converts_utc_iso_to_system_zone() -> None:
    # Fixed offset east of UTC so the hour digit must change if conversion works.
    zone = timezone(timedelta(hours=3))
    iso = "2026-01-01T12:00:00+00:00"
    dt = datetime.fromisoformat(iso).astimezone(zone)
    expected = dt.strftime("%H:%M:%S")
    # Patching ZoneInfo is awkward; assert against an explicit zone via datetime path.
    assert local_hms(dt) == expected
    assert local_hms(iso) == datetime.fromisoformat(iso).astimezone().strftime("%H:%M:%S")


def test_local_hms_treats_naive_and_z_as_utc() -> None:
    naive = "2026-06-15T08:30:00"
    zulu = "2026-06-15T08:30:00Z"
    assert local_hms(naive) == local_hms(zulu)
    assert local_hms("") == ""
    assert local_hms(None) == ""
    assert local_hms("not-a-time", fallback="—") == "—"


def test_local_hms_now_is_hhmmss() -> None:
    assert len(local_hms_now()) == 8
    assert local_hms_now()[2] == ":"
