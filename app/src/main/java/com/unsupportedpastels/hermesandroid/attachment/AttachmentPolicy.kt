package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import java.io.InputStream

/** Raised when a staged file exceeds its byte cap while streaming. */
class AttachmentTooLargeException(
    val attachmentName: String,
    val capBytes: Long,
    actualBytes: Long,
) : Exception("Attachment '$attachmentName' is $actualBytes bytes; cap is $capBytes bytes")

/** Raised when a staged file's bytes cannot be read at all. */
class AttachmentReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Image attachments ride the session's queued-image list; everything else is a `@file:` ref. */
enum class AttachmentKind { IMAGE, FILE }

sealed interface AttachmentAddResult {
    data object Accepted : AttachmentAddResult
    data class Rejected(val reason: String) : AttachmentAddResult
}

/** Result of staging the composer's attachments ahead of `prompt.submit`. */
data class StagedAttachments(
    val refTexts: List<String>,
    val names: List<String>,
)

/** Reads a staged attachment's bytes on the client (the remote host cannot see content:// URIs). */
fun interface AttachmentByteReader {
    suspend fun readBytes(attachment: ComposerAttachment): ByteArray
}

/**
 * Pure attachment policy: naming hygiene, image-vs-file routing, byte/count caps
 * (checked at metadata time AND again while streaming), and prompt-text assembly.
 */
object AttachmentPolicy {
    const val MAX_ATTACHMENTS = 5
    /** Under the gateway's 25 MiB per-image cap, leaving headroom for base64 framing. */
    const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_AGGREGATE_BYTES = 30L * 1024 * 1024
    const val MAX_DISPLAY_NAME_LENGTH = 120

    private val INVALID_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F\\u007F]")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /**
     * Reduce a hostile/qualified provider name to a safe basename: split on both
     * path separators, drop control + platform-invalid characters, strip leading
     * dots, cap the length, and fall back to "attachment".
     */
    fun sanitizeDisplayName(raw: String): String {
        val basename = raw.split('/', '\\').lastOrNull { it.isNotBlank() } ?: ""
        val cleaned = basename
            .replace(INVALID_NAME_CHARS, "")
            .trim()
            .trimStart('.')
            .take(MAX_DISPLAY_NAME_LENGTH)
        return cleaned.ifBlank { "attachment" }
    }

    /** Route by MIME type with a conservative extension fallback for unknown/absent types. */
    fun kindOf(mimeType: String?, displayName: String): AttachmentKind {
        val mime = mimeType?.lowercase()
        if (mime?.startsWith("image/") == true) return AttachmentKind.IMAGE
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return if (extension in IMAGE_EXTENSIONS) AttachmentKind.IMAGE else AttachmentKind.FILE
    }

    fun perKindCapBytes(kind: AttachmentKind): Long = when (kind) {
        AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
        AttachmentKind.FILE -> MAX_FILE_BYTES
    }

    /** Metadata-time admission: count cap, known-size per-kind cap, aggregate cap. */
    fun checkAdd(
        existing: List<ComposerAttachment>,
        candidate: ComposerAttachment,
    ): AttachmentAddResult {
        if (existing.any { it.uri == candidate.uri }) {
            return AttachmentAddResult.Rejected("${candidate.displayName} is already attached")
        }
        if (existing.size >= MAX_ATTACHMENTS) {
            return AttachmentAddResult.Rejected("Maximum of $MAX_ATTACHMENTS attachments")
        }
        val kind = kindOf(candidate.mimeType, candidate.displayName)
        val kindCap = perKindCapBytes(kind)
        if (candidate.sizeBytes > kindCap) {
            val mb = kindCap / (1024 * 1024)
            return AttachmentAddResult.Rejected("${candidate.displayName} exceeds the $mb MB limit for ${kind.name.lowercase()}s")
        }
        val aggregate = existing.sumOf { it.sizeBytes.coerceAtLeast(0) } + candidate.sizeBytes.coerceAtLeast(0)
        if (aggregate > MAX_AGGREGATE_BYTES) {
            return AttachmentAddResult.Rejected("Total attachment size exceeds the limit")
        }
        return AttachmentAddResult.Accepted
    }

    /**
     * Bounded streaming read: never materializes more than [capBytes] + 1 so an
     * unknown-size or dishonest provider cannot exhaust the Android heap.
     */
    fun readBounded(input: InputStream, capBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > capBytes) {
                throw AttachmentTooLargeException("attachment", capBytes, total)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Assemble the submitted prompt text: file `@file:` refs first, then the typed
     * text, then a server-style note when only images were attached and nothing was
     * typed — so `prompt.submit` never receives a blank payload with attachments.
     */
    fun composePromptText(
        typedText: String,
        fileRefs: List<String>,
        attachedNames: List<String>,
    ): String {
        if (fileRefs.isNotEmpty()) {
            val refs = fileRefs.joinToString("\n")
            return if (typedText.isNotBlank()) "$refs\n\n$typedText" else refs
        }
        if (typedText.isNotBlank()) return typedText
        if (attachedNames.isNotEmpty()) {
            return attachedNames.joinToString("\n") { "[User attached image: $it]" }
        }
        return ""
    }
}
