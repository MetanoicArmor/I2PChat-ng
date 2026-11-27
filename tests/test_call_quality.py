"""Call quality features: XOR parity recovery, Opus loss concealment, tunnel options."""

from __future__ import annotations

import asyncio
import os
import unittest

from i2pchat.core.call_media import SAMPLES_PER_FRAME, AudioCodec
from i2pchat.core.call_session import CallSessionManager
from i2pchat.core.media_framing import (
    MAX_PAYLOAD,
    TYPE_PARITY,
    TYPE_VIDEO_KEY,
    FrameReassembler,
    MediaPacket,
    build_parity_payload,
    completed_ptype,
    decode_media_packet,
    fragment_payload,
)

CH = b"\x07" * 16


def _frame_packets(data: bytes, *, seq: int = 1):
    chunks = fragment_payload(data)
    pkts = [
        MediaPacket(
            call_hash=CH,
            seq=seq,
            ts_ms=1,
            ptype=TYPE_VIDEO_KEY,
            flags=0,
            payload=c,
            frag_idx=i,
            frag_total=len(chunks),
        )
        for i, c in enumerate(chunks)
    ]
    parity = MediaPacket(
        call_hash=CH,
        seq=seq,
        ts_ms=1,
        ptype=TYPE_PARITY,
        flags=0,
        payload=build_parity_payload(TYPE_VIDEO_KEY, chunks),
    )
    return pkts, parity


def _wire(p: MediaPacket) -> MediaPacket:
    out = decode_media_packet(p.encode())
    assert out is not None
    return out


def test_recovered_frame_reports_video_type_not_parity():
    data = bytes(range(256)) * 20
    pkts, parity = _frame_packets(data)
    wired = _wire(parity)
    assert wired.ptype == TYPE_PARITY
    assert completed_ptype(wired) == TYPE_VIDEO_KEY
    assert completed_ptype(_wire(pkts[0])) == TYPE_VIDEO_KEY
    r = FrameReassembler()
    assert r.feed(_wire(pkts[0])) is None
    assert r.feed(wired) == data


def test_parity_recovers_any_single_lost_fragment():
    data = bytes((i * 31) % 251 for i in range(MAX_PAYLOAD * 2 + 777))
    pkts, parity = _frame_packets(data)
    assert len(pkts) == 3
    for lost in range(len(pkts)):
        r = FrameReassembler()
        out = None
        for i, p in enumerate(pkts):
            if i != lost:
                out = r.feed(_wire(p)) or out
        assert out is None
        out = r.feed(_wire(parity))
        assert out == data, f"lost fragment {lost}"
        assert r.recovered_frames == 1
        assert r.dropped_frames == 0


def test_parity_arriving_first_still_recovers():
    data = bytes(range(256)) * 20  # 2 fragments
    pkts, parity = _frame_packets(data)
    assert len(pkts) == 2
    r = FrameReassembler()
    assert r.feed(_wire(parity)) is None
    assert r.dropped_frames == 0
    assert r.feed(_wire(pkts[1])) == data
    assert r.recovered_frames == 1


def test_parity_ignored_when_frame_complete_and_late_fragment_not_counted_dropped():
    data = bytes(range(256)) * 30
    pkts, parity = _frame_packets(data)
    r = FrameReassembler()
    out = None
    for p in pkts:
        out = r.feed(_wire(p)) or out
    assert out == data
    assert r.feed(_wire(parity)) is None  # nothing left to recover
    assert r.feed(_wire(pkts[0])) is None  # duplicate of a finished frame
    assert len(r) == 0
    assert r.dropped_frames == 0


def test_two_lost_fragments_cannot_be_recovered_and_expire():
    now = [0.0]
    r = FrameReassembler(timeout_s=1.5, clock=lambda: now[0])
    data = bytes(range(256)) * 60  # 3+ fragments
    pkts, parity = _frame_packets(data)
    assert len(pkts) >= 3
    assert r.feed(_wire(pkts[0])) is None
    assert r.feed(_wire(parity)) is None
    now[0] = 5.0
    r.feed(MediaPacket(CH, 9, 1, TYPE_VIDEO_KEY, 0, b"x"))  # triggers expiry
    assert r.dropped_frames == 1
    assert r.recovered_frames == 0


def test_parity_packet_is_single_datagram_within_limits():
    chunks = fragment_payload(b"a" * (MAX_PAYLOAD + 10))
    pkt = MediaPacket(
        call_hash=CH,
        seq=1,
        ts_ms=0,
        ptype=TYPE_PARITY,
        flags=0,
        payload=build_parity_payload(TYPE_VIDEO_KEY, chunks),
    )
    assert len(pkt.encode()) <= 32 + MAX_PAYLOAD + 6


class _FakeDec:
    """Stands in for opuslib.Decoder: tags each output so the test sees what ran."""

    def __init__(self) -> None:
        self.calls: list[tuple[bytes, bool]] = []

    def decode(self, data: bytes, frame_size: int, decode_fec: bool = False) -> bytes:
        self.calls.append((bytes(data), decode_fec))
        if not data:
            tag = b"P"  # PLC
        elif decode_fec:
            tag = b"F"  # recovered from in-band FEC
        else:
            tag = b"N"  # normal
        return tag * (SAMPLES_PER_FRAME * 2)


