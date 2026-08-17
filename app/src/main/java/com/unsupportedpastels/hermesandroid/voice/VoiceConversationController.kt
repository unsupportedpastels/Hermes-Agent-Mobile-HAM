package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phase of the hands-free voice conversation loop. */
sealed interface VoiceConversationState {
    data object Idle : VoiceConversationState

    /** Microphone armed, waiting for the user to finish an utterance. */
    data class Listening(val level: Float = 0f) : VoiceConversationState

    data object Transcribing : VoiceConversationState

    /** Prompt submitted; the agent is generating. */
    data object Thinking : VoiceConversationState

    /** The reply is being spoken aloud. */
    data object Speaking : VoiceConversationState
}

/** What to do with a finished transcript. */
enum class TranscriptDisposition {
    /** Substantive text — submit it as a prompt. */
    Submit,

    /** Whole-utterance stop phrase — end the conversation without submitting. */
    EndConversation,

    /** Silence/empty — re-arm the microphone without submitting. */
    Rearm,
}

/** Bounded, category-only error surfaced on the voice bar. */
enum class VoiceConversationNotice {
    TranscriptionFailed,
    SpeechFailed,
}

/**
 * Pure state machine for the explicit voice-conversation loop:
 * listen → transcribe → think → speak → re-arm. Entering the loop is the
 * user's deliberate consent, so (unlike dictation) a substantive transcript
 * *is* submitted automatically. Owns no Android, transport, or timer state —
 * the host drives it and every callback is guarded by [generation] so stale
 * engine events from a previous loop are ignored.
 */
class VoiceConversationController(
    private val stopPhrases: () -> List<String>,
) {
    private val _state = MutableStateFlow<VoiceConversationState>(VoiceConversationState.Idle)
    val state: StateFlow<VoiceConversationState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _notice = MutableStateFlow<VoiceConversationNotice?>(null)
    val notice: StateFlow<VoiceConversationNotice?> = _notice.asStateFlow()

    /** Increments on every start/end; hosts tag async callbacks with it. */
    var generation: Long = 0L
        private set

    val isActive: Boolean get() = _state.value != VoiceConversationState.Idle

    /** Begin the loop. Only valid from Idle. */
    fun start(): Boolean {
        if (isActive) return false
        generation++
        _muted.value = false
        _notice.value = null
        _state.value = VoiceConversationState.Listening()
        return true
    }

    /** Hard end from any phase (user End, stop phrase, lifecycle, fatal error). */
    fun end() {
        generation++
        _state.value = VoiceConversationState.Idle
        _muted.value = false
    }

    fun onListeningLevel(level: Float) {
        if (_state.value is VoiceConversationState.Listening) {
            _state.value = VoiceConversationState.Listening(level.coerceIn(0f, 1f))
        }
    }

    /** An utterance was captured; move to transcription. */
    fun onUtteranceCaptured(): Boolean {
        if (_state.value !is VoiceConversationState.Listening) return false
        _state.value = VoiceConversationState.Transcribing
        return true
    }

    /** Nothing captured (recorder failure / pure silence) — re-arm. */
    fun onCaptureEmpty() {
        if (_state.value is VoiceConversationState.Transcribing ||
            _state.value is VoiceConversationState.Listening
        ) {
            _state.value = VoiceConversationState.Listening()
        }
    }

    /**
     * Transcription finished. Decides the transcript's fate and transitions:
     * stop phrase ends the loop, blank re-arms, substantive text moves to
     * Thinking (the host submits it).
     */
    fun onTranscript(transcript: String): TranscriptDisposition {
        if (_state.value !is VoiceConversationState.Transcribing) return TranscriptDisposition.Rearm
        _notice.value = null
        return when {
            transcript.isBlank() -> {
                _state.value = VoiceConversationState.Listening()
                TranscriptDisposition.Rearm
            }
            isVoiceStopPhrase(transcript, stopPhrases()) -> {
                end()
                TranscriptDisposition.EndConversation
            }
            else -> {
                _state.value = VoiceConversationState.Thinking
                TranscriptDisposition.Submit
            }
        }
    }

    /** Transcription failed — visible bounded notice, then re-arm. */
    fun onTranscriptionFailed() {
        if (_state.value !is VoiceConversationState.Transcribing) return
        _notice.value = VoiceConversationNotice.TranscriptionFailed
        _state.value = VoiceConversationState.Listening()
    }

    /** Reply audio started. */
    fun onSpeechStarted(): Boolean {
        if (_state.value !is VoiceConversationState.Thinking) return false
        _state.value = VoiceConversationState.Speaking
        return true
    }

    /** The turn ended with nothing speakable (tool-only, error, muted) — re-arm. */
    fun onTurnCompleteWithoutSpeech() {
        if (_state.value is VoiceConversationState.Thinking ||
            _state.value is VoiceConversationState.Speaking
        ) {
            _state.value = VoiceConversationState.Listening()
        }
    }

    /** Reply playback finished naturally — re-arm the microphone. */
    fun onSpeechFinished() {
        if (_state.value is VoiceConversationState.Speaking) {
            _state.value = VoiceConversationState.Listening()
        }
    }

    /** Speech synthesis/playback failed nonfatally — notice, then re-arm. */
    fun onSpeechFailed() {
        if (_state.value is VoiceConversationState.Thinking ||
            _state.value is VoiceConversationState.Speaking
        ) {
            _notice.value = VoiceConversationNotice.SpeechFailed
            _state.value = VoiceConversationState.Listening()
        }
    }

    /**
     * The user spoke over thinking/playback (barge-in). Playback stops and the
     * mic is already capturing the interruption; state returns to Listening.
     */
    fun onBargeIn(): Boolean {
        if (_state.value !is VoiceConversationState.Thinking &&
            _state.value !is VoiceConversationState.Speaking
        ) {
            return false
        }
        _state.value = VoiceConversationState.Listening()
        return true
    }

    /** Mute pauses capture without ending the loop; unmute re-arms listening. */
    fun setMuted(muted: Boolean) {
        if (!isActive) return
        _muted.value = muted
    }
}
