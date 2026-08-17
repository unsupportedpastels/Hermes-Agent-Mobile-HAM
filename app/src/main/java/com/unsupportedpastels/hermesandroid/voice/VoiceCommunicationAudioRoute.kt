package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build

/** Owns the process-wide Android voice-communication audio mode. */
class VoiceCommunicationAudioRoute(
    context: Context,
    private val onDiagnostics: (String) -> Unit = {},
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var holders = 0
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var previousCommunicationDevice: AudioDeviceInfo? = null

    @Synchronized
    fun acquire() {
        if (holders == 0) {
            previousMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice = audioManager.communicationDevice
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            selectSpeaker()
        }
        holders++
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val previousDevice = previousCommunicationDevice
                if (previousDevice != null) {
                    val restored = audioManager.setCommunicationDevice(previousDevice)
                    onDiagnostics("audio:route speaker-restored=$restored")
                } else {
                    audioManager.clearCommunicationDevice()
                    onDiagnostics("audio:route speaker-restored=true")
                }
            } else {
                audioManager.isSpeakerphoneOn = previousSpeakerphone
            }
            audioManager.mode = previousMode
        }
    }

    @Synchronized
    fun isHeld(): Boolean = holders > 0

    private fun selectSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            val selected = speaker?.let(audioManager::setCommunicationDevice) == true
            onDiagnostics("audio:route speaker-selected=$selected available=${speaker != null}")
        } else {
            audioManager.isSpeakerphoneOn = true
            onDiagnostics("audio:route speaker-selected=${audioManager.isSpeakerphoneOn}")
        }
    }
}
