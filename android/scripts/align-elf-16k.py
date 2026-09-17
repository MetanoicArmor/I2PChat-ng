#!/usr/bin/env python3
"""Pad ELF LOAD segments so offset ≡ vaddr (mod 16384)."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

PAGE = 16384
PT_LOAD = 1


def align_file(path: Path) -> bool:
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit(f"{path}: not ELF64")

    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_shoff = struct.unpack_from("<Q", data, 40)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    e_shentsize = struct.unpack_from("<H", data, 58)[0]
    e_shnum = struct.unpack_from("<H", data, 60)[0]

    def phdr(i: int) -> tuple[int, int, int, int]:
        base = e_phoff + i * e_phentsize
        p_type, _, p_offset, p_vaddr = struct.unpack_from("<IIQQ", data, base)
        return p_type, p_offset, p_vaddr, base

    changed = False
    for i in range(e_phnum):
        p_type, p_offset, p_vaddr, base = phdr(i)
        if p_type != PT_LOAD or p_offset == 0:
            if p_type == PT_LOAD:
                struct.pack_into("<Q", data, base + 48, PAGE)
            continue
        pad = (p_vaddr % PAGE - p_offset % PAGE) % PAGE
        struct.pack_into("<Q", data, base + 48, PAGE)
        if pad == 0:
            continue
        changed = True
        data[p_offset:p_offset] = b"\x00" * pad
        if e_shoff >= p_offset:
            e_shoff += pad
            struct.pack_into("<Q", data, 40, e_shoff)
        for j in range(e_phnum):
            jbase = e_phoff + j * e_phentsize
            joff = struct.unpack_from("<Q", data, jbase + 8)[0]
            if joff >= p_offset:
                struct.pack_into("<Q", data, jbase + 8, joff + pad)
        if e_shnum and e_shentsize:
            for s in range(e_shnum):
                sbase = e_shoff + s * e_shentsize
                soff = struct.unpack_from("<Q", data, sbase + 24)[0]
                if soff >= p_offset:
                    struct.pack_into("<Q", data, sbase + 24, soff + pad)

    if changed:
        path.write_bytes(data)
    return changed


def verify(path: Path) -> None:
    data = path.read_bytes()
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    bad = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, _, p_offset, p_vaddr = struct.unpack_from("<IIQQ", data, base)
        p_align = struct.unpack_from("<Q", data, base + 48)[0]
        if p_type != PT_LOAD:
            continue
        if p_align < PAGE or (p_offset % PAGE) != (p_vaddr % PAGE):
            bad.append((hex(p_offset), hex(p_vaddr), hex(p_align)))
    if bad:
        raise SystemExit(f"{path}: still unaligned: {bad}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.files:
        changed = align_file(path)
        verify(path)
        print(("aligned" if changed else "already-ok"), path)


if __name__ == "__main__":
    main()
