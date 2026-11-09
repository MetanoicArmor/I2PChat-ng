"""Text-art rendering used by /img, /img-bw and inline image previews."""

from __future__ import annotations

from pathlib import Path

import pytest
from PIL import Image

from i2pchat.core.i2p_chat_core import render_braille, render_bw
from i2pchat.gui.chat_python import I2PChat


def _picture(tmp_path: Path, size: tuple[int, int]) -> str:
    path = tmp_path / f"pic-{size[0]}x{size[1]}.png"
    img = Image.new("L", size, 255)
    for x in range(size[0] // 2):
        for y in range(size[1]):
            img.putpixel((x, y), 0)
    img.save(path)
    return str(path)


@pytest.mark.parametrize("size", [(200, 100), (81, 3), (4000, 5), (40, 40)])
def test_images_wider_than_the_limit_are_scaled_not_crashed(
    tmp_path: Path, size: tuple[int, int]
) -> None:
    path = _picture(tmp_path, size)
    bw = render_bw(path)
    assert bw and max(len(line) for line in bw) <= 80
    braille = render_braille(path)
    assert all(len(line) <= 40 for line in braille)


def test_wide_image_keeps_its_proportions(tmp_path: Path) -> None:
    lines = render_bw(_picture(tmp_path, (200, 100)))
    assert len(lines) == 40
    # Left half is black, right half is white (trailing spaces are stripped).
    assert lines[0] == "█" * 40


def test_tui_reports_a_file_that_is_not_an_image(tmp_path: Path) -> None:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]
    not_image = tmp_path / "notes.txt"
    not_image.write_text("plain text", encoding="utf-8")

    assert I2PChat._render_image_lines(app, str(not_image), bw=False) is None
    assert I2PChat._render_image_lines(app, str(not_image), bw=True) is None
    assert [kind for kind, _ in posted] == ["error", "error"]
    assert str(not_image) in posted[0][1]


def test_tui_renders_a_wide_picture_for_img_commands(tmp_path: Path) -> None:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]
    path = _picture(tmp_path, (200, 100))

    assert I2PChat._render_image_lines(app, path, bw=False)
    assert I2PChat._render_image_lines(app, path, bw=True)
    assert posted == []
