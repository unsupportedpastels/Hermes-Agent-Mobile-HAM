package com.unsupportedpastels.hermesandroid.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicLong

private const val MAX_FORWARDED_URIS = 20
private val requestIds = AtomicLong()

internal fun nextShareRequestId(): Long = requestIds.incrementAndGet()

/** Converts an untrusted public share intent into bounded in-app composer metadata. */
internal fun parseIncomingShare(context: Context, incoming: Intent, requestId: Long): SharePayload? {
    if (incoming.action != Intent.ACTION_SEND && incoming.action != Intent.ACTION_SEND_MULTIPLE) return null
    // MainActivity is singleTop and may be recreated with its current intent.
    // Consume the action before parsing so this share cannot be staged twice.
    incoming.action = null
    val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        ?.take(SharePayloadPolicy.MAX_TEXT_CHARS)
        .orEmpty()
    val uris = incoming.sharedContentUris()
    if (text.isBlank() && uris.isEmpty()) return null

    val candidates = uris.mapNotNull { uri ->
        resolveCandidate(context, uri)
    }
    val result = SharePayloadPolicy.build(text, candidates, requestId)
    return result.payload.takeUnless(SharePayload::isEmpty)
}

private fun Intent.sharedContentUris(): List<Uri> {
    val values = buildList {
        clipData?.let { clip ->
            repeat(clip.itemCount.coerceAtMost(MAX_FORWARDED_URIS)) { index ->
                clip.getItemAt(index).uri?.let(::add)
            }
        }
        @Suppress("DEPRECATION")
        when (val stream = extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> add(stream)
            is ArrayList<*> -> stream.filterIsInstance<Uri>().forEach(::add)
        }
    }
    return values
        .asSequence()
        .filter { it.scheme.equals("content", ignoreCase = true) }
        .distinctBy(Uri::toString)
        .take(MAX_FORWARDED_URIS)
        .toList()
}

private fun resolveCandidate(context: Context, uri: Uri): SharedAttachmentCandidate? = runCatching {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    var rawName: String? = null
    var sizeBytes = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) rawName = cursor.getString(nameColumn)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
        }
    }
    SharedAttachmentCandidate(
        uri = uri.toString(),
        displayName = rawName ?: uri.lastPathSegment.orEmpty(),
        mimeType = context.contentResolver.getType(uri),
        sizeBytes = sizeBytes,
    )
}.getOrNull()
