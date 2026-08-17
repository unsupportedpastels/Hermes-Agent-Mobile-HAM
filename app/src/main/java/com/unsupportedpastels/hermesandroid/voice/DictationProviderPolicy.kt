package com.unsupportedpastels.hermesandroid.voice

/** Which engine produces the transcript. */
enum class DictationProvider {
    /** Upload audio to `POST /api/audio/transcribe` — the server's audited STT chain. */
    Server,

    /** Android `SpeechRecognizer` — instant partials, works offline / when server STT is unconfigured. */
    OnDevice,
}

/** User's provider choice; `Automatic` prefers the server for provider-model fidelity. */
enum class DictationProviderPreference {
    Automatic,
    Server,
    OnDevice,
}

/**
 * Chooses the dictation engine from the user's preference and what's actually
 * available — mirroring Hermex's `orderedProviders`. Returns a fallback-ordered
 * list (first = preferred); an empty list means dictation is unavailable and the
 * mic affordance hides.
 *
 * `Automatic` keeps Server first: it uses the same provider chain as the rest of
 * the agent, so a dictated turn transcribes identically to a desktop one. On-device
 * is the offline/unconfigured fallback and the accessibility-friendly live-partial
 * option users can opt into.
 */
object DictationProviderPolicy {
    fun orderedProviders(
        preference: DictationProviderPreference,
        serverAvailable: Boolean,
        onDeviceSupported: Boolean,
    ): List<DictationProvider> {
        val server = if (serverAvailable) listOf(DictationProvider.Server) else emptyList()
        val onDevice = if (onDeviceSupported) listOf(DictationProvider.OnDevice) else emptyList()
        return when (preference) {
            DictationProviderPreference.OnDevice -> onDevice + server
            DictationProviderPreference.Server -> server + onDevice
            DictationProviderPreference.Automatic -> server + onDevice
        }
    }

    fun preferred(
        preference: DictationProviderPreference,
        serverAvailable: Boolean,
        onDeviceSupported: Boolean,
    ): DictationProvider? =
        orderedProviders(preference, serverAvailable, onDeviceSupported).firstOrNull()
}
