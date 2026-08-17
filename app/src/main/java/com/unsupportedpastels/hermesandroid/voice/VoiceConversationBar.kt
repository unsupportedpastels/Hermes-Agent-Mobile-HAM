package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Everything the hands-free voice conversation needs from the connection layer.
 * Null when the connected server lacks the audio routes, so the affordance is
 * hidden (fail-closed). Entering the conversation is the user's explicit
 * consent to auto-submit transcripts — unlike dictation, which only drafts.
 */
data class ComposerVoiceConversation(
    val serverConfig: VoiceServerConfig,
    val transcribe: suspend (dataUrl: String, mimeType: String?) -> Result<TranscriptionResult>,
    val openStream: suspend () -> SpeechStreamSocket?,
    val synthesize: suspend (text: String) -> Result<SpeechAudio>,
)

private const val LEVEL_POLL_MILLIS = 100L


/**
 * Record one utterance: level metering, silence auto-stop, and the server
 * recording cap — the conversation-loop twin of the dictation mic's polling.
 */
internal suspend fun recordUtterance(
    recorder: DictationRecorder,
    config: VoiceServerConfig,
    onLevel: (Float) -> Unit,
    log: (String) -> Unit = {},
): DictationRecording? {
    if (!recorder.start()) {
        log("recorder:start-failed")
        return null
    }
    val autoStop = SilenceAutoStop(
        configuredThreshold = config.silenceThreshold,
        silenceMillis = (config.silenceDurationSeconds * 1_000).toLong(),
        maxMillis = config.maxRecordingSeconds.toLong() * 1_000L,
    )
    val startedAt = System.currentTimeMillis()
    log(
        "recorder:cfg threshold=${config.silenceThreshold} " +
            "silence=${(config.silenceDurationSeconds * 1_000).toLong()}ms " +
            "max=${config.maxRecordingSeconds}s",
    )
    var peak = 0
    var samples = 0
    try {
        while (true) {
            delay(LEVEL_POLL_MILLIS)
            val amplitude = recorder.sampleAmplitude()
            if (amplitude > peak) peak = amplitude
            // One diagnostic line per 2s: amplitude scale + detector state only.
            if (++samples % 20 == 0) {
                log("recorder:amp=$amplitude peak=$peak ${autoStop.debugState()}")
            }
            onLevel((amplitude / 32_767f).coerceIn(0f, 1f))
            when (autoStop.feed(System.currentTimeMillis() - startedAt, amplitude)) {
                SilenceAutoStopDecision.Continue -> Unit
                SilenceAutoStopDecision.Stop -> {
                    log("recorder:stop ${autoStop.debugState()}")
                    return withContext(Dispatchers.IO) { recorder.stopAndEncode() }
                }
                SilenceAutoStopDecision.StopEmpty -> {
                    log("recorder:stop-empty ${autoStop.debugState()}")
                    // Nothing but room tone — never upload it.
                    recorder.cancel()
                    return null
                }
            }
        }
    } catch (cancelled: CancellationException) {
        log("recorder:cancelled")
        recorder.cancel()
        throw cancelled
    } catch (error: Exception) {
        log("recorder:error ${error.javaClass.simpleName}")
        recorder.cancel()
        return null
    }
}

/**
 * REST-clip playback that the barge trigger can interrupt: [stop] both stops
 * the engine and resolves the in-flight await (the engine fires no callback on
 * an external stop), so the loop never hangs on a barged clip.
 */
internal class InterruptiblePlayback(private val engine: SpeechEngine) {
    private var active: CompletableDeferred<Boolean>? = null

    /** Play and suspend until the clip finishes (true) or fails/was stopped (false). */
    suspend fun play(audio: SpeechAudio, onStarted: () -> Unit): Boolean {
        val done = CompletableDeferred<Boolean>()
        active = done
        engine.play(
            audio = audio,
            onStarted = onStarted,
            onFinished = { done.complete(true) },
            onError = { done.complete(false) },
        )
        return try {
            done.await()
        } catch (cancelled: CancellationException) {
            engine.stop()
            throw cancelled
        }
    }

    fun stop() {
        engine.stop()
        active?.complete(false)
    }
}

/**
 * Full-duplex barge-in monitor: meters the microphone while the agent
 * thinks/speaks, using rotating short recorder segments so the eventual upload
 * carries only a bounded pre-roll plus the interruption itself. On sustained
 * speech it fires [onTrigger] once, keeps recording until the utterance ends
 * (server silence tuning), and returns the encoded capture.
 */
