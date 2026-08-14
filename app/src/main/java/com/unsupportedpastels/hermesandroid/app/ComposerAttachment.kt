package com.unsupportedpastels.hermesandroid.app

/**
 * A file the user staged in the composer. The [uri] is a client-local
 * content:// URI the remote host can never read directly — bytes are read
 * and uploaded (staged) at send time via `image.attach_bytes` / `file.attach`.
 */
data class ComposerAttachment(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
)
