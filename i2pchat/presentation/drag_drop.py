"""
Pure validation/routing module for drag-and-drop file/image handling.

Qt layer in i2pchat/gui/main_qt.py wires dragEnterEvent / dropEvent; this module is
intentionally UI-free so tests can run without PyQt6.
"""

from __future__ import annotations

import os
from typing import Sequence

from i2pchat.presentation.media_kind import (
    INLINE_IMAGE_EXTENSIONS,
    is_inline_image_path,
    route_send,
)

# DropAction literals
SEND_FILE = "send_file"
SEND_IMAGE = "send_image"
REJECT = "reject"

DropAction = str  # one of the three constants above

_IMAGE_EXTENSIONS = INLINE_IMAGE_EXTENSIONS

# 100 MB default limit
_MAX_FILE_SIZE = 100 * 1024 * 1024


def _is_image_path(path: str) -> bool:
    return is_inline_image_path(path)


def action_for_path(path: str) -> DropAction:
    """Image extensions → send_image; everything else → send_file."""
    return route_send(path, as_image=None)


def classify_drop_paths(urls: Sequence[str]) -> list[tuple[str, DropAction]]:
    """Per-path actions for a multi-file drop (Kennedy g2387)."""
    out: list[tuple[str, DropAction]] = []
    for raw in urls:
        path = (raw or "").strip()
        if path:
            out.append((path, action_for_path(path)))
    return out


def classify_drop(mime_types: Sequence[str], urls: Sequence[str]) -> DropAction:
    """Return the appropriate DropAction for a drop event.

    *mime_types* are the MIME type strings from the drop event's mimeData.
    *urls* are the local file paths (already decoded from QUrl).

    Rules:
    - If no local file URLs are present → reject.
    - One image → send_image; one other file → send_file.
    - Multiple files → send_file (caller should use ``classify_drop_paths``).
    """
    _ = mime_types
    local_paths = [u for u in urls if u]
    if not local_paths:
        return REJECT
    if len(local_paths) > 1:
        return SEND_FILE
    return action_for_path(local_paths[0])


def validate_drop_file(path: str) -> tuple[bool, str]:
    """Return (ok, reason) for a candidate drop file path.

    Checks: existence, read permission, size limit.
    """
    if not path:
        return False, "No file path provided"
    if not os.path.exists(path):
        return False, f"File not found: {path}"
    if not os.path.isfile(path):
        return False, "Only regular files are supported"
    if not os.access(path, os.R_OK):
        return False, "File is not readable"
    try:
        size = os.path.getsize(path)
    except OSError as exc:
        return False, f"Cannot read file size: {exc}"
    if size > _MAX_FILE_SIZE:
        mb = _MAX_FILE_SIZE // (1024 * 1024)
        return False, f"File exceeds {mb} MB limit"
    if size == 0:
        return False, "File is empty"
    return True, ""


def _validate_image_with_pillow(path: str) -> tuple[bool, str]:
    """Optional Pillow-based image validation (best-effort)."""
    try:
        from PIL import Image, UnidentifiedImageError  # type: ignore
        try:
            with Image.open(path) as img:
                img.verify()
            return True, ""
        except (UnidentifiedImageError, Exception) as exc:
            return False, f"Invalid image: {exc}"
    except ImportError:
        return True, ""  # Pillow not available — skip deep check


def validate_drop_image(path: str) -> tuple[bool, str]:
    """Validate a dropped image file (extension check + optional Pillow check)."""
    ok, reason = validate_drop_file(path)
    if not ok:
        return ok, reason
    if not _is_image_path(path):
        _, ext = os.path.splitext(path)
        supported = ", ".join(sorted(_IMAGE_EXTENSIONS))
        return False, f"Unsupported image type '{ext}'. Supported: {supported}"
    return _validate_image_with_pillow(path)