def _codec() -> AudioCodec:
    c = AudioCodec()
    c._dec = _FakeDec()
    c.opus = True
    return c


def _kinds(frames: list[bytes]) -> bytes:
    return b"".join(f[:1] for f in frames)


def test_audio_in_order_stream_has_no_concealment():
    c = _codec()
    out = []
    for seq in (10, 11, 12):
        out += c.decode_stream(b"x", opus=True, seq=seq, seq_tracked=True)
    assert _kinds(out) == b"NNN"
    assert c.concealed == 0 and c.fec_recovered == 0


def test_audio_single_loss_uses_fec():
    c = _codec()
    c.decode_stream(b"x", opus=True, seq=10, seq_tracked=True)
    out = c.decode_stream(b"y", opus=True, seq=12, seq_tracked=True)
    assert _kinds(out) == b"FN"
    assert c.fec_recovered == 1


def test_audio_burst_loss_uses_plc_then_fec():
    c = _codec()
    c.decode_stream(b"x", opus=True, seq=10, seq_tracked=True)
    out = c.decode_stream(b"y", opus=True, seq=14, seq_tracked=True)
    assert _kinds(out) == b"PPFN"
    assert c.concealed == 2 and c.fec_recovered == 1


def test_audio_huge_gap_is_not_concealed_and_late_packets_dropped():
    c = _codec()
    c.decode_stream(b"x", opus=True, seq=10, seq_tracked=True)
    out = c.decode_stream(b"y", opus=True, seq=500, seq_tracked=True)
    assert _kinds(out) == b"N"
    assert c.decode_stream(b"z", opus=True, seq=499, seq_tracked=True) == []
    assert c.decode_stream(b"z", opus=True, seq=500, seq_tracked=True) == []
    assert c.late_dropped == 2


def test_audio_seq_wraparound():
    c = _codec()
    c.decode_stream(b"x", opus=True, seq=0xFFFFFFFF, seq_tracked=True)
    out = c.decode_stream(b"y", opus=True, seq=0, seq_tracked=True)
    assert _kinds(out) == b"N"


def test_untracked_audio_is_decoded_plainly():
    c = _codec()
    out = c.decode_stream(b"x", opus=True, seq=5, seq_tracked=False)
    out += c.decode_stream(b"y", opus=True, seq=50, seq_tracked=False)
    assert _kinds(out) == b"NN"


def test_pcm_passthrough():
    c = AudioCodec()
    c.opus = False
    assert c.decode_stream(b"\x01\x02", opus=False, seq=1, seq_tracked=True) == [b"\x01\x02"]


def _mgr(**kw) -> CallSessionManager:
    async def _send(_a, _b):  # pragma: no cover
        return True

    return CallSessionManager(sam_address=("127.0.0.1", 7656), send_signal=_send, **kw)


def test_call_tunnel_options_follow_router_hops():
    async def run():
        m = _mgr(tunnel_length=4)
        o = m._tunnel_options()
        assert o["inbound.length"] == o["outbound.length"] == "4"
        assert o["inbound.quantity"] == o["outbound.quantity"] == "3"
        assert o["inbound.backupQuantity"] == "1"
        o2 = _mgr(tunnel_length=99)._tunnel_options()
        assert o2["inbound.length"] == "7"
        assert "inbound.length" not in _mgr()._tunnel_options()

    asyncio.run(run())


def test_send_media_adds_parity_for_fragmented_video_and_audio_seq_flag():
    from i2pchat.core.call_session import CallPhase
    from i2pchat.core.media_framing import FLAG_AUDIO_SEQ, TYPE_OPUS

    class _Dg:
        control_closed = False

        def __init__(self) -> None:
            self.sent: list[MediaPacket] = []

        async def send(self, target: str, data: bytes) -> None:
            self.sent.append(_wire_any(data))

    def _wire_any(data: bytes) -> MediaPacket:
        p = decode_media_packet(data)
        assert p is not None
        return p

    async def run():
        m = _mgr()
        dg = _Dg()
        m._dg = dg  # type: ignore[assignment]
        m._rx_packets = 1  # path already up; this checks framing, not the warmup probe
        m.state.phase = CallPhase.ACTIVE
        m.state.call_id = "c" * 16
        m._remote_send_target = lambda: "dest"  # type: ignore[method-assign]
        await m._send_media(TYPE_VIDEO_KEY, b"v" * (MAX_PAYLOAD + 5), ts_ms=1)
        assert [p.ptype for p in dg.sent] == [TYPE_VIDEO_KEY, TYPE_VIDEO_KEY, TYPE_PARITY]
        dg.sent.clear()
        await m._send_media(TYPE_VIDEO_KEY, b"v" * 100, ts_ms=1)
        assert [p.ptype for p in dg.sent] == [TYPE_VIDEO_KEY]  # single fragment: no parity
        dg.sent.clear()
        await m._send_media(TYPE_OPUS, b"a" * 40, ts_ms=1)
        await m._send_media(TYPE_OPUS, b"a" * 40, ts_ms=2)
        assert [p.seq for p in dg.sent] == [1, 2]
        assert all(p.flags & FLAG_AUDIO_SEQ for p in dg.sent)

    asyncio.run(run())


