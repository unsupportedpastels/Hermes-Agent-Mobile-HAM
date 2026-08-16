package com.unsupportedpastels.hermesandroid.share

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment

/** Bounded content received from Android's system Sharesheet and awaiting a user-selected destination. */
data class SharePayload(
    val requestId: Long,
    val text: String,
    val attachments: List<ComposerAttachment>,
    val rejections: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isBlank() && attachments.isEmpty()
}

data class SharedAttachmentCandidate(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

data class SharePayloadBuildResult(
    val payload: SharePayload,
    val rejections: List<String>,
)
