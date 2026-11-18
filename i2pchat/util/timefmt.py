"""Local display clocks for UI; storage/wire stay ISO-8601 UTC."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional, Union


def local_hms(value: Union[datetime, str, None], *, fallback: str = "") -> str:
    """Format a UTC (or aware) instant as ``HH:MM:SS`` in the system timezone.

    Naive datetimes and ISO strings without an offset are treated as UTC, matching
    history storage. Parse failures return ``fallback`` (empty by default).
    """
    dt = _as_aware_utc(value)
    if dt is None:
        return fallback
    return dt.astimezone().strftime("%H:%M:%S")


def local_hms_now() -> str:
    """Current local wall-clock ``HH:MM:SS`` for live UI labels."""
    return datetime.now().astimezone().strftime("%H:%M:%S")


def _as_aware_utc(value: Union[datetime, str, None]) -> Optional[datetime]:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    text = str(value).strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
