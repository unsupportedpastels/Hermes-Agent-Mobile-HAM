package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.readBodyTextBounded
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val HERMES_CHAT_MAX_FRAME_BYTES = 1024 * 1024
private const val MAX_CONFIGURED_FRAME_BYTES = HERMES_CHAT_MAX_FRAME_BYTES
private const val DEFAULT_MAX_FRAME_BYTES = MAX_CONFIGURED_FRAME_BYTES
private const val MAX_TICKET_RESPONSE_BYTES = 16 * 1024
private const val MAX_EVENT_BUFFER = 128

/** A fresh, single-use ticket returned by /api/auth/ws-ticket. */
data class WsTicket(
    val ticket: String,
    val ttlSeconds: Long,
) {
    init {
        require(ticket.isNotBlank()) { "Hermes WebSocket ticket must not be blank" }
        require(ttlSeconds > 0) { "Hermes WebSocket ticket TTL must be positive" }
    }
}

interface WsTicketClient {
    suspend fun mintTicket(origin: ServerOrigin, accessToken: String): WsTicket
}

interface HermesChatSocket {
    suspend fun sendText(text: String)

    /** Returns null when the peer has closed the WebSocket. */
    suspend fun receiveText(): String?

    suspend fun close()
}

interface ChatWebSocketFactory {
    suspend fun connect(url: String): HermesChatSocket
}

open class HermesChatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class HermesChatProtocolException(
    message: String,
    cause: Throwable? = null,
) : HermesChatException(message, cause)

class HermesChatTransportException(
    message: String,
    cause: Throwable? = null,
) : HermesChatException(message, cause)

data class ResumedChatSession(
    val runtimeSessionId: RuntimeSessionId,
    val durableSessionId: DurableSessionId?,
    val resumed: Boolean,
    val messages: List<JsonObject>,
    val running: Boolean,
    val inflight: InflightPrompt?,
)

data class InflightPrompt(
    val user: String?,
    val assistant: String?,
    val streaming: Boolean,
)

data class PromptSubmission(
    val status: String,
)

/**
 * One row of a `complete.slash` result. [text] is inserted into the composer;
 * [display] and [meta] are presentation only. Defined here (not in the UI layer)
 * so the chat transport owns its own result type.
 */
data class SlashCompletionItem(
    val text: String,
    val display: String = "/$text",
    val meta: String? = null,
)

/** Tolerantly parsed `complete.slash` JSON-RPC result. */
data class SlashCompletionResult(
    val items: List<SlashCompletionItem>,
    val replaceFrom: Int,
)

sealed interface HermesChatEvent {
    val sessionId: RuntimeSessionId

    data class MessageStart(
        override val sessionId: RuntimeSessionId,
        val text: String?,
    ) : HermesChatEvent

    data class MessageDelta(
        override val sessionId: RuntimeSessionId,
        val text: String,
    ) : HermesChatEvent

    data class MessageComplete(
        override val sessionId: RuntimeSessionId,
        val text: String?,
        val status: String?,
        val error: String? = null,
    ) : HermesChatEvent

    data class Error(
        override val sessionId: RuntimeSessionId,
        val message: String,
    ) : HermesChatEvent
}

fun interface HermesChatConnector {
    suspend fun connect(origin: ServerOrigin, accessToken: String): HermesChatSession
}

interface HermesChatSession {
    val events: Flow<HermesChatEvent>

    suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String? = null,
    ): ResumedChatSession

    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission

    /** Live slash-command completion from the connected host; never a static local list. */
    suspend fun completeSlash(text: String): SlashCompletionResult =
        throw HermesChatProtocolException("Slash completion is not available")

    suspend fun close()
}

/**
 * Ticketed JSON-RPC chat transport. The access token is consumed only by the ticket client;
 * the WebSocket factory receives a URL containing only the fresh single-use ticket.
 */
