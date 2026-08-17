package com.unsupportedpastels.hermesandroid.voice

/**
 * Result of `POST /api/audio/transcribe`. An empty [transcript] is a normal
 * outcome — the server returns `ok:true` with `transcript:""` for silence — so
 * callers treat it as "no speech" and re-listen rather than as an error.
 */
data class TranscriptionResult(
    val transcript: String,
    val provider: String?,
) {
    val isEmpty: Boolean get() = transcript.isBlank()
}
