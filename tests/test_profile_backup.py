import io
import json
import os
import secrets
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from i2pchat import crypto
from i2pchat.core.i2p_chat_core import get_profile_data_dir
from i2pchat.storage.chat_history import HistoryEntry, load_history, save_history
from i2pchat.storage.contact_book import save_book, ContactBook, ContactRecord
from i2pchat.storage.profile_backup import (
    BackupError,
    export_history_bundle,
    export_profile_bundle,
    import_history_bundle,
    import_profile_bundle,
    _build_manifest,
    _build_tar_payload,
    _encrypt_payload,
)


IDENTITY_KEY = secrets.token_bytes(32)
PEER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b32.i2p"


@unittest.skipUnless(crypto.NACL_AVAILABLE, "PyNaCl required")
class ProfileBackupTests(unittest.TestCase):
    def _seed_profile(self, profiles_dir: str, profile: str) -> None:
        pdir = get_profile_data_dir(profile, create=True, app_root=profiles_dir)
        with open(os.path.join(pdir, f"{profile}.dat"), "wb") as f:
            f.write(b"profile-secret")
        save_book(
            os.path.join(pdir, f"{profile}.contacts.json"),
            ContactBook(
                contacts=[ContactRecord(addr=PEER, display_name="Alice", note="trusted")],
                last_active_peer=PEER,
            ),
        )
        save_history(
            pdir,
            profile,
            PEER,
            [HistoryEntry(kind="me", text="hello", ts="2026-03-30T10:00:00Z")],
            IDENTITY_KEY,
        )

    def test_profile_backup_roundtrip_restores_sidecars_and_history(self) -> None:
        with tempfile.TemporaryDirectory() as src_td, tempfile.TemporaryDirectory() as dst_td:
            self._seed_profile(src_td, "alice")
            bundle_path = os.path.join(src_td, "alice.i2pchat-profile-backup")
            summary = export_profile_bundle(bundle_path, src_td, "alice", "passphrase", include_history=True)
            self.assertEqual(summary.bundle_type, "profile")
            self.assertGreaterEqual(summary.file_count, 3)

            imported = import_profile_bundle(bundle_path, dst_td, "passphrase")
            self.assertEqual(imported.target_profile, "alice")
            alice_dir = get_profile_data_dir("alice", create=False, app_root=dst_td)
            self.assertTrue(os.path.isfile(os.path.join(alice_dir, "alice.dat")))
            self.assertTrue(os.path.isfile(os.path.join(alice_dir, "alice.contacts.json")))
            loaded = load_history(
                alice_dir, "alice", PEER, IDENTITY_KEY, app_data_root=dst_td
            )
            self.assertEqual([x.text for x in loaded], ["hello"])

    def test_import_rejects_blindbox_dat_overwrite(self) -> None:
        # A crafted bundle whose "blindbox/dat" member would map onto
        # "<profile>.dat" (the private identity key) must be refused.
        with tempfile.TemporaryDirectory() as td:
            files = {
                "profile.dat": b"legit-identity",
                "blindbox/dat": b"OVERWRITE-THE-IDENTITY-KEY",
            }
            manifest = _build_manifest(
                bundle_type="profile", profile="alice", files=files
            )
            payload = _build_tar_payload(manifest, files)
            bundle_path = os.path.join(td, "evil.i2pchat-profile-backup")
            with open(bundle_path, "wb") as f:
                f.write(_encrypt_payload(payload, "passphrase"))
            with self.assertRaises(BackupError):
                import_profile_bundle(bundle_path, td, "passphrase")

    def test_import_rejects_dot_dot_profile_name_from_manifest(self) -> None:
        # ".." passes the profile-name charset but names the parent of the
        # profiles dir, so the identity file would land outside any profile.
        for evil in ("..", "."):
            with tempfile.TemporaryDirectory() as td:
                profiles_dir = os.path.join(td, "profiles")
                os.makedirs(profiles_dir)
                files = {"profile.dat": b"legit-identity"}
                manifest = _build_manifest(bundle_type="profile", profile=evil, files=files)
                payload = _build_tar_payload(manifest, files)
                bundle_path = os.path.join(td, "evil.i2pchat-profile-backup")
                with open(bundle_path, "wb") as f:
                    f.write(_encrypt_payload(payload, "passphrase"))
                with self.assertRaises((BackupError, ValueError)):
                    import_profile_bundle(bundle_path, profiles_dir, "passphrase")
                self.assertEqual(
                    sorted(os.listdir(td)), ["evil.i2pchat-profile-backup", "profiles"]
                )
                self.assertEqual(os.listdir(profiles_dir), [])

    def _write_bundle(self, td: str, bundle_type: str, files: dict[str, bytes], name: str) -> str:
        # Built with tarfile directly: the exporter refuses unsafe names, and
        # the point here is what the importer does with a bundle that was not
        # produced by our exporter.
        manifest = _build_manifest(bundle_type=bundle_type, profile="alice", files=files)
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tf:
            raw = json.dumps(manifest).encode("utf-8")
            info = tarfile.TarInfo("manifest.json")
            info.size = len(raw)
            tf.addfile(info, io.BytesIO(raw))
            for logical, content in files.items():
                info = tarfile.TarInfo(f"payload/{logical}")
                info.size = len(content)
                tf.addfile(info, io.BytesIO(content))
        bundle_path = os.path.join(td, name)
        with open(bundle_path, "wb") as f:
            f.write(_encrypt_payload(buf.getvalue(), "passphrase"))
        return bundle_path

    def test_history_import_rejects_unsafe_history_suffix(self) -> None:
        # "history/<suffix>" becomes "<profile>.history.<suffix>"; a suffix with
        # a separator or a parent component must not escape the profile dir.
        for evil in ("../../escape.json", "sub/dir.json", ".."):
            with tempfile.TemporaryDirectory() as td:
                profiles_dir = os.path.join(td, "profiles")
                os.makedirs(profiles_dir)
                bundle_path = self._write_bundle(
                    td, "history", {f"history/{evil}": b"{}"}, "evil.i2pchat-history-backup"
                )
                with self.assertRaises(BackupError):
                    import_history_bundle(bundle_path, profiles_dir, "alice", "passphrase")
                self.assertEqual(
                    sorted(os.listdir(td)), ["evil.i2pchat-history-backup", "profiles"]
                )

    def test_bundle_member_size_is_bounded(self) -> None:
        from i2pchat.storage import profile_backup as pb

        with tempfile.TemporaryDirectory() as td:
            profiles_dir = os.path.join(td, "profiles")
            os.makedirs(profiles_dir)
            bundle_path = self._write_bundle(
                td, "history", {"history/big.json": b"0" * 4096}, "big.i2pchat-history-backup"
            )
            with patch.object(pb, "MAX_BUNDLE_MEMBER_BYTES", 1024):
                with self.assertRaises(BackupError):
                    import_history_bundle(bundle_path, profiles_dir, "alice", "passphrase")
            with patch.object(pb, "MAX_BUNDLE_TOTAL_BYTES", 1024):
                with self.assertRaises(BackupError):
                    import_history_bundle(bundle_path, profiles_dir, "alice", "passphrase")

    def test_history_backup_roundtrip_skip_conflicts(self) -> None:
        with tempfile.TemporaryDirectory() as src_td, tempfile.TemporaryDirectory() as dst_td:
            self._seed_profile(src_td, "alice")
            self._seed_profile(dst_td, "alice")
            dst_alice = get_profile_data_dir("alice", create=False, app_root=dst_td)
            save_history(
                dst_alice,
                "alice",
                PEER,
                [HistoryEntry(kind="me", text="existing", ts="2026-03-30T12:00:00Z")],
                IDENTITY_KEY,
            )
            bundle_path = os.path.join(src_td, "alice.i2pchat-history-backup")
            export_history_bundle(bundle_path, src_td, "alice", "passphrase")

            imported = import_history_bundle(
                bundle_path,
                dst_td,
                "alice",
                "passphrase",
                conflict_mode="skip",
            )
            self.assertEqual(imported.skipped_files, 1)
            loaded = load_history(
                dst_alice, "alice", PEER, IDENTITY_KEY, app_data_root=dst_td
            )
            self.assertEqual([x.text for x in loaded], ["existing"])

    def test_history_backup_roundtrip_overwrite_conflicts(self) -> None:
        with tempfile.TemporaryDirectory() as src_td, tempfile.TemporaryDirectory() as dst_td:
            self._seed_profile(src_td, "alice")
            self._seed_profile(dst_td, "alice")
            dst_alice = get_profile_data_dir("alice", create=False, app_root=dst_td)
            save_history(
                dst_alice,
                "alice",
                PEER,
                [HistoryEntry(kind="me", text="existing", ts="2026-03-30T12:00:00Z")],
                IDENTITY_KEY,
            )
            bundle_path = os.path.join(src_td, "alice.i2pchat-history-backup")
            export_history_bundle(bundle_path, src_td, "alice", "passphrase")

            imported = import_history_bundle(
                bundle_path,
                dst_td,
                "alice",
                "passphrase",
                conflict_mode="overwrite",
            )
            self.assertEqual(imported.skipped_files, 0)
            loaded = load_history(
                dst_alice, "alice", PEER, IDENTITY_KEY, app_data_root=dst_td
            )
            self.assertEqual([x.text for x in loaded], ["hello"])

    def test_wrong_passphrase_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as src_td, tempfile.TemporaryDirectory() as dst_td:
            self._seed_profile(src_td, "alice")
            bundle_path = os.path.join(src_td, "alice.i2pchat-profile-backup")
            export_profile_bundle(bundle_path, src_td, "alice", "passphrase", include_history=True)
            with self.assertRaises(BackupError):
                import_profile_bundle(bundle_path, dst_td, "wrong-passphrase")


if __name__ == "__main__":
    unittest.main()
