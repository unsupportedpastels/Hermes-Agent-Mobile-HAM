package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the composer needs to offer dictation. Null when the connected
 * server has no audio routes, so the mic simply does not render (fail-closed).
 * [serverConfig] carries the authoritative recording cap and silence tuning.
 */
data class ComposerDictation(
    val serverConfig: VoiceServerConfig,
    val transcribe: suspend (dataUrl: String, mimeType: String?) -> Result<TranscriptionResult>,
)

private const val POLL_INTERVAL_MILLIS = 100L

/** Dictation is user-driven, so give them longer before declaring no speech. */
private const val DICTATION_NO_SPEECH_TIMEOUT_MILLIS = 15_000L

private fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Tap-to-toggle dictation mic. Tap starts recording; tapping again (or silence /
 * the server recording cap) stops and transcribes; long-press cancels. The
 * transcript is appended to the composer draft via [onAppendTranscript] — it is
 * never sent automatically. Recording/level/elapsed polling, silence auto-stop,
 * and the RECORD_AUDIO grant all live here; the [DictationController] state
 * machine and [DictationRecorder] engine are tested independently.
 */
@Composable
fun DictationMicButton(
    dictation: ComposerDictation,
    enabled: Boolean,
    onAppendTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: (DictationFailure) -> Unit = {},
    recorderFactory: (Context) -> DictationRecorder = { MediaRecorderDictationRecorder(it) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { DictationController(dictation.serverConfig.maxRecordingSeconds.toLong() * 1_000L) }
    val recorder = remember { recorderFactory(context) }
    val state by controller.state.collectAsState()

    val currentAppend by rememberUpdatedState(onAppendTranscript)
    val currentTranscribe by rememberUpdatedState(dictation.transcribe)
    val currentOnError by rememberUpdatedState(onError)
    val silenceThreshold = dictation.serverConfig.silenceThreshold
    val silenceMillis = (dictation.serverConfig.silenceDurationSeconds * 1_000).toLong()

    suspend fun finishAndTranscribe() {
        if (!controller.finishRecording()) return
        val recording = withContext(Dispatchers.IO) { recorder.stopAndEncode() }
        if (recording == null) {
            controller.fail(DictationFailure.NoSpeech)
            currentOnError(DictationFailure.NoSpeech)
            return
        }
        currentTranscribe(recording.dataUrl, recording.mimeType)
            .onSuccess { result ->
                if (result.isEmpty) {
                    controller.fail(DictationFailure.NoSpeech)
                    currentOnError(DictationFailure.NoSpeech)
                } else {
                    currentAppend(result.transcript)
                    controller.onTranscriptionComplete()
                }
            }
            .onFailure {
                controller.fail(DictationFailure.TranscriptionFailed)
                currentOnError(DictationFailure.TranscriptionFailed)
            }
    }

    fun startRecording() {
        if (!controller.beginRecording()) return
        if (!recorder.start()) {
            controller.fail(DictationFailure.RecordingFailed)
            currentOnError(DictationFailure.RecordingFailed)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            controller.fail(DictationFailure.PermissionDenied)
            currentOnError(DictationFailure.PermissionDenied)
        }
    }

    // Drive elapsed/level/silence while recording; stop on cap or speech-then-
    // silence (ambient-calibrated — see SilenceAutoStop). A hands-free tap that
    // never hears speech resolves to NoSpeech instead of uploading room tone.
    val isRecording = state is DictationState.Recording
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        val autoStop = SilenceAutoStop(
            configuredThreshold = silenceThreshold,
            silenceMillis = silenceMillis,
            maxMillis = controller.maxRecordingMillis,
            noSpeechTimeoutMillis = DICTATION_NO_SPEECH_TIMEOUT_MILLIS,
        )
        while (isActive) {
            delay(POLL_INTERVAL_MILLIS)
            val amplitude = recorder.sampleAmplitude()
            controller.onAudioLevel(amplitude / 32_767f)
            val elapsed = System.currentTimeMillis() - startedAt
            controller.onElapsed(elapsed)
            when (autoStop.feed(elapsed, amplitude)) {
                SilenceAutoStopDecision.Continue -> Unit
                SilenceAutoStopDecision.Stop -> {
                    finishAndTranscribe()
                    break
                }
                SilenceAutoStopDecision.StopEmpty -> {
                    recorder.cancel()
                    controller.fail(DictationFailure.NoSpeech)
                    currentOnError(DictationFailure.NoSpeech)
                    break
                }
            }
        }
    }

    // Privacy default: leaving the foreground (including device lock) discards
    // any in-progress recording; the visible draft text is untouched.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                recorder.cancel()
                controller.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            recorder.cancel()
            controller.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val level = (state as? DictationState.Recording)?.level ?: 0f
    val pulse by animateFloatAsState(targetValue = 1f + level * 0.25f, label = "dictationPulse")

    val description = when (state) {
        is DictationState.Recording -> "Stop dictation and insert"
        is DictationState.Transcribing -> "Transcribing"
        else -> "Dictate message"
    }
    val stateText = when (val current = state) {
        is DictationState.Recording -> "Recording"
        is DictationState.Transcribing -> "Transcribing"
        is DictationState.Failed -> current.reason.name
        DictationState.Idle -> "Idle"
    }

    fun beginCapture() {
        controller.dismissError()
        if (hasRecordAudioPermission(context)) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Quick tap toggles a hands-free recording (also the TalkBack activation path).
    val tap: () -> Unit = tap@{
        if (!enabled) return@tap
        when (state) {
            is DictationState.Recording -> scope.launch { finishAndTranscribe() }
            is DictationState.Transcribing -> Unit
            else -> beginCapture()
        }
    }
    // Press-and-hold to talk: start on the long-press threshold, stop on release.
    val holdStart: () -> Unit = holdStart@{
        if (!enabled) return@holdStart
        if (state is DictationState.Idle || state is DictationState.Failed) beginCapture()
    }
    val holdRelease: () -> Unit = {
        if (state is DictationState.Recording) scope.launch { finishAndTranscribe() }
    }
    val holdCancel: () -> Unit = {
        recorder.cancel()
        controller.cancel()
    }

    val latestState by rememberUpdatedState(state)
    val latestEnabled by rememberUpdatedState(enabled)
    val currentTap by rememberUpdatedState(tap)
    val currentHoldStart by rememberUpdatedState(holdStart)
    val currentHoldRelease by rememberUpdatedState(holdRelease)
    val currentHoldCancel by rememberUpdatedState(holdCancel)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(44.dp)
            .indication(interactionSource, ripple(bounded = false, radius = 22.dp))
            .semantics {
                contentDescription = description
                stateDescription = stateText
                onClick { currentTap(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestEnabled || latestState is DictationState.Transcribing) {
                        return@awaitEachGesture
                    }
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    val releasedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (releasedEarly != null) {
                        // Released before the long-press threshold — treat as a tap.
                        interactionSource.tryEmit(PressInteraction.Release(press))
                        currentTap()
                    } else {
                        // Held past the threshold — record while held, stop on release.
                        currentHoldStart()
                        val up = waitForUpOrCancellation()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                        if (up != null) currentHoldRelease() else currentHoldCancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DictationState.Transcribing ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            is DictationState.Recording ->
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
                )
            else ->
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.alpha(if (enabled) 1f else 0.6f),
                )
        }
    }
}
