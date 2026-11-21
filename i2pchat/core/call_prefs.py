"""Persistent call media preferences (bitrate / FPS / resolution)."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Optional

from i2pchat.router.settings import router_settings_path


@dataclass
class CallPrefs:
    audio_bitrate_kbps: int = 32
    video_bitrate_kbps: int = 250
    video_fps: int = 8
    video_max_edge: int = 480
    start_mic: bool = True
    start_spk: bool = True

    def clamp(self) -> "CallPrefs":
        self.audio_bitrate_kbps = max(12, min(64, int(self.audio_bitrate_kbps)))
        self.video_bitrate_kbps = max(50, min(800, int(self.video_bitrate_kbps)))
        self.video_fps = max(3, min(20, int(self.video_fps)))
        self.video_max_edge = max(160, min(720, int(self.video_max_edge)))
        return self

    @property
    def max_bitrate_bps(self) -> int:
        return (self.audio_bitrate_kbps + self.video_bitrate_kbps) * 1000


def call_prefs_path(profile: Optional[str] = None) -> Path:
    # Stored next to router_prefs.json; profile arg reserved for future per-profile files.
    _ = profile
    return Path(router_settings_path()).with_name("call_prefs.json")


def load_call_prefs(profile: Optional[str] = None) -> CallPrefs:
    path = call_prefs_path(profile)
    prefs = CallPrefs()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, TypeError):
        return prefs.clamp()
    if not isinstance(raw, dict):
        return prefs.clamp()
    for key in (
        "audio_bitrate_kbps",
        "video_bitrate_kbps",
        "video_fps",
        "video_max_edge",
        "start_mic",
        "start_spk",
    ):
        if key in raw:
            setattr(prefs, key, raw[key])
    return prefs.clamp()


def save_call_prefs(prefs: CallPrefs, profile: Optional[str] = None) -> None:
    path = call_prefs_path(profile)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    data: dict[str, Any] = asdict(prefs.clamp())
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)
