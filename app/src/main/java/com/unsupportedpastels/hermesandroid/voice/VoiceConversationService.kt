package com.unsupportedpastels.hermesandroid.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Process-level bridge between the Compose-owned voice loop and the
 * foreground service: the loop registers its stop callback and state label
 * here; the service's notification Stop action and teardown paths call it.
 * Nothing here persists across process death — recreation never silently
 * re-arms the microphone (the user must explicitly restart voice).
 */
object VoiceServiceBridge {
    @Volatile
    var onStopRequested: (() -> Unit)? = null

    @Volatile
    var running: Boolean = false
        internal set
}

/**
 * Opt-in microphone foreground service that keeps an already-started voice
 * conversation alive across screen-off. Started only from a visible activity
 * after explicit user opt-in; non-exported; START_NOT_STICKY so a killed
 * process never restarts capture on its own. The persistent notification shows
 * the loop phase (never transcript text) and always carries a Stop action.
 */
class VoiceConversationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoiceServiceBridge.onStopRequested?.invoke()
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        val stateLabel = intent?.getStringExtra(EXTRA_STATE)?.take(32) ?: "Listening"
        ensureChannel()
        val notification = buildNotification(stateLabel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        VoiceServiceBridge.running = true
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSelfCleanly()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        VoiceServiceBridge.running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded: the voice loop's own lifecycle stops the service long
            // before this ceiling; the timeout is a leak backstop only.
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Voice conversation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while hands-free voice stays active with the screen off"
            },
        )
    }

    private fun buildNotification(stateLabel: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceConversationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hermes voice conversation")
            .setContentText(stateLabel)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_conversation"
        private const val NOTIFICATION_ID = 0x5601
        private const val ACTION_STOP = "com.unsupportedpastels.hermesandroid.voice.STOP"
        private const val EXTRA_STATE = "state"
        private const val WAKE_LOCK_TAG = "hermes:voice-conversation"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60L * 60L * 1000L

        /** Start/refresh the service with the current loop phase label. */
        fun start(context: Context, stateLabel: String) {
            val intent = Intent(context, VoiceConversationService::class.java)
                .putExtra(EXTRA_STATE, stateLabel)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            VoiceServiceBridge.running = false
            context.stopService(Intent(context, VoiceConversationService::class.java))
        }
    }
}
