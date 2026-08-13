package com.unsupportedpastels.hermesandroid.notifications

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationNavigationRequest(
    val requestId: Long,
    val sessionId: DurableSessionId,
)

object NotificationNavigationInbox {
    private val nextRequestId = AtomicLong()
    private val mutableRequests = MutableStateFlow<NotificationNavigationRequest?>(null)
    val requests = mutableRequests.asStateFlow()

    fun publish(sessionId: DurableSessionId): NotificationNavigationRequest =
        NotificationNavigationRequest(nextRequestId.incrementAndGet(), sessionId).also {
            mutableRequests.value = it
        }
}