internal suspend fun monitorBargeInWithRecorder(
    recorderFactory: () -> DictationRecorder,
    config: VoiceServerConfig,
    onTrigger: () -> Unit,
    /** True while TTS is audible — raises the trip point to the playback clamp. */
    playbackActive: () -> Boolean = { false },
): DictationRecording? {
    val graceMillis = (config.bargeInGraceSeconds * 1_000).toLong()
    val silenceMillis = minOf(
        (config.silenceDurationSeconds * 1_000).toLong(),
        BARGE_CAPTURE_SILENCE_MILLIS,
    ).coerceAtLeast(400L)
    val monitorStart = System.currentTimeMillis()
    // One detector for the whole monitor: its grace-window ambient calibration
    // must survive recorder segment rotation.
    val detector = BargeInDetector(config.bargeTriggerAmplitude(), graceMillis)
    while (true) {
        val recorder = recorderFactory()
        if (!recorder.start()) {
            // Recorder contention (route change, transient failure) — back off.
            delay(1_000)
            continue
        }
        var triggered = false
        val segmentStart = System.currentTimeMillis()
        try {
            while (true) {
                delay(LEVEL_POLL_MILLIS)
                val amplitude = recorder.sampleAmplitude()
                val now = System.currentTimeMillis()
                if (detector.onSample(now - monitorStart, amplitude, playbackActive())) {
                    triggered = true
                    onTrigger()
                    break
                }
                if (now - segmentStart >= BARGE_SEGMENT_MILLIS) break
            }
            if (!triggered) {
                recorder.cancel()
                continue
            }
            // Capture the rest of the interruption until sustained silence,
            // judged against the calibrated ambient floor (the raw server
            // threshold sits below phone-mic room tone).
            val utteranceStart = System.currentTimeMillis()
            var silentFor = 0L
            while (true) {
                delay(LEVEL_POLL_MILLIS)
                val amplitude = recorder.sampleAmplitude()
                silentFor = if (amplitude < detector.silenceFloorAmplitude) {
                    silentFor + LEVEL_POLL_MILLIS
                } else {
                    0L
                }
                val elapsed = System.currentTimeMillis() - utteranceStart
                if (silentFor >= silenceMillis || elapsed >= BARGE_UTTERANCE_CAP_MILLIS) break
            }
            return withContext(Dispatchers.IO) { recorder.stopAndEncode() }
        } catch (cancelled: CancellationException) {
            recorder.cancel()
            throw cancelled
        }
    }
}

private const val BARGE_SEGMENT_MILLIS = 5_000L
private const val BARGE_UTTERANCE_CAP_MILLIS = 30_000L
/** Do not wait for the normal 3s dictation pause before classifying a barge. */
private const val BARGE_CAPTURE_SILENCE_MILLIS = 1_200L

/**
 * Builds a [VoiceConversationHost] bound to the open session. The loop ends on
 * session switch, when the composable leaves composition, and on activity stop
 * (privacy default: no capture while not visible). Draft text is untouched.
 */
