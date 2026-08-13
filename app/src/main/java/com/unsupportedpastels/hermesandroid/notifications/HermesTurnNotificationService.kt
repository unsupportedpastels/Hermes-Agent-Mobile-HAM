package com.unsupportedpastels.hermesandroid.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import androidx.core.app.NotificationCompat
import com.unsupportedpastels.hermesandroid.MainActivity
import com.unsupportedpastels.hermesandroid.R
import com.unsupportedpastels.hermesandroid.app.DurableSessionId

interface TurnNotificationController {
    fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int)
    fun activeCountChanged(activeCount: Int)
    fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?)
}

internal object NoOpTurnNotificationController : TurnNotificationController {
    override fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int) = Unit
    override fun activeCountChanged(activeCount: Int) = Unit
    override fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?) = Unit
}

internal class AndroidTurnNotificationController(
    private val context: Context,
) : TurnNotificationController {
    override fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int) {
        context.startForegroundService(serviceIntent(HermesTurnNotificationService.ACTION_START).apply {
            putExtra(HermesTurnNotificationService.EXTRA_COUNT, activeCount)
        })
    }

    override fun activeCountChanged(activeCount: Int) {
        context.startService(serviceIntent(HermesTurnNotificationService.ACTION_COUNT).apply {
            putExtra(HermesTurnNotificationService.EXTRA_COUNT, activeCount)
        })
    }

    override fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String) =
        postInput(HermesTurnNotificationService.ACTION_APPROVAL, sessionId, title, preview)

    override fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String) =
        postInput(HermesTurnNotificationService.ACTION_CLARIFICATION, sessionId, title, preview)

    override fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String) =
        postInput(HermesTurnNotificationService.ACTION_UNSUPPORTED, sessionId, title, preview)

    override fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?) {
        context.startService(serviceIntent(HermesTurnNotificationService.ACTION_COMPLETE).apply {
            putExtra(HermesTurnNotificationService.EXTRA_SESSION_ID, sessionId.value)
            putExtra(HermesTurnNotificationService.EXTRA_TITLE, title)
            putExtra(HermesTurnNotificationService.EXTRA_TEXT, text)
            putExtra(HermesTurnNotificationService.EXTRA_STATUS, status)
        })
    }

    private fun postInput(
        action: String,
        sessionId: DurableSessionId,
        title: String,
        preview: String,
    ) {
        context.startService(serviceIntent(action).apply {
            putExtra(HermesTurnNotificationService.EXTRA_SESSION_ID, sessionId.value)
            putExtra(HermesTurnNotificationService.EXTRA_TITLE, title)
            putExtra(HermesTurnNotificationService.EXTRA_TEXT, preview)
        })
    }

    private fun serviceIntent(action: String) =
        Intent(context, HermesTurnNotificationService::class.java).setAction(action)
}

class HermesTurnNotificationService : Service() {
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_COUNT -> updateActiveCount(intent.getIntExtra(EXTRA_COUNT, 0))
            ACTION_APPROVAL -> postInput(intent, "Hermes needs approval")
            ACTION_CLARIFICATION -> postInput(intent, "Hermes needs your input")
            ACTION_UNSUPPORTED -> postInput(intent, "Hermes needs secure input")
            ACTION_COMPLETE -> postCompletion(intent)
            ACTION_OPEN_SESSION -> openSession(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    private fun updateActiveCount(count: Int) {
        activeCount = count.coerceAtLeast(0)
        if (activeCount == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startForeground(ONGOING_NOTIFICATION_ID, ongoingNotification(activeCount))
    }

    private fun ongoingNotification(count: Int): Notification = NotificationCompat.Builder(this, CHANNEL_ACTIVE)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(activeTurnTitle(count))
        .setContentText("HAM is keeping live responses connected")
        .setContentIntent(openAppIntent(null))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    private fun postInput(intent: Intent, heading: String) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val preview = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(240)
        manager.notify(notificationId(sessionId, 1), NotificationCompat.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setSubText(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openAppIntent(sessionId))
            .addAction(0, "Review in HAM", openAppIntent(sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build())
    }

    private fun postCompletion(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val preview = finalResponsePreview(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        val status = intent.getStringExtra(EXTRA_STATUS)?.lowercase()
        val heading = when (status) {
            "error", "failed" -> "Hermes task failed"
            "cancelled", "canceled", "interrupted" -> "Hermes task was cancelled"
            else -> "Hermes finished"
        }
        manager.cancel(notificationId(sessionId, 1))
        manager.notify(notificationId(sessionId, 2), NotificationCompat.Builder(this, CHANNEL_COMPLETE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setSubText(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openAppIntent(sessionId))
            .addAction(0, "Open session", openAppIntent(sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build())
    }

    private fun openAppIntent(sessionId: String?): PendingIntent {
        if (sessionId == null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val intent = Intent(this, HermesTurnNotificationService::class.java).apply {
            action = ACTION_OPEN_SESSION
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getService(
            this,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openSession(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return
        val sessionId = runCatching { DurableSessionId(value) }.getOrNull() ?: return
        NotificationNavigationInbox.publish(sessionId)
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ACTIVE, "Active Hermes tasks", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_ATTENTION, "Hermes needs attention", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_COMPLETE, "Completed Hermes tasks", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun notificationId(sessionId: String, kind: Int): Int = 31 * sessionId.hashCode() + kind

    companion object {
        private const val CHANNEL_ACTIVE = "hermes_active_tasks"
        private const val CHANNEL_ATTENTION = "hermes_task_attention"
        private const val CHANNEL_COMPLETE = "hermes_task_complete"
        private const val ONGOING_NOTIFICATION_ID = 4100

        internal const val EXTRA_SESSION_ID = "session_id"
        internal const val EXTRA_COUNT = "active_count"
        internal const val EXTRA_TITLE = "session_title"
        internal const val EXTRA_TEXT = "notification_text"
        internal const val EXTRA_STATUS = "completion_status"
        internal const val ACTION_START = "com.unsupportedpastels.hermesandroid.notifications.START"
        internal const val ACTION_COUNT = "com.unsupportedpastels.hermesandroid.notifications.COUNT"
        internal const val ACTION_APPROVAL = "com.unsupportedpastels.hermesandroid.notifications.APPROVAL"
        internal const val ACTION_CLARIFICATION = "com.unsupportedpastels.hermesandroid.notifications.CLARIFICATION"
        internal const val ACTION_UNSUPPORTED = "com.unsupportedpastels.hermesandroid.notifications.UNSUPPORTED"
        internal const val ACTION_COMPLETE = "com.unsupportedpastels.hermesandroid.notifications.COMPLETE"
        internal const val ACTION_OPEN_SESSION = "com.unsupportedpastels.hermesandroid.notifications.OPEN_SESSION"
    }
}
