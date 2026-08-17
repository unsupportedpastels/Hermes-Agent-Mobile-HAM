package com.unsupportedpastels.hermesandroid.voice

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * Audio transcription and synthesis run far longer than ordinary chat REST
 * calls — the desktop client uses scaled 180–600s windows because a provider may
 * spend minutes on a long utterance. These per-request timeouts are applied
 * ONLY to `/api/audio/…` calls via [audioRequestTimeout]; the shared Hermes
 * client installs `HttpTimeout` with no defaults, so ordinary requests keep
 * their engine-level behaviour untouched.
 */
object VoiceAudioTimeouts {
    const val TRANSCRIBE_REQUEST_MILLIS = 180_000L
    const val SPEAK_REQUEST_MILLIS = 180_000L
    const val CONNECT_MILLIS = 30_000L
    const val SOCKET_MILLIS = 600_000L
}

/** Apply the extended audio window to a single `/api/audio/…` request. */
fun HttpRequestBuilder.audioRequestTimeout(requestMillis: Long) {
    timeout {
        connectTimeoutMillis = VoiceAudioTimeouts.CONNECT_MILLIS
        requestTimeoutMillis = requestMillis
        socketTimeoutMillis = VoiceAudioTimeouts.SOCKET_MILLIS
    }
}
