package com.jambus.heji

import java.util.Locale

/** Pure presentation rules for the Vault browser; mutation protection remains in [VaultPathPolicy]. */
object VaultBrowserPolicy {
    enum class FileKind { MARKDOWN, IMAGE, VIDEO, OTHER }

    private val hiddenSegments = setOf(".obsidian", ".markbook", ".trash")
    private val readOnlyAttachmentSegments = setOf("assets", "attachments")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")
    private val videoExtensions = setOf("mp4", "3gp", "m4v", "mov", "webm", "mkv")

    fun isHidden(relativePath: String): Boolean {
        val segments = pathSegments(relativePath)
        return segments.any { it in hiddenSegments } || segments.lastOrNull()?.startsWith(".markbook-") == true
    }

    fun isReadOnlyAttachmentPath(relativePath: String): Boolean =
        pathSegments(relativePath).any { it in readOnlyAttachmentSegments }

    fun canPermanentlyDeleteAttachment(
        relativePath: String,
        name: String,
        mimeType: String?,
        isDirectory: Boolean
    ): Boolean = !isDirectory && !isHidden(relativePath) && isReadOnlyAttachmentPath(relativePath) &&
        fileKind(name, mimeType) in setOf(FileKind.IMAGE, FileKind.VIDEO)

    fun fileKind(name: String, mimeType: String?): FileKind {
        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            name.endsWith(".md", ignoreCase = true) -> FileKind.MARKDOWN
            mime.startsWith("image/") || extension in imageExtensions -> FileKind.IMAGE
            mime.startsWith("video/") || extension in videoExtensions -> FileKind.VIDEO
            else -> FileKind.OTHER
        }
    }

    fun viewerMimeType(name: String, mimeType: String?): String {
        val supplied = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        if (supplied != null) return supplied
        return when (fileKind(name, mimeType)) {
            FileKind.IMAGE -> "image/*"
            FileKind.VIDEO -> "video/*"
            else -> "application/octet-stream"
        }
    }

    private fun pathSegments(relativePath: String): List<String> = relativePath
        .split('/')
        .filter { it.isNotBlank() }
        .map { it.lowercase(Locale.ROOT) }
}
