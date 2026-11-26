"""Unit tests for adaptive call quality and codec negotiation."""

from __future__ import annotations

from i2pchat.core.call_adapt import AdaptiveController, EffectiveMedia, target_frame_bytes
from i2pchat.core.call_prefs import PRESET_ECONOMY, PRESET_MAX, PRESET_NORMAL, CallPrefs
from i2pchat.core.call_signals import codecs_include, intersect_codecs, normalize_codec_list
from i2pchat.core.call_signals import call_id_hash16
from i2pchat.core.media_framing import (
    TYPE_VIDEO_DELTA,
    TYPE_VIDEO_KEY,
    FrameReassembler,
    MediaPacket,
    fragment_payload,
)


def test_presets_apply():
    p = CallPrefs().apply_preset(PRESET_ECONOMY)
    assert p.video_max_edge == 240
    assert p.video_fps == 5
    p.apply_preset(PRESET_MAX)
    assert p.video_bitrate_kbps == 600
    assert p.preset == PRESET_MAX
    p.apply_preset(PRESET_NORMAL)
    assert p.video_max_edge == 480


def test_target_frame_bytes_scales():
    small = target_frame_bytes(video_bitrate_kbps=100, video_fps=10)
    big = target_frame_bytes(video_bitrate_kbps=600, video_fps=10)
    assert big > small
    assert small >= 800


def test_adapt_steps_down_on_drops():
    ceil = EffectiveMedia(32, 250, 8, 480, jpeg_quality=40)
    ctrl = AdaptiveController(ceil)
    ctrl.observe({"tx_packets": 0, "tx_dropped": 0, "rx_packets": 0, "rx_dropped": 0})
    # Simulate bad window: many TX with drops, no RX.
    eff = ctrl.observe(
        {"tx_packets": 20, "tx_dropped": 5, "rx_packets": 0, "rx_dropped": 3}
    )
    assert eff.level >= 1
    assert eff.video_bitrate_kbps <= 250


def test_adapt_steps_up_on_good_path():
    ceil = EffectiveMedia(32, 250, 8, 480, jpeg_quality=40)
    ctrl = AdaptiveController(ceil)
    ctrl._apply_level(3)
    assert ctrl.effective.level == 3
    ctrl.observe({"tx_packets": 0, "tx_dropped": 0, "rx_packets": 0, "rx_dropped": 0})
    for i in range(1, 10):
        ctrl.observe(
            {
                "tx_packets": i * 10,
                "tx_dropped": 0,
                "rx_packets": i * 10,
                "rx_dropped": 0,
            }
        )
    assert ctrl.effective.level < 3


def test_intersect_codecs_prefers_local_order():
    got = intersect_codecs("opus,vp8,jpeg", "jpeg,opus")
    assert got == "opus,jpeg"
    assert codecs_include(got, "opus")
    assert not codecs_include(got, "vp8")
    assert normalize_codec_list("Opus, VP8, jpeg") == ["opus", "vp8", "jpeg"]


def test_video_key_delta_framing_roundtrip():
    h = call_id_hash16("1-test")
    payload = b"vp8-frame-bytes" * 200
    chunks = fragment_payload(payload)
    assert len(chunks) >= 1
    reasm = FrameReassembler()
    out = None
    for idx, chunk in enumerate(chunks):
        pkt = MediaPacket(
            call_hash=h,
            seq=7,
            ts_ms=1,
            ptype=TYPE_VIDEO_KEY,
            flags=0,
            payload=chunk,
            frag_idx=idx,
            frag_total=len(chunks),
        )
        out = reasm.feed(pkt)
    assert out == payload

    delta = MediaPacket(
        call_hash=h,
        seq=8,
        ts_ms=2,
        ptype=TYPE_VIDEO_DELTA,
        flags=0,
        payload=b"delta",
        frag_idx=0,
        frag_total=1,
    )
    assert reasm.feed(delta) == b"delta"
    assert TYPE_VIDEO_KEY == 2
    assert TYPE_VIDEO_DELTA == 4
