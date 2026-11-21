"""Unit tests for i2pchat.presentation.media_kind (attachment UX intent routing)."""

from __future__ import annotations

import unittest

from i2pchat.presentation.media_kind import (
    MediaKind,
    classify_path,
    is_inline_image_path,
    route_send,
)


class TestClassifyPath(unittest.TestCase):
    def test_kinds(self):
        cases = [
            ("photo.png", MediaKind.IMAGE),
            ("/tmp/a.JPEG", MediaKind.IMAGE),
            ("clip.webp", MediaKind.IMAGE),
            ("anim.gif", MediaKind.IMAGE),
            ("clip.mp4", MediaKind.VIDEO),
            ("song.mp3", MediaKind.AUDIO),
            ("doc.pdf", MediaKind.OTHER),
            ("no_ext", MediaKind.OTHER),
        ]
        for path, expected in cases:
            with self.subTest(path=path):
                self.assertEqual(classify_path(path), expected)


class TestInlineImagePath(unittest.TestCase):
    def test_supported(self):
        for path in ("a.png", "b.jpg", "c.jpeg", "d.webp", "E.PNG"):
            self.assertTrue(is_inline_image_path(path), path)

    def test_unsupported(self):
        for path in ("a.gif", "b.bmp", "c.mp4", "d.pdf", "e"):
            self.assertFalse(is_inline_image_path(path), path)


class TestRouteSend(unittest.TestCase):
    def test_as_image_true_always_image_api(self):
        self.assertEqual(route_send("x.png", as_image=True), "send_image")
        self.assertEqual(route_send("x.pdf", as_image=True), "send_image")

    def test_as_image_false_always_file_api(self):
        self.assertEqual(route_send("x.png", as_image=False), "send_file")
        self.assertEqual(route_send("x.webp", as_image=False), "send_file")
        self.assertEqual(route_send("x.zip", as_image=False), "send_file")

    def test_auto_extension(self):
        self.assertEqual(route_send("photo.png", as_image=None), "send_image")
        self.assertEqual(route_send("photo.JPG", as_image=None), "send_image")
        self.assertEqual(route_send("archive.zip", as_image=None), "send_file")
        self.assertEqual(route_send("photo.gif", as_image=None), "send_file")


if __name__ == "__main__":
    unittest.main()
