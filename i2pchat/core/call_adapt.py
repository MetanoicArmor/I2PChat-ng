"""Runtime adaptive quality for call media (pure logic, no Qt)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from i2pchat.core.media_framing import MAX_PAYLOAD

# Cap JPEG / keyframe size so reassembly stays realistic on lossy DATAGRAM.
MAX_FRAME_FRAGMENTS = 4
MAX_FRAME_BYTES = MAX_PAYLOAD * MAX_FRAME_FRAGMENTS


@dataclass
class EffectiveMedia:
    """Runtime encode knobs (may sit below the saved preset ceiling)."""

    audio_bitrate_kbps: int
    video_bitrate_kbps: int
    video_fps: int
    video_max_edge: int
    jpeg_quality: int = 40
    level: int = 0  # 0=ceiling … higher = more reduced

    def target_frame_bytes(self) -> int:
        fps = max(1, int(self.video_fps))
        raw = int(self.video_bitrate_kbps) * 1000 // 8 // fps
        return max(800, min(MAX_FRAME_BYTES, raw))


def target_frame_bytes(*, video_bitrate_kbps: int, video_fps: int) -> int:
    return EffectiveMedia(
        audio_bitrate_kbps=32,
        video_bitrate_kbps=video_bitrate_kbps,
        video_fps=video_fps,
        video_max_edge=320,
    ).target_frame_bytes()


@dataclass
class AdaptSnapshot:
    tx_packets: int = 0
    tx_dropped: int = 0
    rx_packets: int = 0
    rx_dropped: int = 0


class AdaptiveController:
    """Move effective quality within a ceiling based on TX/RX drop deltas."""

    def __init__(self, ceiling: EffectiveMedia) -> None:
        self.ceiling = EffectiveMedia(
            audio_bitrate_kbps=ceiling.audio_bitrate_kbps,
            video_bitrate_kbps=ceiling.video_bitrate_kbps,
            video_fps=ceiling.video_fps,
            video_max_edge=ceiling.video_max_edge,
            jpeg_quality=min(70, max(25, ceiling.jpeg_quality)),
            level=0,
        )
        self.effective = EffectiveMedia(
            audio_bitrate_kbps=self.ceiling.audio_bitrate_kbps,
            video_bitrate_kbps=self.ceiling.video_bitrate_kbps,
            video_fps=self.ceiling.video_fps,
            video_max_edge=self.ceiling.video_max_edge,
            jpeg_quality=self.ceiling.jpeg_quality,
            level=0,
        )
        self._prev: Optional[AdaptSnapshot] = None
        self._good_streak = 0
        self._bad_streak = 0

    def reset_ceiling(self, ceiling: EffectiveMedia) -> None:
        self.ceiling = EffectiveMedia(
            audio_bitrate_kbps=ceiling.audio_bitrate_kbps,
            video_bitrate_kbps=ceiling.video_bitrate_kbps,
            video_fps=ceiling.video_fps,
            video_max_edge=ceiling.video_max_edge,
            jpeg_quality=min(70, max(25, ceiling.jpeg_quality)),
            level=0,
        )
        # Keep current level relative to new ceiling.
        self._apply_level(self.effective.level)

    def observe(self, stats: dict[str, Any] | AdaptSnapshot) -> EffectiveMedia:
        if isinstance(stats, AdaptSnapshot):
            snap = stats
        else:
            snap = AdaptSnapshot(
                tx_packets=int(stats.get("tx_packets") or 0),
                tx_dropped=int(stats.get("tx_dropped") or 0),
                rx_packets=int(stats.get("rx_packets") or 0),
                rx_dropped=int(stats.get("rx_dropped") or 0),
            )
        if self._prev is None:
            self._prev = snap
            return self.effective

        d_tx_drop = max(0, snap.tx_dropped - self._prev.tx_dropped)
        d_rx_drop = max(0, snap.rx_dropped - self._prev.rx_dropped)
        d_tx = max(0, snap.tx_packets - self._prev.tx_packets)
        d_rx = max(0, snap.rx_packets - self._prev.rx_packets)
        self._prev = snap

        drop_ratio = 0.0
        if d_tx + d_rx > 0:
            drop_ratio = (d_tx_drop + d_rx_drop) / float(d_tx + d_rx)

        # Silence after the path has already carried media is trouble.
        # Silence before the first packet is just the datagram LeaseSet not
        # being published yet — crushing quality then sends a tiny picture
        # the moment the path finally opens.
        starving = snap.rx_packets > 0 and d_tx >= 8 and d_rx == 0
        bad = starving or d_tx_drop >= 2 or d_rx_drop >= 2 or drop_ratio >= 0.08
        good = (not bad) and d_tx_drop == 0 and d_rx_drop == 0 and (d_rx > 0 or d_tx < 4)

        if bad:
            self._bad_streak += 1
            self._good_streak = 0
            if self._bad_streak >= 1:
                self._apply_level(min(6, self.effective.level + 1))
                self._bad_streak = 0
        elif good:
            self._good_streak += 1
            self._bad_streak = 0
            if self._good_streak >= 3:
                self._apply_level(max(0, self.effective.level - 1))
                self._good_streak = 0
        else:
            self._good_streak = 0
            self._bad_streak = 0
        return self.effective

    def _apply_level(self, level: int) -> None:
        level = max(0, min(6, int(level)))
        c = self.ceiling
        # Each level trims bitrate/fps/edge/quality toward a floor.
        factor = max(0.35, 1.0 - 0.11 * level)
        edge_steps = (720, 640, 480, 400, 320, 240, 160)
        fps_floor = 3
        self.effective = EffectiveMedia(
            audio_bitrate_kbps=c.audio_bitrate_kbps,
            video_bitrate_kbps=max(50, int(c.video_bitrate_kbps * factor)),
            video_fps=max(fps_floor, int(c.video_fps * factor + 0.5)),
            video_max_edge=edge_steps[min(level, len(edge_steps) - 1)]
            if c.video_max_edge >= edge_steps[min(level, len(edge_steps) - 1)]
            else max(160, int(c.video_max_edge * factor)),
            jpeg_quality=max(25, min(70, int(c.jpeg_quality - 5 * level))),
            level=level,
        )
        # Never exceed ceiling edge.
        self.effective.video_max_edge = min(c.video_max_edge, self.effective.video_max_edge)
        self.effective.video_fps = min(c.video_fps, self.effective.video_fps)
        self.effective.video_bitrate_kbps = min(
            c.video_bitrate_kbps, self.effective.video_bitrate_kbps
        )
