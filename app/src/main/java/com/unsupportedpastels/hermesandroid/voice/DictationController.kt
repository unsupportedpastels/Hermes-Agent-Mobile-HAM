package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a dictation attempt ended without producing a transcript. */
enum class DictationFailure {
    PermissionDenied,
    Unavailable,
    NoSpeech,
    RecordingFailed,
    TranscriptionFailed,
}

/**
 * Phase of a single dictation attempt. The controller only tracks phase — it
 * never holds or sends the transcript. Recognised text flows straight from the
 * engine/transport into the composer draft, so dictation can *never* auto-send.
 */
sealed interface DictationState {
    data object Idle : DictationState

    data class Recording(val elapsedMillis: Long, val level: Float) : DictationState

    data object Transcribing : DictationState

    data class Failed(val reason: DictationFailure) : DictationState
}

/**
 * Pure state machine for hold-to-dictate / tap-to-dictate. Bounded by
 * [maxRecordingMillis] (server `voice.max_recording_seconds`); the engine feeds
 * elapsed/level samples and a silence signal, and the controller reports when
 * the cap is hit so the caller stops the mic. No Android dependencies — the
 * `AudioRecord`/`SpeechRecognizer` engine lives behind a separate seam.
 */
class DictationController(
    val maxRecordingMillis: Long =
        VoiceServerConfig.DEFAULT.maxRecordingSeconds.toLong() * 1_000L,
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    val isActive: Boolean
        get() = _state.value is DictationState.Recording || _state.value is DictationState.Transcribing

    /** Begin recording. Allowed only from Idle or a dismissed Failed state. */
    fun beginRecording(): Boolean {
        return when (_state.value) {
            is DictationState.Idle, is DictationState.Failed -> {
                _state.value = DictationState.Recording(elapsedMillis = 0L, level = 0f)
                true
            }
            else -> false
        }
    }

    fun onAudioLevel(level: Float) {
        val recording = _state.value as? DictationState.Recording ?: return
        _state.value = recording.copy(level = level.coerceIn(0f, 1f))
    }

    /**
     * Report elapsed recording time. Returns true when the recording cap is
     * reached so the caller stops capture and moves to transcription.
     */
    fun onElapsed(millis: Long): Boolean {
        val recording = _state.value as? DictationState.Recording ?: return false
        _state.value = recording.copy(elapsedMillis = millis.coerceAtLeast(0L))
        return millis >= maxRecordingMillis
    }

    /** Recording finished (user released, silence auto-stop, or cap hit). */
    fun finishRecording(): Boolean {
        if (_state.value !is DictationState.Recording) return false
        _state.value = DictationState.Transcribing
        return true
    }

    /** Transcription completed — return to Idle regardless of whether text was empty. */
    fun onTranscriptionComplete() {
        if (_state.value is DictationState.Transcribing) {
            _state.value = DictationState.Idle
        }
    }

    /** Abort the current attempt from any phase (slide-to-cancel, hard stop). */
    fun cancel() {
        _state.value = DictationState.Idle
    }

    fun fail(reason: DictationFailure) {
        _state.value = DictationState.Failed(reason)
    }

    fun dismissError() {
        if (_state.value is DictationState.Failed) {
            _state.value = DictationState.Idle
        }
    }
}
