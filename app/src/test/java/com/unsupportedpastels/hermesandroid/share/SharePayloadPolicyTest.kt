package com.unsupportedpastels.hermesandroid.share

import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadPolicyTest {
    @Test
    fun acceptsTextAndDistinctContentAttachmentsWithinExistingComposerBounds() {
        val result = SharePayloadPolicy.build(
            text = "Review this",
            candidates = listOf(
                SharedAttachmentCandidate(
                    uri = "content://provider/image/1",
                    displayName = "../photo.png",
                    mimeType = "image/png",
                    sizeBytes = 1024,
                ),
                SharedAttachmentCandidate(
                    uri = "content://provider/file/2",
                    displayName = "report.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 2048,
                ),
            ),
        )

        assertEquals("Review this", result.payload.text)
        assertEquals(listOf("photo.png", "report.pdf"), result.payload.attachments.map { it.displayName })
        assertTrue(result.rejections.isEmpty())
    }

    @Test
    fun rejectsUnsafeSchemesDuplicatesOversizeAndItemsBeyondTheCountCapWithoutDroppingValidSiblings() {
        val candidates = buildList {
            add(SharedAttachmentCandidate("file:///private/secret", "secret", null, 10))
            add(SharedAttachmentCandidate("content://provider/duplicate", "first.txt", "text/plain", 10))
            add(SharedAttachmentCandidate("content://provider/duplicate", "second.txt", "text/plain", 10))
            add(
                SharedAttachmentCandidate(
                    "content://provider/large",
                    "large.pdf",
                    "application/pdf",
                    AttachmentPolicy.MAX_FILE_BYTES + 1,
                ),
            )
            repeat(AttachmentPolicy.MAX_ATTACHMENTS + 2) { index ->
                add(SharedAttachmentCandidate("content://provider/valid/$index", "valid-$index.txt", "text/plain", 10))
            }
        }

        val result = SharePayloadPolicy.build(text = null, candidates = candidates)

        assertEquals(AttachmentPolicy.MAX_ATTACHMENTS, result.payload.attachments.size)
        assertEquals(result.payload.attachments.map { it.uri }.distinct(), result.payload.attachments.map { it.uri })
        assertTrue(result.payload.attachments.all { it.uri.startsWith("content://") })
        assertTrue(result.rejections.size >= 4)
    }

    @Test
    fun boundsSharedTextAndRejectsAnEmptyPayload() {
        val bounded = SharePayloadPolicy.build("x".repeat(SharePayloadPolicy.MAX_TEXT_CHARS + 10), emptyList())
        assertEquals(SharePayloadPolicy.MAX_TEXT_CHARS, bounded.payload.text.length)

        val empty = SharePayloadPolicy.build("   ", emptyList())
        assertTrue(empty.payload.isEmpty)
        assertTrue(empty.rejections.isNotEmpty())
    }
}
