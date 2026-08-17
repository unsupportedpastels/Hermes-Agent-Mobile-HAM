package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the composer needs to read messages aloud. Null when the connected server
 * has no audio routes, so speaker buttons do not render (fail-closed).
 */
data class MessageReadAloud(
    val synthesize: suspend (text: String) -> Result<SpeechAudio>,
)

/**
 * Shared read-aloud coordination for one session: a single [SpeechPlaybackController]
 * and [SpeechEngine] so only one message speaks at a time. Text is sanitized for
 * speech before synthesis; tool/system/secret content should never reach here.
 */
class ReadAloudSession(
    val controller: SpeechPlaybackController,
    private val scope: CoroutineScope,
    private val engine: SpeechEngine,
    private val synthesize: suspend (String) -> Result<SpeechAudio>,
) {
    /** Start speaking [text] for [messageKey], or stop if it is already the active one. */
    fun toggle(messageKey: String, text: String) {
        if (controller.isActiveFor(messageKey)) {
            stop()
            return
        }
        val speechText = sanitizeTextForSpeech(text)
        if (speechText.isBlank()) return
        engine.stop()
        controller.beginPreparing(messageKey)
        scope.launch {
            synthesize(speechText)
                .onSuccess { audio ->
                    if (!controller.isActiveFor(messageKey)) return@onSuccess
                    engine.play(
                        audio = audio,
                        onStarted = { controller.markPlaying(messageKey) },
                        onFinished = { controller.complete() },
                        onError = { controller.fail(messageKey, SpeechPlaybackFailure.Playback) },
                    )
                }
                .onFailure { controller.fail(messageKey, SpeechPlaybackFailure.Synthesis) }
        }
    }

    fun stop() {
        engine.stop()
        controller.stop()
    }
}

/**
 * Remembers a [ReadAloudSession] bound to [sessionId]. Playback stops when the
 * session changes, when the composable leaves composition, and when the activity
 * is stopped.
 */
@Composable
fun rememberReadAloudSession(
    readAloud: MessageReadAloud?,
    sessionId: String,
    engineFactory: (Context) -> SpeechEngine = { MediaPlayerSpeechEngine(it) },
): ReadAloudSession? {
    if (readAloud == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { engineFactory(context) }
    val controller = remember { SpeechPlaybackController() }
    val session = remember(readAloud) {
        ReadAloudSession(controller, scope, engine, readAloud.synthesize)
    }

    // Stop when switching sessions.
    DisposableEffect(sessionId) {
        onDispose { session.stop() }
    }
    // Release when leaving composition.
    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }
    // Stop when the activity is stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return session
}

/**
 * Per-message speaker toggle. Shows play when idle, a spinner while synthesizing,
 * and stop while this message plays; disabled while a *different* message is
 * speaking so only one plays at a time.
 */
@Composable
fun MessageSpeakerButton(
    session: ReadAloudSession,
    messageKey: String,
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by session.controller.state.collectAsState()
    val isPreparing = (state as? SpeechPlaybackState.Preparing)?.messageKey == messageKey
    val isPlaying = (state as? SpeechPlaybackState.Playing)?.messageKey == messageKey
    val activeKey = session.controller.activeKey
    val otherActive = activeKey != null && activeKey != messageKey

    val description = if (isPlaying || isPreparing) "Stop reading aloud" else "Read message aloud"
    val stateText = when {
        isPreparing -> "Preparing"
        isPlaying -> "Playing"
        else -> "Idle"
    }

    IconButton(
        onClick = { session.toggle(messageKey, text) },
        enabled = enabled && !otherActive,
        modifier = modifier.semantics {
            contentDescription = description
            stateDescription = stateText
        },
    ) {
        when {
            isPreparing ->
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            isPlaying ->
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            else ->
                Icon(
                    imageVector = Icons.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
