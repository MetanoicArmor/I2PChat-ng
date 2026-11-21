"""Unit tests for call signaling and media framing."""

from __future__ import annotations

from i2pchat.core.call_signals import (
    CALL_ACCEPT,
    CALL_HANGUP,
    CALL_INVITE,
    CALL_MEDIA,
    CALL_REJECT,
    CallAccept,
    CallHangup,
    CallInvite,
    CallMediaState,
    CallReject,
    call_id_hash16,
    parse_call_signal,
)
from i2pchat.core.media_framing import (
    FLAG_FRAG_MORE,
    TYPE_OPUS,
    TYPE_VIDEO_JPEG,
    MediaPacket,
    decode_media_packet,
    fragment_payload,
)


def test_invite_accept_roundtrip():
    invite = CallInvite(
        call_id="1-abc",
        want_audio=True,
        want_video=True,
        codecs="opus,jpeg",
        max_bitrate=300_000,
        datagram_b32="xyz.b32.i2p",
    )
    parsed = parse_call_signal(invite.encode())
    assert parsed is not None
    assert parsed.kind == CALL_INVITE
    assert parsed.want_video is True
    assert parsed.datagram_b32 == "xyz.b32.i2p"

    accept = CallAccept("1-abc", "opus,jpeg", 250_000, "peerdest")
    parsed_a = parse_call_signal(accept.encode())
    assert parsed_a is not None
    assert parsed_a.kind == CALL_ACCEPT
    assert parsed_a.max_bitrate == 250_000


def test_reject_hangup_media():
    assert parse_call_signal(CallReject("cid", "busy").encode()).reason == "busy"
    assert parse_call_signal(CallHangup("cid").encode()).kind == CALL_HANGUP
    media = parse_call_signal(
        CallMediaState("cid", mic=False, cam=True, screen=False, spk=True).encode()
    )
    assert media is not None
    assert media.kind == CALL_MEDIA
    assert media.mic is False
    assert media.cam is True


def test_call_id_hash_length():
    assert len(call_id_hash16("hello")) == 16


def test_media_packet_roundtrip():
    pkt = MediaPacket(
        call_hash=b"\x01" * 16,
        seq=42,
        ts_ms=1_700_000_000,
        ptype=TYPE_OPUS,
        flags=0,
        payload=b"opus-bytes",
    )
    decoded = decode_media_packet(pkt.encode())
    assert decoded is not None
    assert decoded.seq == 42
    assert decoded.ptype == TYPE_OPUS
    assert decoded.payload == b"opus-bytes"


def test_fragment_payload_flags():
    parts = fragment_payload(b"abcdefghij", max_chunk=4)
    assert len(parts) == 3
    assert parts[0][1] & FLAG_FRAG_MORE
    assert parts[1][1] & FLAG_FRAG_MORE
    assert parts[2][1] == 0
    assert b"".join(c for c, _ in parts) == b"abcdefghij"


def test_jpeg_type_constant():
    assert TYPE_VIDEO_JPEG == 3
