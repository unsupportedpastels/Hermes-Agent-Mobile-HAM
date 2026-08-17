package com.unsupportedpastels.hermesandroid.voice

/**
 * Which official `/api/audio/…` contracts the connected `hermes serve`
 * advertises. Everything downstream is fail-closed: an absent contract means the
 * corresponding affordance is hidden, never surfaced as an error.
 *
 * Contracts verified against the installed server (hermes_cli/web_server.py):
 * `POST /api/audio/transcribe`, `POST /api/audio/speak`,
 * `WS /api/audio/speak-stream`, `GET /api/audio/elevenlabs/voices`.
 */
data class VoiceCapabilities(
    /** The `/api/audio/…` route family exists on this server. */
    val audioRoutesPresent: Boolean,
    /** An ElevenLabs API key is configured, so the voice picker can populate. */
    val elevenLabsVoicesAvailable: Boolean,
) {
    val canDictateViaServer: Boolean get() = audioRoutesPresent
    val canReadAloud: Boolean get() = audioRoutesPresent
    val canStreamSpeech: Boolean get() = audioRoutesPresent
    val canPickElevenLabsVoice: Boolean get() = audioRoutesPresent && elevenLabsVoicesAvailable

    companion object {
        val NONE = VoiceCapabilities(audioRoutesPresent = false, elevenLabsVoicesAvailable = false)
    }
}

/**
 * Derives [VoiceCapabilities] from a lightweight, side-effect-free probe of the
 * `GET /api/audio/elevenlabs/voices` route.
 *
 * That route is the family's cheapest existence test: released servers answer
 * `200` (even with `available:false` when no key is set), while servers without
 * the audio routes answer `404`/`405` — the same "unsupported route" signal HAM
 * already uses for cron and bulk-delete endpoints. Any non-2xx (including auth
 * or 5xx) fails closed to [VoiceCapabilities.NONE] and is re-probed later.
 */
object VoiceCapabilityPolicy {
    fun fromVoicesProbe(statusCode: Int, elevenLabsAvailable: Boolean): VoiceCapabilities =
        if (statusCode in 200..299) {
            VoiceCapabilities(
                audioRoutesPresent = true,
                elevenLabsVoicesAvailable = elevenLabsAvailable,
            )
        } else {
            VoiceCapabilities.NONE
        }
}
