package com.unsupportedpastels.hermesandroid.voice

/**
 * Turns successive snapshots of a streaming assistant message into append-only
 * deltas for the speech stream. The server's `SentenceChunker` sanitizes and
 * cuts sentences across delta boundaries, so deltas are fed raw — whitespace
 * preserved, no client-side sanitization.
 *
 * If the visible text is ever *not* an extension of what was already fed (a
 * server-side replacement/rewrite), feeding pauses until the authoritative
 * final text arrives; only a suffix that cleanly extends the fed prefix is
 * spoken then. Speech may briefly lag a rewrite but never duplicates it.
 */
class SpeechDeltaFeeder {
    private var fedText = ""
    private var diverged = false

    /** Delta to feed for the current streaming [text], or null when nothing new. */
    fun nextDelta(text: String): String? {
        if (diverged) return null
        if (!text.startsWith(fedText)) {
            diverged = true
            return null
        }
        if (text.length == fedText.length) return null
        val delta = text.substring(fedText.length)
        fedText = text
        return delta
    }

    /**
     * The message finalized as [finalText]. Returns the remaining suffix to feed
     * before `done`, or null when the final text cannot be reconciled with what
     * was already spoken (feed nothing rather than repeat).
     */
    fun reconcileFinal(finalText: String): String? {
        val suffix = if (finalText.startsWith(fedText)) {
            finalText.substring(fedText.length)
        } else {
            null
        }
        fedText = finalText
        diverged = false
        return suffix?.takeIf { it.isNotEmpty() }
    }
}
