"""Incoming file folders and the accept-or-path decision."""

from __future__ import annotations

import os

import pytest

from i2pchat.storage.media_prefs import (
    clear_ephemeral_media,
    incoming_files_dir,
    incoming_images_dir,
    resolve_save_as,
    save_media_prefs,
)


@pytest.fixture
def profiles(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "i2pchat.storage.media_prefs.get_profiles_dir",
        lambda: str(tmp_path),
    )
    return tmp_path


def test_empty_prefs_use_the_default_folders(profiles):
    assert incoming_files_dir() == str(profiles / "downloads")
    assert incoming_images_dir() == str(profiles / "images")


def test_custom_paths_may_be_the_same_folder(profiles):
    shared = profiles / "media"
    save_media_prefs(files_dir=str(shared), images_dir=str(shared), ephemeral=False)
    assert incoming_files_dir() == str(shared)
    assert incoming_images_dir() == str(shared)
    assert shared.is_dir()


def test_ephemeral_mode_uses_a_cache_that_exit_deletes(profiles):
    save_media_prefs(files_dir=str(profiles / "media"), images_dir="", ephemeral=True)
    files = incoming_files_dir()
    images = incoming_images_dir()
    assert files.endswith(os.path.join("incoming-files"))
    assert images.endswith(os.path.join("incoming-images"))
    assert str(profiles) not in files
    assert str(profiles) not in images
    open(os.path.join(files, "secret.bin"), "w").close()
    clear_ephemeral_media()
    assert not os.path.isdir(files)
    assert not os.path.isdir(os.path.join(str(profiles), "cache"))


def test_save_as_rejects_a_bare_directory(tmp_path):
    assert resolve_save_as(str(tmp_path) + os.sep) is None
    chosen = resolve_save_as(str(tmp_path / "notes.txt"))
    assert chosen == str(tmp_path / "notes.txt")