@Composable
fun rememberVoiceConversationHost(
    conversation: ComposerVoiceConversation?,
    sessionId: String,
    chat: ChatSessionSnapshot,
    onSubmit: (text: String, interrupted: Boolean) -> Unit,
    onStopTurn: () -> Unit = {},
    screenOffContinuation: Boolean = false,
    recorderFactory: (Context) -> DictationRecorder = { MediaRecorderDictationRecorder(it) },
    bargeRecorderFactory: (Context) -> DictationRecorder = { context: Context ->
        AudioRecordBargeRecorder(context = context)
    },
    speechEngineFactory: (Context) -> SpeechEngine = { MediaPlayerSpeechEngine(it) },
): VoiceConversationHost? {
    if (conversation == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatState = rememberUpdatedState(chat)
    val submitState = rememberUpdatedState(onSubmit)
    val stopTurnState = rememberUpdatedState(onStopTurn)
    // The dependency bundle is rebuilt by callers on recomposition; reading it
    // through updated state keeps ONE host per open session. Keying the host on
    // the bundle would tear the loop down on every recomposition.
    val conversationState = rememberUpdatedState(conversation)
    val audioRoute = remember { VoiceCommunicationAudioRoute(context) }
    val speechEngine = remember { speechEngineFactory(context) }

    val host = remember(sessionId) {
        val controller = VoiceConversationController {
            conversationState.value.serverConfig.stopPhrases
        }
        val recorder = recorderFactory(context)
        val playback = InterruptiblePlayback(speechEngine)
        VoiceConversationHost(
            controller = controller,
            scope = scope,
            engines = VoiceConversationEngines(
                listen = { onLevel ->
                    recordUtterance(
                        recorder,
                        conversationState.value.serverConfig,
                        onLevel,
                        log = {},
                    )
                },
                transcribe = { dataUrl, mimeType ->
                    conversationState.value.transcribe(dataUrl, mimeType)
                },
                submit = { text, interrupted -> submitState.value(text, interrupted) },
                replies = snapshotFlow { chatState.value.toVoiceReplyView() },
                openStream = { conversationState.value.openStream() },
                sinkFactory = { AudioTrackSpeechSink() },
                restSpeak = { text -> conversationState.value.synthesize(text) },
                playAudio = { audio, onStarted, onFinished, onError ->
                    if (playback.play(audio, onStarted)) onFinished() else onError()
                },
                stopPlayback = { playback.stop() },
                stopTurn = { stopTurnState.value() },
                startAudioSession = {
                    audioRoute.acquire()
                },
                stopAudioSession = { audioRoute.release() },
                monitorBargeIn = { onTrigger ->
                    val config = conversationState.value.serverConfig
                    if (config.bargeInEnabled) {
                        monitorBargeInWithRecorder(
                            recorderFactory = { bargeRecorderFactory(context) },
                            config = config,
                            onTrigger = onTrigger,
                            playbackActive = {
                                controller.state.value == VoiceConversationState.Speaking
                            },
                        )
                    } else {
                        // Barge-in disabled by server config: idle until the
                        // turn's monitor window is cancelled.
                        kotlinx.coroutines.awaitCancellation()
                    }
                },
                log = {},
            ),
        )
    }

    // End on session switch / leaving composition; release the media engine.
    DisposableEffect(host) {
        onDispose { host.end() }
    }
    DisposableEffect(Unit) {
        onDispose { speechEngine.release() }
    }
    // Privacy default: leaving the foreground (including device lock) ends the
    // loop — unless the user opted into screen-off continuation and its
    // microphone foreground service is already running.
    val lifecycleOwner = LocalLifecycleOwner.current
    val continuationState = rememberUpdatedState(screenOffContinuation)
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                !(continuationState.value && VoiceServiceBridge.running)
            ) {
                host.end()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While the loop is active: run the opted-in foreground service (persistent
    // stoppable notification, phase label only — never transcript text), stop
    // the loop on headphone route loss, and expose Stop to the notification.
    val hostState = host.controller.state.collectAsState()
    DisposableEffect(host) {
        VoiceServiceBridge.onStopRequested = { host.end() }
        onDispose {
            VoiceServiceBridge.onStopRequested = null
            VoiceConversationService.stop(context)
        }
    }
    LaunchedEffect(host, screenOffContinuation) {
        snapshotFlow { hostState.value }.collect { state ->
            if (state == VoiceConversationState.Idle) {
                VoiceConversationService.stop(context)
            } else if (screenOffContinuation) {
                val label = when (state) {
                    is VoiceConversationState.Listening -> "Listening"
                    VoiceConversationState.Transcribing -> "Transcribing"
                    VoiceConversationState.Thinking -> "Thinking"
                    VoiceConversationState.Speaking -> "Speaking"
                    VoiceConversationState.Idle -> ""
                }
                VoiceConversationService.start(context, label)
            }
        }
    }
    DisposableEffect(host) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                    host.isActive
                ) {
                    // Wired/Bluetooth route loss must not continue through speakers.
                    host.end()
                }
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return host
}

/** Headphones toggle that enters/ends the hands-free voice conversation. */
@Composable
fun VoiceConversationToggleButton(
    host: VoiceConversationHost,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by host.controller.state.collectAsState()
    val active = state != VoiceConversationState.Idle
    val description = if (active) "End voice conversation" else "Start voice conversation"
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) host.start()
    }
    fun startWithPermission() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) host.start() else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    IconButton(
        onClick = { if (active) host.end() else startWithPermission() },
        enabled = enabled || active,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = Icons.Outlined.Headphones,
            contentDescription = null,
            // The Headphones glyph fills 18dp of its 24dp viewport, dead-center,
            // while the composer's Mic glyph fills 19dp optically centered 0.5dp
            // high. Render slightly larger and nudged up so the pair reads as the
            // same size on the same optical baseline.
            modifier = Modifier
                .size(26.dp)
                .offset(y = (-0.5).dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

/**
 * Compact status bar shown above the composer while the conversation is active:
 * phase label + level animation, mute toggle, and hard End. Stop-Hermes-response
 * remains the composer's separate destructive control.
 */
@Composable
fun VoiceConversationBar(
    host: VoiceConversationHost,
    modifier: Modifier = Modifier,
) {
    val state by host.controller.state.collectAsState()
    val muted by host.controller.muted.collectAsState()
    val notice by host.controller.notice.collectAsState()
    if (state == VoiceConversationState.Idle) return

    val label = when {
        muted -> "Muted"
        else -> when (state) {
            is VoiceConversationState.Listening -> "Listening"
            VoiceConversationState.Transcribing -> "Transcribing"
            VoiceConversationState.Thinking -> "Thinking"
            VoiceConversationState.Speaking -> "Speaking"
            VoiceConversationState.Idle -> ""
        }
    }
    val level = (state as? VoiceConversationState.Listening)?.level ?: 0f
    val pulse by animateFloatAsState(targetValue = 1f + level * 0.6f, label = "voiceLevelPulse")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("Voice conversation bar")
            .semantics {
                contentDescription = "Voice conversation"
                stateDescription = label
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { scaleX = pulse; scaleY = pulse },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = when {
                        muted -> MaterialTheme.colorScheme.outline
                        state is VoiceConversationState.Listening ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                ) {}
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
            notice?.let {
                Text(
                    text = when (it) {
                        VoiceConversationNotice.TranscriptionFailed -> "Couldn't transcribe"
                        VoiceConversationNotice.SpeechFailed -> "Couldn't speak reply"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            IconButton(
                onClick = { host.setMuted(!muted) },
                modifier = Modifier
                    .size(36.dp)
                    .scale(0.9f)
                    .semantics {
                        contentDescription = if (muted) "Unmute microphone" else "Mute microphone"
                    },
            ) {
                Icon(
                    imageVector = if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { host.end() },
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "End voice conversation" },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
