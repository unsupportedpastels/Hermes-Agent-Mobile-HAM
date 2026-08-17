package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import kotlinx.coroutines.flow.combine

/** A finalized reply eligible for auto-speak. */
data class AutoSpeakTarget(val index: Int, val text: String)

/**
 * Decides which finalized assistant reply auto-speak reads next, mirroring
 * Desktop's use-auto-speak-replies: history is never read, only replies that
 * finalize while the policy is watching, each at most once, backlog collapsed
 * to the newest eligible reply.
 *
 * "History" is defined defensively, because transcript loading and session
 * resume can replace the message list at any moment: the first view observed,
 * any view whose list SHRANK, and any view that grew by more than a single
 * turn plausibly could (a bulk transcript load) all re-mark everything present
 * as already spoken. Only a reply that appears through ordinary incremental
 * growth is ever read aloud.
 */
class AutoSpeakPolicy {
    private var lastSeenCount = -1
    private var lastSpokenIndex = Int.MAX_VALUE

    /** Everything currently visible becomes history. */
    fun markAllSpoken(view: VoiceReplyView) {
        lastSpokenIndex = view.finalAssistantIndex
        lastSeenCount = view.messageCount
    }

    /** Newest eligible finalized reply, or null when there is nothing new. */
    fun nextToSpeak(view: VoiceReplyView): AutoSpeakTarget? {
        val firstObservation = lastSeenCount < 0
        val listReplaced = view.messageCount < lastSeenCount
        val bulkGrowth = !firstObservation &&
            view.messageCount - lastSeenCount > MAX_INCREMENTAL_GROWTH
        if (firstObservation || listReplaced || bulkGrowth) {
            markAllSpoken(view)
            return null
        }
        lastSeenCount = view.messageCount
        val index = view.finalAssistantIndex
        val text = view.finalAssistantText ?: return null
        if (index <= lastSpokenIndex) return null
        return AutoSpeakTarget(index, text)
    }

    fun markSpoken(index: Int) {
        if (lastSpokenIndex == Int.MAX_VALUE || index > lastSpokenIndex) {
            lastSpokenIndex = index
        }
    }

    private companion object {
        /** One turn adds a handful of rows incrementally; a whole transcript
         * arriving at once adds many — treat that as a load, not new replies. */
        const val MAX_INCREMENTAL_GROWTH = 4
    }
}

/**
 * Speaks new finalized replies in the currently open session through the shared
 * [ReadAloudSession] (so auto-speak, manual speaker buttons, and artifact audio
 * stay mutually exclusive). Holds while any clip owns playback and re-checks
 * when it goes idle; [suppressed] (an active voice conversation) pauses it —
 * conversation playback owns speech.
 */
@Composable
fun AutoSpeakEffect(
    readAloudSession: ReadAloudSession?,
    chat: ChatSessionSnapshot,
    enabled: Boolean,
    suppressed: Boolean,
    sessionId: String,
) {
    if (readAloudSession == null || !enabled) return
    val chatState = rememberUpdatedState(chat)
    // A new policy per session entry: its first observation (and any transcript
    // reload it sees) marks everything present as history, never read aloud.
    val policy = remember(sessionId) { AutoSpeakPolicy() }
    val suppressedState = rememberUpdatedState(suppressed)
    LaunchedEffect(policy, readAloudSession) {
        combine(
            snapshotFlow { chatState.value.isLoading to chatState.value.toVoiceReplyView() },
            readAloudSession.controller.state,
        ) { view, playback -> view to playback }
            .collect { (loadingAndView, playback) ->
                val (loading, view) = loadingAndView
                if (loading) {
                    // History still arriving — everything visible stays history.
                    policy.markAllSpoken(view)
                    return@collect
                }
                if (suppressedState.value) return@collect
                if (playback != SpeechPlaybackState.Idle) return@collect
                val target = policy.nextToSpeak(view) ?: return@collect
                policy.markSpoken(target.index)
                readAloudSession.toggle("auto:$sessionId:${target.index}", target.text)
            }
    }
}
