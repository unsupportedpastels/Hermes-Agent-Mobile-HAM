package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a read-aloud attempt ended without playing to completion. */
enum class SpeechPlaybackFailure {
    Unavailable,
    Synthesis,
    Playback,
}

/**
 * Read-aloud phase for a single target. [messageKey] identifies what is being
 * spoken (an assistant message id, or an artifact source) so the UI can show
 * the speaker state on exactly one item and disable the others.
 */
sealed interface SpeechPlaybackState {
    data object Idle : SpeechPlaybackState

    data class Preparing(val messageKey: String) : SpeechPlaybackState

    data class Playing(val messageKey: String) : SpeechPlaybackState

    data class Failed(val messageKey: String, val reason: SpeechPlaybackFailure) : SpeechPlaybackState
}

/**
 * Pure coordinator for read-aloud. Only one target plays at a time, so starting
 * a new one supersedes any in-flight playback. The Android [SpeechEngine] and
 * audio-focus handling live in the host; this state machine is tested directly.
 */
class SpeechPlaybackController {
    private val _state = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)
    val state: StateFlow<SpeechPlaybackState> = _state.asStateFlow()

    /** The target currently preparing or playing, or null when idle/failed. */
    val activeKey: String?
        get() = when (val current = _state.value) {
            is SpeechPlaybackState.Preparing -> current.messageKey
            is SpeechPlaybackState.Playing -> current.messageKey
            else -> null
        }

    fun isActiveFor(messageKey: String): Boolean = activeKey == messageKey

    fun isPlaying(messageKey: String): Boolean =
        (_state.value as? SpeechPlaybackState.Playing)?.messageKey == messageKey

    /** Begin synthesizing/loading for [messageKey], superseding any current target. */
    fun beginPreparing(messageKey: String) {
        _state.value = SpeechPlaybackState.Preparing(messageKey)
    }

    /** Audio started for [messageKey]; ignored if a newer target has taken over. */
    fun markPlaying(messageKey: String): Boolean {
        if (activeKey != messageKey) return false
        _state.value = SpeechPlaybackState.Playing(messageKey)
        return true
    }

    /** Playback finished naturally. */
    fun complete() {
        if (_state.value is SpeechPlaybackState.Playing || _state.value is SpeechPlaybackState.Preparing) {
            _state.value = SpeechPlaybackState.Idle
        }
    }

    /** Hard stop (session switch, activity stop, user tap, artifact audio takeover). */
    fun stop() {
        _state.value = SpeechPlaybackState.Idle
    }

    fun fail(messageKey: String, reason: SpeechPlaybackFailure) {
        // A stale failure (a superseded target) must not clobber the active one.
        if (activeKey == null || activeKey == messageKey) {
            _state.value = SpeechPlaybackState.Failed(messageKey, reason)
        }
    }

    fun dismissError() {
        if (_state.value is SpeechPlaybackState.Failed) {
            _state.value = SpeechPlaybackState.Idle
        }
    }
}
