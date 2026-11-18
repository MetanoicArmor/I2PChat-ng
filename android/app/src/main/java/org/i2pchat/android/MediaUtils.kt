package org.i2pchat.android

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

enum class MediaKind {
    Image,
    Video,
    Audio,
    Other,
}

object MediaUtils {
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif")
    private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr")

    fun mediaPath(msg: MessageUi): String = msg.imagePath.ifBlank { msg.filePath }

    fun kindFor(path: String): MediaKind {
        val ext = File(path).extension.lowercase()
        return when {
            ext in imageExt -> MediaKind.Image
            ext in videoExt -> MediaKind.Video
            ext in audioExt -> MediaKind.Audio
            else -> {
                val mime = mimeFor(path)
                when {
                    mime.startsWith("image/") -> MediaKind.Image
                    mime.startsWith("video/") -> MediaKind.Video
                    mime.startsWith("audio/") -> MediaKind.Audio
                    else -> MediaKind.Other
                }
            }
        }
    }

    fun mimeFor(path: String): String {
        val ext = File(path).extension.lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when {
            ext in imageExt -> "image/*"
            ext in videoExt -> "video/*"
            ext in audioExt -> "audio/*"
            ext == "pdf" -> "application/pdf"
            else -> "*/*"
        }
    }

    fun contentUri(context: Context, path: String): Uri? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrElse { Uri.fromFile(file) }
    }

    fun copyToUri(context: Context, sourcePath: String, dest: Uri): Boolean {
        val src = File(sourcePath)
        if (!src.isFile) return false
        return runCatching {
            context.contentResolver.openOutputStream(dest)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
    }
}