def test_audio_encode_respects_peer_without_opus():
    c = AudioCodec()

    class _Enc:
        def encode(self, pcm, n):
            return b"OPUS"

    c._enc = _Enc()
    c.opus = True
    pcm = b"\x00" * (SAMPLES_PER_FRAME * 2)
    assert c.encode(pcm) == (b"OPUS", True)
    assert c.encode(pcm, allow_opus=False) == (pcm, False)


def test_uses_opus_follows_negotiated_codecs(monkeypatch=None):
    import i2pchat.core.call_session as cs

    async def run():
        m = _mgr()
        orig = cs.opus_available
        cs.opus_available = lambda: True
        try:
            m.state.codecs = "opus,jpeg"
            assert m.uses_opus() is True
            m.state.codecs = "jpeg"
            assert m.uses_opus() is False
            assert m.snapshot()["uses_opus"] is False
        finally:
            cs.opus_available = orig

    asyncio.run(run())


class CallReceiveLimitTests(unittest.TestCase):
    def test_oversized_fragment_is_rejected(self) -> None:
        from i2pchat.core.media_framing import HEADER_SIZE, MAX_PACKET_PAYLOAD, decode_media_packet

        raw = b"I2C2" + b"\x00" * 16 + (1).to_bytes(4, "big") + b"\x00" * 4
        raw += bytes([3, 0, 0, 1]) + b"A" * (MAX_PACKET_PAYLOAD + 1)
        self.assertGreater(len(raw), HEADER_SIZE)
        self.assertIsNone(decode_media_packet(raw))

    def test_reassembler_drops_a_frame_past_the_byte_budget(self) -> None:
        from i2pchat.core.media_framing import MAX_PAYLOAD, MAX_RX_FRAME_BYTES, FrameReassembler, MediaPacket

        r = FrameReassembler()
        chunk = b"a" * MAX_PAYLOAD
        total = 20
        stored = 0
        while stored + MAX_PAYLOAD <= MAX_RX_FRAME_BYTES:
            self.assertIsNone(
                r.feed(
                    MediaPacket(
                        call_hash=b"\x07" * 16,
                        seq=3,
                        ts_ms=1,
                        ptype=3,
                        flags=0,
                        payload=chunk,
                        frag_idx=stored // MAX_PAYLOAD,
                        frag_total=total,
                    )
                )
            )
            stored += MAX_PAYLOAD
        self.assertIsNone(
            r.feed(
                MediaPacket(
                    call_hash=b"\x07" * 16,
                    seq=3,
                    ts_ms=1,
                    ptype=3,
                    flags=0,
                    payload=chunk,
                    frag_idx=stored // MAX_PAYLOAD,
                    frag_total=total,
                )
            )
        )
        self.assertGreaterEqual(r.dropped_frames, 1)
        self.assertEqual(len(r), 0)

    def test_vp8_keyframe_over_the_edge_is_rejected(self) -> None:
        from i2pchat.core.video_codec import vp8_keyframe_rejected

        def header(width: int, height: int, *, key: bool) -> bytes:
            payload = bytearray(10)
            payload[0] = 0 if key else 1
            payload[3:6] = b"\x9d\x01\x2a"
            payload[6] = width & 0xFF
            payload[7] = (width >> 8) & 0x3F
            payload[8] = height & 0xFF
            payload[9] = (height >> 8) & 0x3F
            return bytes(payload)

        self.assertTrue(vp8_keyframe_rejected(header(2000, 200, key=True)))
        self.assertFalse(vp8_keyframe_rejected(header(640, 480, key=True)))
        self.assertFalse(vp8_keyframe_rejected(header(2000, 200, key=False)))

    def test_oversized_jpeg_is_not_decoded(self) -> None:
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        try:
            from PyQt6 import QtCore, QtGui, QtWidgets
        except ImportError as exc:
            self.skipTest(f"PyQt6 unavailable for JPEG decode test: {exc}")

        from i2pchat.core.call_media import qimage_from_jpeg
        from i2pchat.core.media_framing import MAX_RX_FRAME_BYTES

        self.assertIsNone(qimage_from_jpeg(b""))
        self.assertIsNone(qimage_from_jpeg(b"x" * (MAX_RX_FRAME_BYTES + 1)))

        QtWidgets.QApplication.instance() or QtWidgets.QApplication([])

        def jpeg(width: int, height: int) -> bytes:
            img = QtGui.QImage(width, height, QtGui.QImage.Format.Format_RGB888)
            img.fill(0)
            buf = QtCore.QBuffer()
            buf.open(QtCore.QIODevice.OpenModeFlag.WriteOnly)
            img.save(buf, "JPG", 40)
            return bytes(buf.data())

        self.assertIsNone(qimage_from_jpeg(jpeg(2000, 400)))
        small = qimage_from_jpeg(jpeg(320, 240))
        self.assertIsNotNone(small)
        assert small is not None
        self.assertEqual(small.width(), 320)
