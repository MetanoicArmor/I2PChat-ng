package org.i2pchat.android

import java.io.File

/**
 * Unified chat attachment view-model. Wire/history still use `[image]` / `[file]`;
 * UI renders from this instead of parallel imagePath/filePath branches.
 */
data class ChatAttachment(
    val kind: MediaKind,
    val path: String = "",
    val memoryKey: String = "",
    val sealedId: String = "",
    val displayName: String = "",
    /** True for Send-picture / `[image]` history (inline thumb), not for document sends. */
    val inlinePreview: Boolean,
) {
    val hasOpenablePath: Boolean get() = path.isNotBlank()
    val hasMemory: Boolean get() = memoryKey.isNotBlank()
    val hasSealed: Boolean get() = sealedId.isNotBlank()
    val isPresent: Boolean
        get() = hasOpenablePath || hasMemory || hasSealed || displayName.isNotBlank()
}

/**
 * Build an attachment from history text + resolved local paths.
 * Returns null for plain text messages.
 */
fun buildChatAttachment(
    text: String,
    imagePath: String = "",
    filePath: String = "",
    memoryImage: String = "",
    sealedFile: String = "",
): ChatAttachment? {
    val isImageLine = text.startsWith("[image] ")
    val isFileLine = text.startsWith("[file] ")
    if (!isImageLine && !isFileLine && memoryImage.isBlank() && sealedFile.isBlank()) {
        return null
    }
    val sealed = parseSealedFileHistory(text)
    val displayName = when {
        imagePath.isNotBlank() -> File(imagePath).name
        filePath.isNotBlank() -> File(filePath).name
        sealed != null -> sealed.displayName
        isFileLine -> File(text.removePrefix("[file] ").trim()).name
        isImageLine -> File(text.removePrefix("[image] ").trim()).name
        else -> ""
    }
    val path = imagePath.ifBlank { filePath }
    val kindSource = path.ifBlank { displayName }
    val kind = mediaKindForName(kindSource)
    val sealedId = sealedFile.ifBlank { sealed?.id.orEmpty() }
    val inlinePreview = isImageLine || memoryImage.isNotBlank()
    return ChatAttachment(
        kind = kind,
        path = path,
        memoryKey = memoryImage,
        sealedId = sealedId,
        displayName = displayName,
        inlinePreview = inlinePreview,
    )
}
