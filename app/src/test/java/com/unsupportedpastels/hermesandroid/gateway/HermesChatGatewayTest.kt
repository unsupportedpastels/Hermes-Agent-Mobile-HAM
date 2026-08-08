package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatGatewayTest {
    @Test
    fun connectsWithTicketAndResumesUsingSeparateIdentities() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "session.resume") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                      "session_id":"runtime-1","session_key":"durable-1","resumed":true,
                      "messages":[{"role":"assistant","text":"hello","future":true}],"running":true,
                      "inflight":{"user":"prompt","assistant":"partial","streaming":true},
                      "future_field":{"ignored":true}
                    }}
                    """.trimIndent(),
                )
            }
        }

        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )
        val connection = gateway.connect()
        val resumed = connection.resume(
            durableSessionId = com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
            profile = "default",
        )

        assertEquals(listOf("opaque-access"), ticketClient.accessTokens)
        assertEquals(
            listOf("wss://hermes.example/api/ws?ticket=ticket-1"),
            socketFactory.urls,
        )
        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("2.0", request["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("session.resume", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("durable-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("default", params["profile"]!!.jsonPrimitive.content)
        assertFalse(params["close_on_disconnect"]!!.jsonPrimitive.boolean)
        assertEquals("runtime-1", resumed.runtimeSessionId.value)
        assertEquals("durable-1", resumed.durableSessionId?.value)
        assertTrue(resumed.resumed)
        assertTrue(resumed.running)
        assertEquals("hello", resumed.messages.single()["text"]!!.jsonPrimitive.content)
        assertEquals("prompt", resumed.inflight?.user)
        assertEquals("partial", resumed.inflight?.assistant)
        assertTrue(resumed.inflight?.streaming == true)

        connection.close()
    }

    @Test
    fun reportsJsonRpcErrorsWithoutParsingNullResult() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":null,"error":{"code":-32602,"message":"details omitted"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.resume(
                com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        assertEquals("Hermes RPC request failed (-32602)", failure?.message)
        connection.close()
    }

    @Test
    fun submitsPromptAndPreservesEventsThatRaceThePromptAck() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"unknown.future","session_id":"runtime-1","payload":"ignored"}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"runtime-1","payload":{"text":"draft"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"hel","future":1}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-1","payload":{"text":"hello","status":"error","error":"terminal failure"}}}""",
                )
                socket.offer("""{"jsonrpc":"2.0","method":"event","params":{"type":"error","session_id":"runtime-1","payload":{"message":"temporary failure"}}}""")
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming","future":true}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = ticketClient,
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val ack = connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
        val events = connection.events.take(4).toList()

        assertEquals("streaming", ack.status)
        assertEquals(
            listOf(
                HermesChatEvent.MessageStart(RuntimeSessionId("runtime-1"), "draft"),
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), "hel"),
                HermesChatEvent.MessageComplete(
                    RuntimeSessionId("runtime-1"),
                    "hello",
                    "error",
                    "terminal failure",
                ),
                HermesChatEvent.Error(RuntimeSessionId("runtime-1"), "temporary failure"),
            ),
            events,
        )
        connection.close()
    }

    @Test
    fun eventBufferOverflowFailsClosedInsteadOfDroppingStreamEvents() = runTest {
        val socket = ScriptedSocket().apply {
            onSend = {
                repeat(129) { index ->
                    offer(
                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-overflow","payload":{"text":"$index"}}}""",
                    )
                }
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("overflow-ticket"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.submitPrompt(RuntimeSessionId("runtime-overflow"), "hello")
        }.exceptionOrNull()

        assertTrue(failure is HermesChatTransportException)
        connection.close()
    }

    @Test
    fun correlatesConcurrentResponsesByJsonRpcRequestId() = runTest {
        val socket = ScriptedSocket()
        val sentIds = mutableListOf<String>()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            sentIds += request["id"]!!.jsonPrimitive.content
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val first = async { connection.submitPrompt(RuntimeSessionId("runtime-1"), "first") }
        val second = async { connection.submitPrompt(RuntimeSessionId("runtime-1"), "second") }
        advanceUntilIdle()
        assertEquals(2, sentIds.size)

        socket.offer("""{"jsonrpc":"2.0","id":${sentIds[1]},"result":{"status":"streaming"}}""")
        socket.offer("""{"jsonrpc":"2.0","id":${sentIds[0]},"result":{"status":"queued"}}""")

        assertEquals("queued", first.await().status)
        assertEquals("streaming", second.await().status)
        connection.close()
    }

    @Test
    fun closeFailsPendingRequestsAndStopsTheTransport() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()
        val pending = async {
            runCatching {
                connection.submitPrompt(RuntimeSessionId("runtime-1"), "waiting")
            }.exceptionOrNull()
        }
        advanceUntilIdle()

        connection.close()

        assertTrue(pending.await() is HermesChatTransportException)
        assertTrue(socket.closeCount > 0)
    }

    @Test
    fun rejectsFramesOverTheConfiguredBoundBeforeSending() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            maxFrameBytes = 64,
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.submitPrompt(RuntimeSessionId("runtime-1"), "x".repeat(256))
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        assertTrue(socket.sentFrames.isEmpty())
        connection.close()
    }

    @Test
    fun preservesCancellationFromTicketMintingAndSocketConnection() = runTest {
        val origin = ServerOrigin.parse("https://hermes.example")
        val ticketFailure = runCatching {
            HermesChatGateway(
                origin = origin,
                accessToken = "opaque-access",
                ticketClient = object : WsTicketClient {
                    override suspend fun mintTicket(
                        origin: ServerOrigin,
                        accessToken: String,
                    ): WsTicket = throw CancellationException("cancel ticket")
                },
                socketFactory = RecordingSocketFactory(ScriptedSocket()),
                parentScope = backgroundScope,
            ).connect()
        }.exceptionOrNull()
        assertTrue(ticketFailure is CancellationException)

        val socketFailure = runCatching {
            HermesChatGateway(
                origin = origin,
                accessToken = "opaque-access",
                ticketClient = RecordingTicketClient("ticket-1"),
                socketFactory = object : ChatWebSocketFactory {
                    override suspend fun connect(url: String): HermesChatSocket =
                        throw CancellationException("cancel socket")
                },
                parentScope = backgroundScope,
            ).connect()
        }.exceptionOrNull()
        assertTrue(socketFailure is CancellationException)
    }

    @Test
    fun rejectsResumeForDifferentDurableSession() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"session_id":"runtime-1","session_key":"different-durable","running":false}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.resume(com.unsupportedpastels.hermesandroid.app.DurableSessionId("requested-durable"))
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        connection.close()
    }

    @Test
    fun defaultFrameLimitAllowsBoundedLargeResumeResponse() = runTest {
        val socket = ScriptedSocket()
        val largeText = "x".repeat(70_000)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"session_id":"runtime-1","session_key":"durable-1","messages":[{"role":"assistant","text":"$largeText"}],"running":false}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val resumed = connection.resume(
            com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
        )

        assertEquals(70_000, resumed.messages.single()["text"]!!.jsonPrimitive.content.length)
        connection.close()
    }

    @Test
    fun rejectsOversizedIncomingFramesAndFailsPendingRequests() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            maxFrameBytes = 64,
            parentScope = backgroundScope,
        ).connect()
        val pending = async {
            runCatching {
                connection.resume(
                    com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
                )
            }.exceptionOrNull()
        }
        advanceUntilIdle()
        socket.offer("x".repeat(65))

        advanceUntilIdle()
        val failure = pending.await()

        assertTrue(failure is HermesChatProtocolException)
        assertFalse(failure!!.message!!.contains("x".repeat(65)))
        connection.close()
    }

    @Test
    fun mintsTicketsWithBearerPostWithoutPuttingAccessTokenInWebSocketUrl() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/auth/ws-ticket", request.url.encodedPath)
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            assertFalse(request.url.toString().contains("opaque-access"))
            respond(
                content = """{"ticket":"fresh-ticket","ttl_seconds":30,"future":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)

        val ticket = KtorWsTicketClient(client).mintTicket(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals("fresh-ticket", ticket.ticket)
        assertEquals(30, ticket.ttlSeconds)
        client.close()
    }

    @Test
    fun closeRacingRequestRegistrationNeverLeavesRequestPending() = runTest {
        repeat(100) {
            val connection = HermesChatConnection(
                socket = ScriptedSocket(),
                maxFrameBytes = HERMES_CHAT_MAX_FRAME_BYTES,
                parentScope = backgroundScope,
            )
            val request = async {
                runCatching { connection.resume(DurableSessionId("durable-race"), "default") }
            }
            yield()
            connection.close()

            val result = withTimeout(1_000) { request.await() }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun requestsASeparateFreshTicketForEveryConnection() = runTest {
        val sockets = listOf(ScriptedSocket(), ScriptedSocket())
        val urls = mutableListOf<String>()
        var socketIndex = 0
        val factory = object : ChatWebSocketFactory {
            override suspend fun connect(url: String): HermesChatSocket {
                urls += url
                return sockets[socketIndex++]
            }
        }
        val ticketClient = object : WsTicketClient {
            var calls = 0
            override suspend fun mintTicket(
                origin: ServerOrigin,
                accessToken: String,
            ): WsTicket = WsTicket("ticket-${++calls}", 30)
        }
        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = ticketClient,
            socketFactory = factory,
            parentScope = backgroundScope,
        )

        val first = gateway.connect()
        val second = gateway.connect()
        first.close()
        second.close()

        assertEquals(
            listOf(
                "wss://hermes.example/api/ws?ticket=ticket-1",
                "wss://hermes.example/api/ws?ticket=ticket-2",
            ),
            urls,
        )
    }
}

private class RecordingTicketClient(private val ticket: String) : WsTicketClient {
    val accessTokens = mutableListOf<String>()

    override suspend fun mintTicket(
        origin: ServerOrigin,
        accessToken: String,
    ): WsTicket {
        assertEquals("https://hermes.example", origin.value)
        accessTokens += accessToken
        return WsTicket(ticket = ticket, ttlSeconds = 30)
    }
}

private class RecordingSocketFactory(private val socket: ScriptedSocket) : ChatWebSocketFactory {
    val urls = mutableListOf<String>()

    override suspend fun connect(url: String): HermesChatSocket {
        urls += url
        return socket
    }
}

private class ScriptedSocket : HermesChatSocket {
    val sentFrames = mutableListOf<String>()
    var closeCount = 0
    private val incoming = Channel<String>(Channel.UNLIMITED)
    var onSend: (suspend (String) -> Unit)? = null

    override suspend fun sendText(text: String) {
        sentFrames += text
        onSend?.invoke(text)
    }

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        closeCount += 1
        incoming.close()
    }

    suspend fun offer(frame: String) {
        incoming.send(frame)
    }
}
