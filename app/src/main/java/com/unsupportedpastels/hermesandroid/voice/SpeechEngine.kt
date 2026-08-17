package com.unsupportedpastels.hermesandroid.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays synthesized speech audio. The Android implementation
 * ([MediaPlayerSpeechEngine]) owns audio focus, the ACTION_AUDIO_BECOMING_NOISY
 * (unplug) stop, and player/temp-file release; the [SpeechPlaybackController]
 * state machine and the read-aloud coordination are tested independently.
 */
interface SpeechEngine {
    /**
     * Begin playback. [onStarted] fires when audio actually starts, [onFinished]
     * on natural completion or an unplug/becoming-noisy stop, and [onError] on
     * any failure (focus denied, decode/playback error).
     */
    suspend fun play(
        audio: SpeechAudio,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: () -> Unit,
    )

    /** Stop and release without firing [onFinished] (the caller drove the stop). */
    fun stop()

    /** Release all resources permanently. */
    fun release()
}

private fun mimeExtension(mimeType: String): String =
    when (mimeType.substringAfter('/').substringBefore(';').lowercase()) {
        "mpeg", "mp3" -> ".mp3"
        "wav", "x-wav" -> ".wav"
        "ogg", "opus" -> ".ogg"
        "aac" -> ".aac"
        "mp4", "m4a", "x-m4a" -> ".m4a"
        else -> ".bin"
    }

/**
 * [MediaPlayer]-backed speech playback. Requests transient audio focus (so it
 * ducks other media and yields to calls), stops on headphone unplug, and reuses
 * the app cache + temp-file/release conventions from the artifact audio player.
 */
class MediaPlayerSpeechEngine(
    private val context: Context,
) : SpeechEngine {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var tempFile: File? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    override suspend fun play(
        audio: SpeechAudio,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: () -> Unit,
    ) {
        stop()
        val file = try {
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "read-aloud").apply { mkdirs() }
                File.createTempFile("speech-", mimeExtension(audio.mimeType), directory).apply {
                    writeBytes(audio.bytes)
                }
            }
        } catch (_: Exception) {
            onError()
            return
        }
        tempFile = file

        if (!requestFocus()) {
            cleanup()
            onError()
            return
        }
        registerNoisyReceiver(onFinished)

        try {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    cleanup()
                    onFinished()
                }
                setOnErrorListener { _, _, _ ->
                    cleanup()
                    onError()
                    true
                }
                setOnPreparedListener {
                    it.start()
                    onStarted()
                }
                prepareAsync()
            }
            player = mediaPlayer
        } catch (_: Exception) {
            cleanup()
            onError()
        }
    }

    override fun stop() {
        cleanup()
    }

    override fun release() {
        cleanup()
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun registerNoisyReceiver(onFinished: () -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    cleanup()
                    onFinished()
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        noisyReceiver = receiver
    }

    private fun cleanup() {
        noisyReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            noisyReceiver = null
        }
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        focusRequest?.let {
            runCatching { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
        tempFile?.delete()
        tempFile = null
    }
}
