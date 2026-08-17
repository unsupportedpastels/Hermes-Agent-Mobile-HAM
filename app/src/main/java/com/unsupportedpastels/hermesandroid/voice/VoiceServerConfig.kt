package com.unsupportedpastels.hermesandroid.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a completed voice utterance leaves the composer.
 *
 * Mirrors the server `voice.submit_mode` setting: `direct` submits immediately,
 * `draft` leaves an editable transcript. HAM is beholden to the gateway, so this
 * server-authoritative value — not a client-invented toggle — decides whether a
 * dictated turn is staged or sent.
 */
enum class VoiceSubmitMode {
    Direct,
    Draft,
    ;

    companion object {
        fun parse(raw: String?): VoiceSubmitMode =
            when (raw?.trim()?.lowercase()) {
                "draft" -> Draft
                "direct" -> Direct
                else -> Direct
            }
    }
}

/**
 * Client-side mirror of the released `hermes serve` `voice` config section
 * (config_defaults.py "voice"). Voice behaviour — recording caps, silence
 * auto-stop, barge-in trip points, stop phrases, auto-TTS — is driven by these
 * server values so HAM never forks the audited voice contract.
 *
 * Parsing is shape-safe: the server tolerates `voice` being a bool/str instead
 * of a dict (voice.py), and any individual field may be missing or the wrong
 * type. A malformed or absent value falls back to the corresponding [DEFAULT]
 * field rather than throwing.
 */
data class VoiceServerConfig(
    val submitMode: VoiceSubmitMode,
    val maxRecordingSeconds: Int,
    val autoTts: Boolean,
    val silenceThreshold: Int,
    val silenceDurationSeconds: Double,
    val bargeInEnabled: Boolean,
    val bargeInGraceSeconds: Double,
    val bargeInThresholdMultiplier: Double,
    val stopPhrases: List<String>,
    /** Root `tts.provider` — which server TTS backend is configured. */
    val ttsProvider: String? = null,
    /** Root `tts.elevenlabs.voice_id` — current ElevenLabs voice selection. */
    val elevenLabsVoiceId: String? = null,
    /** Root `stt.provider` — which server STT backend is configured. */
    val sttProvider: String? = null,
    /** Root `stt.enabled`. */
    val sttEnabled: Boolean = true,
) {
    companion object {
        /** Matches the released server defaults verbatim (config_defaults.py). */
        val DEFAULT = VoiceServerConfig(
            submitMode = VoiceSubmitMode.Direct,
            maxRecordingSeconds = 120,
            autoTts = false,
            silenceThreshold = 200,
            silenceDurationSeconds = 3.0,
            bargeInEnabled = true,
            bargeInGraceSeconds = 0.5,
            bargeInThresholdMultiplier = 3.0,
            stopPhrases = listOf("stop"),
        )

        private const val MIN_RECORDING_SECONDS = 1
        private const val MAX_RECORDING_SECONDS = 600
        private const val MAX_SILENCE_THRESHOLD = 32_767
        private const val MAX_STOP_PHRASES = 16
        private const val MAX_STOP_PHRASE_CHARS = 64

        /** Extract the `voice`/`tts`/`stt` sections from a full `/api/config` document. */
        fun fromConfigRoot(root: JsonObject?): VoiceServerConfig {
            val base = fromVoiceSection(root?.get("voice") as? JsonObject)
            if (root == null) return base
            val tts = root["tts"] as? JsonObject
            val stt = root["stt"] as? JsonObject
            val elevenLabs = tts?.get("elevenlabs") as? JsonObject
            return base.copy(
                ttsProvider = tts?.string("provider")?.trim()?.lowercase()?.take(64),
                elevenLabsVoiceId = elevenLabs?.string("voice_id")?.trim()?.take(128),
                sttProvider = stt?.string("provider")?.trim()?.lowercase()?.take(64),
                sttEnabled = stt?.bool("enabled", true) ?: true,
            )
        }

        fun fromVoiceSection(voice: JsonObject?): VoiceServerConfig {
            if (voice == null) return DEFAULT
            return VoiceServerConfig(
                submitMode = VoiceSubmitMode.parse(
                    voice.string("submit_mode") ?: DEFAULT.submitMode.name,
                ),
                maxRecordingSeconds = voice.int("max_recording_seconds", DEFAULT.maxRecordingSeconds)
                    .coerceIn(MIN_RECORDING_SECONDS, MAX_RECORDING_SECONDS),
                autoTts = voice.bool("auto_tts", DEFAULT.autoTts),
                silenceThreshold = voice.int("silence_threshold", DEFAULT.silenceThreshold)
                    .coerceIn(0, MAX_SILENCE_THRESHOLD),
                silenceDurationSeconds = voice.double("silence_duration", DEFAULT.silenceDurationSeconds)
                    .coerceIn(0.0, 60.0),
                bargeInEnabled = voice.bool("barge_in", DEFAULT.bargeInEnabled),
                bargeInGraceSeconds = voice.double("barge_in_grace_seconds", DEFAULT.bargeInGraceSeconds)
                    .coerceIn(0.0, 10.0),
                bargeInThresholdMultiplier = voice.double(
                    "barge_in_threshold_multiplier",
                    DEFAULT.bargeInThresholdMultiplier,
                ).coerceIn(1.0, 20.0),
                stopPhrases = parseStopPhrases(voice["stop_phrases"] as? JsonArray),
            )
        }

        private fun parseStopPhrases(array: JsonArray?): List<String> {
            if (array == null) return DEFAULT.stopPhrases
            return array
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                .filter { it.isNotEmpty() && it.length <= MAX_STOP_PHRASE_CHARS }
                .distinct()
                .take(MAX_STOP_PHRASES)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: fallback

        private fun JsonObject.int(key: String, fallback: Int): Int =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: fallback

        private fun JsonObject.double(key: String, fallback: Double): Double =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: fallback
    }
}
