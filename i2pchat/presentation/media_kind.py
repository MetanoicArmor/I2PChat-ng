"""Presentation-layer media classification and send-intent routing.

Wire protocol (F vs G) is unchanged: this only decides which local API to call
based on user intent and file extension. Mirrors Android MediaTypes.kt kinds.
"""

from __future__ import annotations

import os
from enum import Enum
from typing import Literal, Optional

# Inline image transfer (G) supports these extensions only.
INLINE_IMAGE_EXTENSIONS = frozenset({".png", ".jpg", ".jpeg", ".webp"})

_VIDEO_EXTENSIONS = frozenset(
    {".mp4", ".m4v", ".3gp", ".webm", ".mkv", ".mov"}
)
_AUDIO_EXTENSIONS = frozenset(
    {".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".amr"}
)

SendApi = Literal["send_image", "send_file"]


class MediaKind(str, Enum):
    IMAGE = "image"
    VIDEO = "video"
    AUDIO = "audio"
    OTHER = "other"


def _ext(path: str) -> str:
    return os.path.splitext(path or "")[1].lower()


def is_inline_image_path(path: str) -> bool:
    """True when the path extension is allowed for Send picture / paste / drop-as-image."""
    return _ext(path) in INLINE_IMAGE_EXTENSIONS


def classify_path(path: str) -> MediaKind:
    """MIME-ish kind from extension (name or path). Unknown → OTHER."""
    ext = _ext(path)
    if not ext:
        return MediaKind.OTHER
    if ext in INLINE_IMAGE_EXTENSIONS or ext in {
        ".gif",
        ".bmp",
        ".heic",
        ".heif",
    }:
        return MediaKind.IMAGE
    if ext in _VIDEO_EXTENSIONS:
        return MediaKind.VIDEO
    if ext in _AUDIO_EXTENSIONS:
        return MediaKind.AUDIO
    return MediaKind.OTHER


def route_send(path: str, *, as_image: Optional[bool] = None) -> SendApi:
    """Map a local path + send intent to the core API to call.

    * ``as_image=True`` — always ``send_image`` (caller validates the file).
    * ``as_image=False`` — always ``send_file`` (document even for PNG).
    * ``as_image=None`` — extension heuristic (drag-drop / auto): inline images
      via ``send_image``, everything else via ``send_file``.
    """
    if as_image is True:
        return "send_image"
    if as_image is False:
        return "send_file"
    if is_inline_image_path(path):
        return "send_image"
    return "send_file"
