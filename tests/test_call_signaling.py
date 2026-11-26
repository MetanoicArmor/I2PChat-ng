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
    HEADER_SIZE,
    MAX_PAYLOAD,
    TYPE_OPUS,
    TYPE_VIDEO_JPEG,
    FrameReassembler,
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


def test_fragment_payload_chunks():
    parts = fragment_payload(b"abcdefghij", max_chunk=4)
    assert parts == [b"abcd", b"efgh", b"ij"]
    assert fragment_payload(b"") == [b""]


def test_max_payload_fits_sam_buffer():
    # i2pd's SAM socket buffer is 8192 bytes: header line + payload must fit.
    assert MAX_PAYLOAD + HEADER_SIZE + 700 < 8192


def _packets(payload: bytes, seq: int = 7, chunk: int = 4):
    chunks = fragment_payload(payload, max_chunk=chunk)
    return [
        MediaPacket(
            call_hash=b"\x02" * 16,
            seq=seq,
            ts_ms=1,
            ptype=TYPE_VIDEO_JPEG,
            flags=0,
            payload=c,
            frag_idx=i,
            frag_total=len(chunks),
        )
        for i, c in enumerate(chunks)
    ]


def test_fragment_roundtrip_through_wire_and_reassembler():
    data = bytes(range(256)) * 40
    pkts = [decode_media_packet(p.encode()) for p in _packets(data, chunk=MAX_PAYLOAD)]
    assert all(p is not None for p in pkts)
    assert len(pkts) == 3
    r = FrameReassembler()
    out = None
    for p in reversed(pkts):  # reordered delivery
        out = r.feed(p) or out
    assert out == data


def test_reassembler_drops_incomplete_frame_after_timeout():
    now = [0.0]
    r = FrameReassembler(timeout_s=1.5, clock=lambda: now[0])
    pkts = _packets(b"abcdefghij")
    assert r.feed(pkts[0]) is None
    now[0] = 5.0
    # a later frame triggers expiry of the stale partial one
    full = _packets(b"zz", seq=8)
    assert r.feed(full[0]) == b"zz"
    assert len(r) == 0
    assert r.dropped_frames == 1
    assert r.feed(pkts[1]) is None  # late fragment of an expired frame never completes


def test_reassembler_limits_pending_frames():
    r = FrameReassembler(max_pending=2)
    for seq in range(5):
        r.feed(_packets(b"abcdefgh", seq=seq)[0])
    assert len(r) == 2
    assert r.dropped_frames == 3


def test_decode_rejects_old_magic_and_bad_fragment_index():
    assert decode_media_packet(b"I2C1" + b"\x00" * 40) is None
    bad = MediaPacket(call_hash=b"\x01" * 16, seq=1, ts_ms=1, ptype=1, flags=0, payload=b"x").encode()
    raw = bytearray(bad)
    raw[HEADER_SIZE - 2] = 5  # frag_idx
    raw[HEADER_SIZE - 1] = 2  # frag_total
    assert decode_media_packet(bytes(raw)) is None


def test_jpeg_type_constant():
    assert TYPE_VIDEO_JPEG == 3
