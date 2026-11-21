package org.i2pchat.android

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

enum class MediaKind { Image, Video, Audio, Other }

// MimeTypeMap lacks some of these on older Android releases.
private val extraMimeByExt = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
    "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    "heic" to "image/heic", "heif" to "image/heif",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp",
    "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
    "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
    "wav" to "audio/x-wav", "flac" to "audio/flac", "amr" to "audio/amr",
    "pdf" to "application/pdf",
)

/** MIME type from the extension of a file name or path, or null when unknown. */
fun mimeTypeForName(nameOrPath: String): String? {
    val ext = File(nameOrPath).extension.lowercase(Locale.ROOT)
    if (ext.isEmpty()) return null
    // Prefer the local table so JVM unit tests (no MimeTypeMap) and older APIs stay consistent.
    extraMimeByExt[ext]?.let { return it }
    return runCatching {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }.getOrNull()
}

fun mediaKindForName(nameOrPath: String): MediaKind {
    val mime = mimeTypeForName(nameOrPath) ?: return MediaKind.Other
    return when {
        mime.startsWith("image/") -> MediaKind.Image
        mime.startsWith("video/") -> MediaKind.Video
        mime.startsWith("audio/") -> MediaKind.Audio
        else -> MediaKind.Other
    }
}