class HermesChatGateway(
    private val origin: ServerOrigin,
    private val accessToken: String,
    private val ticketClient: WsTicketClient,
    private val socketFactory: ChatWebSocketFactory,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        require(accessToken.isNotBlank()) { "Hermes access token must not be blank" }
        require(maxFrameBytes in 1..MAX_CONFIGURED_FRAME_BYTES) {
            "Hermes frame limit is out of bounds"
        }
    }

    suspend fun connect(): HermesChatConnection {
        val ticket = ticketClient.mintTicket(origin, accessToken)
        val socketUrl = websocketUrl(origin, ticket.ticket)
        val socket = try {
            socketFactory.connect(socketUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not connect to Hermes chat", error)
        }
        return HermesChatConnection(
            socket = socket,
            maxFrameBytes = maxFrameBytes,
            parentScope = parentScope,
        )
    }

    private fun websocketUrl(origin: ServerOrigin, ticket: String): String {
        val httpsOrigin = origin.value
        val wssOrigin = httpsOrigin.replaceFirst("https://", "wss://")
        val encodedTicket = URLEncoder.encode(ticket, StandardCharsets.UTF_8.name())
        return "$wssOrigin/api/ws?ticket=$encodedTicket"
    }
}

class HermesChatConnection internal constructor(
    private val socket: HermesChatSocket,
    private val maxFrameBytes: Int,
    parentScope: CoroutineScope,
) : HermesChatSession {
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<JsonObject>>()
    private val eventChannel = Channel<HermesChatEvent>(capacity = MAX_EVENT_BUFFER)
    private val connectionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(parentScope.coroutineContext + connectionJob)
    private val readerJob: Job = connectionScope.launch { readLoop() }
    private val json = Json { ignoreUnknownKeys = true }

    override val events: Flow<HermesChatEvent> = eventChannel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        val params = buildJsonObject {
            put("session_id", durableSessionId.value)
            profile?.let { put("profile", it) }
            put("close_on_disconnect", false)
        }
        return parseResumeResult(request("session.resume", params), durableSessionId)
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId.value)
            put("text", text)
        }
        val result = request("prompt.submit", params)
        val status = result.stringValue("status")
            ?: throw HermesChatProtocolException("Prompt response was incomplete")
        return PromptSubmission(status)
    }

    override suspend fun completeSlash(text: String): SlashCompletionResult {
        val params = buildJsonObject { put("text", text) }
        val result = request("complete.slash", params)
        val rawItems = result["items"] as? JsonArray
        val items = rawItems.orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val itemText = row.stringValue("text")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val display = row.stringValue("display")
                ?.takeIf(String::isNotBlank)
                ?: "/$itemText"
            val meta = row.stringValue("meta")?.takeIf(String::isNotBlank)
            SlashCompletionItem(text = itemText, display = display, meta = meta)
        }
        val replaceFrom = result.longValue("replace_from")?.toInt() ?: 0
        return SlashCompletionResult(items = items, replaceFrom = replaceFrom)
    }

    override suspend fun close() {
        if (!markClosed()) return
        connectionJob.cancel()
        failPending(HermesChatTransportException("Hermes chat connection closed"))
        eventChannel.close()
        runCatching { socket.close() }
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextRequestId.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        synchronized(lifecycleLock) {
            if (closed.get()) {
                throw HermesChatTransportException("Hermes chat connection is closed")
            }
            pendingRequests[id] = deferred
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        try {
            ensureFrameSize(frame)
            socket.sendText(frame)
            return deferred.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not send Hermes chat request", error)
        } finally {
            pendingRequests.remove(id, deferred)
        }
    }

    private suspend fun readLoop() {
        try {
            while (connectionScope.isActive) {
                val frame = socket.receiveText() ?: break
                ensureFrameSize(frame)
                handleFrame(frame)
            }
            if (!closed.get()) {
                failPending(HermesChatTransportException("Hermes chat connection closed by peer"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HermesChatException) {
            failPending(error)
        } catch (error: Exception) {
            failPending(HermesChatTransportException("Hermes chat receive failed", error))
        } finally {
            markClosed()
            failPending(HermesChatTransportException("Hermes chat connection closed"))
            eventChannel.close()
            runCatching { socket.close() }
        }
    }

    private fun handleFrame(frame: String) {
        val message = try {
            json.parseToJsonElement(frame).jsonObject
        } catch (error: Exception) {
            throw HermesChatProtocolException("Hermes chat frame was invalid", error)
        }
        if (message.stringValue("jsonrpc") != "2.0") return

        if (message.stringValue("method") == "event") {
            handleEvent(message)
            return
        }

        val id = message.longValue("id") ?: return
        val deferred = pendingRequests.remove(id) ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            val code = error.longValue("code")
            val suffix = code?.let { " ($it)" }.orEmpty()
            deferred.completeExceptionally(
                HermesChatProtocolException("Hermes RPC request failed$suffix"),
            )
            return
        }
        val result = message["result"] as? JsonObject
        if (result == null) {
            deferred.completeExceptionally(HermesChatProtocolException("Hermes response was incomplete"))
        } else {
            deferred.complete(result)
        }
    }

    private fun handleEvent(message: JsonObject) {
        val params = message["params"] as? JsonObject ?: return
        val sessionId = params.stringValue("session_id")?.let {
            runCatching { RuntimeSessionId(it) }.getOrNull()
        } ?: return
        val type = params.stringValue("type") ?: return
        if (type !in setOf("message.start", "message.delta", "message.complete", "error")) return
        val payload = params["payload"] as? JsonObject ?: return
        val event = when (type) {
            "message.start" -> HermesChatEvent.MessageStart(
                sessionId = sessionId,
                text = payload.stringValue("text"),
            )

            "message.delta" -> payload.stringValue("text")?.let { text ->
                HermesChatEvent.MessageDelta(sessionId, text)
            }

            "message.complete" -> HermesChatEvent.MessageComplete(
                sessionId = sessionId,
                text = payload.stringValue("text"),
                status = payload.stringValue("status"),
                error = payload.stringValue("error"),
            )

            "error" -> payload.stringValue("message")
                ?.takeIf(String::isNotBlank)
                ?.let { HermesChatEvent.Error(sessionId, it) }

            else -> null
        }
        if (event != null && eventChannel.trySend(event).isFailure) {
            throw HermesChatTransportException("Hermes chat event buffer exceeded")
        }
    }

    private fun parseResumeResult(
        result: JsonObject,
        requestedDurableSessionId: DurableSessionId,
    ): ResumedChatSession {
        val runtimeSessionId = result.stringValue("session_id")?.let {
            runCatching { RuntimeSessionId(it) }.getOrNull()
        } ?: throw HermesChatProtocolException("Resume response was incomplete")
        val durableSessionId = result.stringValue("session_key")
            ?.takeIf(String::isNotBlank)
            ?.let(::DurableSessionId)
        if (durableSessionId != null && durableSessionId != requestedDurableSessionId) {
            throw HermesChatProtocolException("Resume response referenced a different durable session")
        }
        val messages = result["messages"] as? JsonArray ?: JsonArray(emptyList())
        val inflight = (result["inflight"] as? JsonObject)?.let { value ->
            InflightPrompt(
                user = value.stringValue("user"),
                assistant = value.stringValue("assistant"),
                streaming = value.booleanValue("streaming") ?: false,
            )
        }
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = result.booleanValue("resumed") ?: false,
            messages = messages.filterIsInstance<JsonObject>(),
            running = result.booleanValue("running") ?: false,
            inflight = inflight,
        )
    }

    private fun ensureFrameSize(frame: String) {
        if (frame.toByteArray(StandardCharsets.UTF_8).size > maxFrameBytes) {
            throw HermesChatProtocolException("Hermes chat frame exceeds the size limit")
        }
    }

    private fun markClosed(): Boolean = synchronized(lifecycleLock) {
        closed.compareAndSet(false, true)
    }

    private fun failPending(error: HermesChatException) {
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longValue(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

class KtorWsTicketClient(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WsTicketClient {
    override suspend fun mintTicket(origin: ServerOrigin, accessToken: String): WsTicket {
        if (accessToken.isBlank()) throw HermesChatException("Hermes access token must not be blank")
        return try {
            val response = client.post("${origin.value}/api/auth/ws-ticket") {
                bearerAuth(accessToken)
            }
            val body = response.readBodyTextBounded(MAX_TICKET_RESPONSE_BYTES)
            if (!response.status.isSuccess()) {
                throw HermesChatTransportException(
                    "Hermes ticket request returned HTTP ${response.status.value}",
                )
            }
            val value = json.parseToJsonElement(body).jsonObject
            val ticket = value.stringValue("ticket")
                ?.takeIf(String::isNotBlank)
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            val ttlSeconds = value.longValue("ttl_seconds")
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            WsTicket(ticket, ttlSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not mint Hermes chat ticket", error)
        }
    }
}

class KtorChatWebSocketFactory(
    private val client: HttpClient,
) : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket {
        return try {
            KtorHermesChatSocket(client.webSocketSession { url(url) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not connect to Hermes chat", error)
        }
    }
}

private class KtorHermesChatSocket(
    private val session: WebSocketSession,
) : HermesChatSocket {
    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun receiveText(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return String(frame.data, StandardCharsets.UTF_8)
        }
    }

    override suspend fun close() {
        session.cancel()
    }
}
