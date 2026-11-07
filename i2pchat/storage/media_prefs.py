"""Where incoming files and images are written.

Empty paths mean the usual ``downloads/`` and ``images/`` directories under the
app data root. Both paths may be the same directory. Ephemeral mode writes into
a cache that ``clear_ephemeral_media`` deletes on exit and on profile switch.
"""

from __future__ import annotations

import json
import os
import shutil
from typing import Optional

from i2pchat.core.i2p_chat_core import get_profiles_dir


def _prefs_path() -> str:
    return os.path.join(get_profiles_dir(), "ui_prefs.json")


def _read() -> dict:
    try:
        with open(_prefs_path(), "r", encoding="utf-8") as handle:
            data = json.load(handle)
        if isinstance(data, dict):
            return data
    except (OSError, json.JSONDecodeError):
        pass
    return {}


def _write(data: dict) -> None:
    path = _prefs_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(data, handle)
    os.replace(tmp, path)


def load_media_prefs() -> tuple[str, str, bool]:
    data = _read()
    files = str(data.get("files_dir") or "").strip()
    images = str(data.get("images_dir") or "").strip()
    ephemeral = data.get("ephemeral_media") is True
    return files, images, ephemeral


def save_media_prefs(*, files_dir: str, images_dir: str, ephemeral: bool) -> None:
    data = _read()
    data["files_dir"] = (files_dir or "").strip()
    data["images_dir"] = (images_dir or "").strip()
    data["ephemeral_media"] = bool(ephemeral)
    _write(data)


def _cache_dir(kind: str) -> str:
    return os.path.join(get_profiles_dir(), "cache", f"incoming-{kind}")


def _prepare(path: str, *, private: bool) -> str:
    os.makedirs(path, exist_ok=True)
    if private:
        try:
            os.chmod(path, 0o700)
        except OSError:
            pass
    return path


def incoming_files_dir() -> str:
    custom, _images, ephemeral = load_media_prefs()
    if ephemeral:
        return _prepare(_cache_dir("files"), private=True)
    if custom:
        return _prepare(os.path.expanduser(custom), private=False)
    return _prepare(os.path.join(get_profiles_dir(), "downloads"), private=True)


def incoming_images_dir() -> str:
    _files, custom, ephemeral = load_media_prefs()
    if ephemeral:
        return _prepare(_cache_dir("images"), private=True)
    if custom:
        return _prepare(os.path.expanduser(custom), private=False)
    return _prepare(os.path.join(get_profiles_dir(), "images"), private=True)


def clear_ephemeral_media() -> None:
    """Delete the cache used by ephemeral mode. Safe to call when it is off."""
    cache = os.path.join(get_profiles_dir(), "cache")
    if os.path.isdir(cache):
        shutil.rmtree(cache, ignore_errors=True)


def resolve_save_as(path: str) -> Optional[str]:
    """A user-chosen destination, or ``None`` when the path is empty or unsafe."""
    text = os.path.expanduser((path or "").strip())
    if not text or text.endswith(os.sep):
        return None
    parent = os.path.dirname(text) or "."
    try:
        os.makedirs(parent, exist_ok=True)
    except OSError:
        return None
    return text
