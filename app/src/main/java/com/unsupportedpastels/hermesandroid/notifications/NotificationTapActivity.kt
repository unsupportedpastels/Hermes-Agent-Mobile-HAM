package com.unsupportedpastels.hermesandroid.notifications

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.unsupportedpastels.hermesandroid.MainActivity
import com.unsupportedpastels.hermesandroid.app.DurableSessionId

internal const val EXTRA_TAP_SESSION_ID = "session_id"

internal fun notificationTapIntent(context: Context, sessionId: String): Intent =
    Intent(context, NotificationTapActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_TAP_SESSION_ID, sessionId)
    }

class NotificationTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_TAP_SESSION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 256 }
            ?.let { runCatching { DurableSessionId(it) }.getOrNull() }
        if (sessionId != null) {
            NotificationNavigationInbox.publish(sessionId)
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        finish()
    }
}
