"""Проверка validate_image для PNG/JPEG/WebP."""

from __future__ import annotations

import pytest

from i2pchat.core.i2p_chat_core import (
    detect_inline_image_format,
    strip_image_metadata,
    validate_image,
)


def test_detect_inline_image_format() -> None:
    assert detect_inline_image_format(b"\x89PNG\r\n\x1a\n") == "png"
    assert detect_inline_image_format(b"\xff\xd8\xff\xe0") == "jpeg"
    assert detect_inline_image_format(b"RIFF" + b"\x00\x00\x00\x00" + b"WEBP") == "webp"
    assert detect_inline_image_format(b"") is None
    assert detect_inline_image_format(b"GIF87a") is None


def test_validate_png_jpeg(tmp_path) -> None:
    from PIL import Image

    p = tmp_path / "a.png"
    Image.new("RGB", (4, 4), color="blue").save(p, "PNG")
    ok, err, ext = validate_image(str(p))
    assert ok and ext == "png" and err == ""

    j = tmp_path / "b.jpg"
    Image.new("RGB", (4, 4), color="red").save(j, "JPEG")
    ok, err, ext = validate_image(str(j))
    assert ok and ext == "jpeg" and err == ""


def test_validate_webp(tmp_path) -> None:
    from PIL import Image

    w = tmp_path / "c.webp"
    try:
        Image.new("RGB", (8, 8), color="green").save(w, "WEBP")
    except Exception:
        pytest.skip("Pillow WebP encode not available")

    ok, err, ext = validate_image(str(w))
    assert ok and ext == "webp" and err == ""


def _png_chunk(tag: bytes, payload: bytes) -> bytes:
    import struct
    import zlib

    crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)


def _jpeg_segment(marker: int, payload: bytes) -> bytes:
    import struct

    return bytes((0xFF, marker)) + struct.pack(">H", len(payload) + 2) + payload


def _webp_chunk(tag: bytes, payload: bytes) -> bytes:
    import struct

    pad = b"\x00" if len(payload) % 2 else b""
    return tag + struct.pack("<I", len(payload)) + payload + pad


def test_strip_image_metadata_removes_exif_text_and_xmp() -> None:
    import struct

    png = (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", b"\x00" * 13)
        + _png_chunk(b"tEXt", b"Comment\x00secret-gps")
        + _png_chunk(b"eXIf", b"GPSdata")
        + _png_chunk(b"IDAT", b"pixels")
        + _png_chunk(b"IEND", b"")
    )
    clean = strip_image_metadata(png)
    assert b"secret-gps" not in clean
    assert b"GPSdata" not in clean
    assert b"pixels" in clean
    assert clean.endswith(_png_chunk(b"IEND", b""))

    jpeg = (
        b"\xff\xd8"
        + _jpeg_segment(0xE1, b"Exif\x00\x00GPSdata")
        + _jpeg_segment(0xFE, b"comment")
        + _jpeg_segment(0xE0, b"JFIF")
        + b"\xff\xda"
        + struct.pack(">H", 2)
        + b"pixels"
        + b"\xff\xd9"
    )
    clean = strip_image_metadata(jpeg)
    assert b"GPSdata" not in clean
    assert b"comment" not in clean
    assert b"JFIF" in clean
    assert b"pixels" in clean
    assert clean.startswith(b"\xff\xd8") and clean.endswith(b"\xff\xd9")

    body = (
        _webp_chunk(b"VP8 ", b"abcd")
        + _webp_chunk(b"EXIF", b"GPSdata")
        + _webp_chunk(b"XMP ", b"<x:xmp")
    )
    webp = b"RIFF" + struct.pack("<I", 4 + len(body)) + b"WEBP" + body
    clean = strip_image_metadata(webp)
    assert b"GPSdata" not in clean
    assert b"<x:xmp" not in clean
    assert b"abcd" in clean
    assert clean[8:12] == b"WEBP"


def test_strip_real_jpeg_drops_exif_and_keeps_pixels(tmp_path) -> None:
    import io

    from PIL import Image

    image = Image.new("RGB", (8, 8), "red")
    exif = image.getexif()
    exif[0x010F] = "secret-camera"
    path = tmp_path / "exif.jpg"
    image.save(path, "JPEG", exif=exif)
    raw = path.read_bytes()
    assert b"secret-camera" in raw

    clean = strip_image_metadata(raw)
    assert b"secret-camera" not in clean
    opened = Image.open(io.BytesIO(clean))
    opened.load()
    original = Image.open(io.BytesIO(raw))
    original.load()
    assert opened.size == original.size
    assert opened.tobytes() == original.tobytes()
