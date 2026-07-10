package com.hermes.mobile.data

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HermesGateway {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val nextId = AtomicLong()
    private val connectionGeneration = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var opening: CompletableDeferred<Unit>? = null
    @Volatile private var connected = false

    suspend fun connect(
        baseUrl: String,
        ticket: String,
        onEvent: (GatewayEvent) -> Unit,
        onDisconnect: (Throwable) -> Unit = {},
    ) {
        close()
        val generation = connectionGeneration.incrementAndGet()
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}"
            else -> "ws://${baseUrl.removePrefix("http://")}"
        }.trimEnd('/')
        val opened = CompletableDeferred<Unit>()
        opening = opened
        val disconnectReported = AtomicBoolean()
        val request = Request.Builder().url("$wsBase/api/ws?ticket=${java.net.URLEncoder.encode(ticket, "UTF-8")}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration.get()) {
                    webSocket.close(1000, "superseded")
                    return
                }
                connected = true
                opening = null
                opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation == connectionGeneration.get()) handleFrame(text, onEvent)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration.get()) return
                Log.w(TAG, "WebSocket failed after HTTP ${response?.code ?: "upgrade"}", t)
                if (!opened.isCompleted) opened.completeExceptionally(t)
                reportDisconnect(generation, t, disconnectReported, onDisconnect)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) return
                val error = IOException("Hermes live connection closed: $code $reason")
                Log.w(TAG, "WebSocket closed: code=$code reason=$reason")
                reportDisconnect(generation, error, disconnectReported, onDisconnect)
            }
        })
        try {
            withTimeout(15_000) { opened.await() }
        } catch (error: Throwable) {
            if (generation == connectionGeneration.get()) close()
            throw error
        }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = "android-${nextId.incrementAndGet()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val sent = socket?.send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
                .toString(),
        ) == true
        if (!sent) {
            pending.remove(id)
            throw IOException("Hermes live connection is not open")
        }
        return try {
            withTimeout(120_000) { deferred.await() }
        } finally {
            pending.remove(id, deferred)
        }
    }

    fun close() {
        connectionGeneration.incrementAndGet()
        connected = false
        opening?.completeExceptionally(IOException("Hermes live connection was superseded"))
        opening = null
        socket?.close(1000, "client closing")
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    fun isConnected(): Boolean = connected

    private fun reportDisconnect(
        generation: Long,
        error: Throwable,
        reported: AtomicBoolean,
        onDisconnect: (Throwable) -> Unit,
    ) {
        if (generation != connectionGeneration.get() || !reported.compareAndSet(false, true)) return
        connected = false
        opening = null
        socket = null
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        onDisconnect(error)
    }

    private fun handleFrame(text: String, onEvent: (GatewayEvent) -> Unit) {
        val frame = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Ignoring malformed WebSocket frame (${text.length} chars)", it)
            return
        }
        if (frame.optString("method") == "event") {
            val params = frame.optJSONObject("params") ?: return
            onEvent(
                GatewayEvent(
                    type = params.optString("type"),
                    sessionId = params.optString("session_id"),
                    payload = params.optJSONObject("payload") ?: JSONObject(),
                ),
            )
            return
        }
        val id = frame.optString("id")
        val call = pending.remove(id) ?: return
        val error = frame.optJSONObject("error")
        if (error != null) call.completeExceptionally(HermesRpcException(error.optString("message", "Hermes request failed")))
        else call.complete(frame.optJSONObject("result") ?: JSONObject())
    }
}

data class GatewayEvent(
    val type: String,
    val sessionId: String,
    val payload: JSONObject,
)

class HermesRpcException(message: String) : IOException(message)

private const val TAG = "HermesGateway"
