#!/usr/bin/env python3
"""Скачивает PNG Twemoji 72×72 для EMOJI_CHARS в i2pchat/gui/twemoji/.

Источник: https://github.com/jdecked/twemoji (графика Twitter, CC-BY 4.0).
Имена файлов — кодовые точки без U+FE0F, как в assets/72x72 апстрима.

Из корня репозитория::

  python3 scripts/vendor_twemoji.py
"""

from __future__ import annotations

import json
import sys
import unicodedata
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

_ASSET_BASE = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72"

_LICENSE_TEXT = """\
Twemoji graphics
Copyright Twitter, Inc. and other contributors

The PNG files in png/ are the 72×72 Twemoji images from
https://github.com/jdecked/twemoji (tag 15.1.0, assets/72x72).

Those graphics are licensed under Creative Commons Attribution 4.0
International (CC-BY 4.0):

  https://creativecommons.org/licenses/by/4.0/

Attribution: "Twemoji by Twitter, licensed under CC-BY 4.0."

The Twemoji library source is under a separate MIT license. This
directory ships only the graphics, not that source.
"""

_NOTICE_TEXT = """\
Twemoji (raster pack)
=====================

The files under png/ and manifest.json are the 72x72 PNGs from

  https://github.com/jdecked/twemoji
  tag 15.1.0, assets/72x72

Graphics are by Twitter, Inc. and other contributors and are licensed
under CC-BY 4.0. See LICENSE-TWEMOJI in this directory.

Refresh after changing EMOJI_CHARS, from the repo root:

  python3 scripts/vendor_twemoji.py
"""


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _codepoint_name(emoji: str, *, keep_fe0f: bool) -> str:
    chars = unicodedata.normalize("NFC", emoji)
    if not keep_fe0f:
        chars = "".join(ch for ch in chars if ord(ch) != 0xFE0F)
    return "-".join(f"{ord(ch):x}" for ch in chars)


def _download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "I2PChat-vendor-twemoji"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    if not data.startswith(b"\x89PNG"):
        raise ValueError(f"not a PNG: {url}")
    return data


def _fetch_one(emoji: str) -> tuple[str, bytes] | str:
    try:
        return _fetch_png(emoji)
    except RuntimeError as error:
        return str(error)


def _fetch_png(emoji: str) -> tuple[str, bytes]:
    stripped = _codepoint_name(emoji, keep_fe0f=False)
    full = _codepoint_name(emoji, keep_fe0f=True)
    names = [stripped] if stripped == full else [stripped, full]
    last_error: Exception | None = None
    for name in names:
        url = f"{_ASSET_BASE}/{name}.png"
        try:
            return name, _download(url)
        except (urllib.error.URLError, ValueError, TimeoutError) as error:
            last_error = error
    raise RuntimeError(f"{emoji!r}: {last_error}")


def main() -> int:
    repo_root = _repo_root()
    sys.path.insert(0, str(repo_root))
    from i2pchat.gui.emoji_data import EMOJI_CHARS  # noqa: E402
    from i2pchat.gui.emoji_paths import twemoji_root  # noqa: E402

    dest_root = twemoji_root().resolve()
    png_dir = dest_root / "png"
    png_dir.mkdir(parents=True, exist_ok=True)
    for stale in png_dir.glob("*.png"):
        stale.unlink()

    manifest: dict[str, str] = {}
    missing: list[str] = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        fetched = list(pool.map(_fetch_one, EMOJI_CHARS))
    for emoji, result in zip(EMOJI_CHARS, fetched, strict=True):
        if isinstance(result, str):
            missing.append(result)
            continue
        name, data = result
        fname = f"{name}.png"
        (png_dir / fname).write_bytes(data)
        manifest[emoji] = f"png/{fname}"

    (dest_root / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (dest_root / "LICENSE-TWEMOJI").write_text(_LICENSE_TEXT, encoding="utf-8")
    (dest_root / "NOTICE").write_text(_NOTICE_TEXT, encoding="utf-8")
    print(f"Saved {len(manifest)} PNGs -> {png_dir}")
    if missing:
        print(f"Missing ({len(missing)}):", file=sys.stderr)
        for line in missing:
            print(f"  {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
