#!/usr/bin/env python3
"""Self-test for SAM DATAGRAM media transport used by calls.

Opens two TRANSIENT DATAGRAM sessions on one SAM bridge (default: the bundled
router at 127.0.0.1:17656), sends packets A -> B for several payload sizes and
address forms (full base64 / ``<b32>.b32.i2p``) and reports what arrives.

Usage:
    uv run python scripts/call_datagram_selftest.py [--sam 127.0.0.1:17656]
        [--sizes 100,1200,3500,7000] [--wait 90] [--count 3]
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from i2pchat.core.media_framing import MediaPacket, TYPE_PCM16, decode_media_packet  # noqa: E402
from i2pchat.sam.datagram import create_datagram_session  # noqa: E402


def _parse_sam(value: str) -> tuple[str, int]:
    host, _, port = value.rpartition(":")
    return (host or "127.0.0.1", int(port))


async def _run(args: argparse.Namespace) -> int:
    sam = _parse_sam(args.sam)
    sizes = [int(x) for x in args.sizes.split(",") if x.strip()]
    got: dict[tuple[int, int], float] = {}
    t0 = time.monotonic()

    async def on_b(from_dest: str, payload: bytes) -> None:
        pkt = decode_media_packet(payload)
        if pkt is None:
            return
        got[(pkt.seq, len(payload))] = time.monotonic() - t0

    print(f"SAM {sam[0]}:{sam[1]}: creating two DATAGRAM sessions (tunnel build can take minutes)...")
    a = await create_datagram_session("selftest-a", sam_address=sam)
    b = await create_datagram_session("selftest-b", sam_address=sam, on_datagram=on_b)
    print(f"  A: {a.local_b32}")
    print(f"  B: {b.local_b32}")
    print(f"  ready after {time.monotonic() - t0:.1f}s")

    sent: list[tuple[str, int, int]] = []
    seq = 0
    for form, dest in (("b64", b.local_destination_b64), ("b32", b.local_b32)):
        for size in sizes:
            for _ in range(args.count):
                seq += 1
                body = bytes(size)
                pkt = MediaPacket(
                    call_hash=b"\x07" * 16,
                    seq=seq,
                    ts_ms=0,
                    ptype=TYPE_PCM16,
                    flags=0,
                    payload=body,
                )
                raw = pkt.encode()
                try:
                    await a.send(dest, raw)
                    sent.append((form, size, seq))
                except Exception as exc:
                    print(f"  send {form} size={size} failed: {exc!r}")
                await asyncio.sleep(0.05)

    print(f"sent {len(sent)} packets; waiting up to {args.wait}s for delivery...")
    deadline = time.monotonic() + args.wait
    while time.monotonic() < deadline and len(got) < len(sent):
        await asyncio.sleep(0.5)

    print("\nform  size   sent  recv  first-rx(s)")
    ok = True
    for form in ("b64", "b32"):
        for size in sizes:
            seqs = [s for f, sz, s in sent if f == form and sz == size]
            recv = [(k, v) for k, v in got.items() if k[0] in seqs]
            first = min((v for _, v in recv), default=None)
            print(
                f"{form}   {size:5d}  {len(seqs):4d}  {len(recv):4d}  "
                f"{'-' if first is None else f'{first:.1f}'}"
            )
            if seqs and not recv:
                ok = False
    print(f"\nA stats: {a.stats()}")
    print(f"B stats: {b.stats()}")
    await a.close()
    await b.close()
    return 0 if ok else 1


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sam", default="127.0.0.1:17656")
    ap.add_argument("--sizes", default="100,1200,3500,7000")
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--wait", type=float, default=90.0)
    args = ap.parse_args()
    raise SystemExit(asyncio.run(_run(args)))


if __name__ == "__main__":
    main()
