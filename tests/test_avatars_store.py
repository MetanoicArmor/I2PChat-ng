"""Avatar store + delivery ledger."""

from __future__ import annotations

import secrets
import tempfile
import unittest

from i2pchat.storage.avatars import (
    AVATAR_WIRE_FILENAME,
    get_own_avatar,
    get_peer_avatar,
    is_avatar_wire_filename,
    load_ledger,
    mark_delivered,
    mark_need_request_all_contacts,
    peers_needing_avatar_request,
    peers_needing_own_avatar,
    set_own_avatar,
    set_peer_avatar,
    sha256_hex,
)


class AvatarStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.pdir = self._tmp.name
        self.key = secrets.token_bytes(32)
        self.peer = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_own_avatar_roundtrip_and_delivery(self) -> None:
        blob = b"\xff\xd8\xff" + b"jpeg-bytes" * 20
        digest = set_own_avatar(self.pdir, self.key, blob)
        self.assertEqual(digest, sha256_hex(blob))
        self.assertEqual(get_own_avatar(self.pdir, self.key), blob)
        self.assertEqual(
            peers_needing_own_avatar(self.pdir, self.key, [self.peer]),
            [self.peer],
        )
        mark_delivered(self.pdir, self.key, self.peer, digest)
        self.assertEqual(
            peers_needing_own_avatar(self.pdir, self.key, [self.peer]),
            [],
        )
        # New avatar invalidates delivery marks.
        set_own_avatar(self.pdir, self.key, blob + b"x")
        self.assertEqual(
            peers_needing_own_avatar(self.pdir, self.key, [self.peer]),
            [self.peer],
        )

    def test_peer_avatar_and_import_re_request(self) -> None:
        blob = b"PNG\x00peer"
        set_peer_avatar(self.pdir, self.key, self.peer, blob)
        self.assertEqual(get_peer_avatar(self.pdir, self.key, self.peer), blob)
        mark_need_request_all_contacts(self.pdir, self.key, [self.peer])
        self.assertIsNone(get_peer_avatar(self.pdir, self.key, self.peer))
        self.assertEqual(peers_needing_avatar_request(self.pdir, self.key), [self.peer])
        ledger = load_ledger(self.pdir, self.key)
        self.assertEqual(ledger.need_request, [self.peer])

    def test_wire_filename(self) -> None:
        self.assertTrue(is_avatar_wire_filename(AVATAR_WIRE_FILENAME))
        self.assertTrue(is_avatar_wire_filename("/tmp/.i2pchat-avatar.jpg"))
        self.assertFalse(is_avatar_wire_filename("photo.jpg"))


if __name__ == "__main__":
    unittest.main()
