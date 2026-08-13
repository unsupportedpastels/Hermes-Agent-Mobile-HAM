package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.http.Url
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOAuthTest {
    @Test
    fun authorizationUrlUsesGatewayBrokerPkceAndLoopbackCallback() {
        val verifier = "a".repeat(43)
        val challenge = NativeOAuth.s256Challenge(verifier)

        val url = NativeOAuth.authorizationUrl(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            challenge = challenge,
            redirectUri = "http://127.0.0.1:54321/callback",
            state = "client-state",
        )

        assertEquals("https", url.protocol.name)
        assertEquals("hermes.example", url.host)
        assertEquals("/auth/native/authorize", url.encodedPath)
        assertEquals("nous", url.parameters["provider"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(challenge, url.parameters["code_challenge"])
        assertEquals("http://127.0.0.1:54321/callback", url.parameters["redirect_uri"])
        assertEquals("client-state", url.parameters["state"])
    }

    @Test
    fun callbackRejectsMismatchedStateBeforeReturningCode() {
        val result = runCatching {
            NativeOAuth.parseCallback(
                requestTarget = "/callback?code=one-time-code&state=attacker-state",
                expectedState = "client-state",
            )
        }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("one-time-code"))
    }

    @Test
    fun callbackRejectsUnexpectedQueryParameters() {
        val result = runCatching {
            NativeOAuth.parseCallback(
                requestTarget = "/callback?code=one-time-code&state=client-state&unexpected=value",
                expectedState = "client-state",
            )
        }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("one-time-code"))
    }

    @Test
    fun requestLineReadRejectsSlowTricklePastTotalDeadline() {
        val request = "GET /callback?code=code&state=state HTTP/1.1\r\n"
            .toByteArray(Charsets.US_ASCII)
        var index = 0
        var nowNanos = 0L
        val slowInput = object : InputStream() {
            override fun read(): Int {
                nowNanos += TimeUnit.MILLISECONDS.toNanos(400)
                return request.getOrNull(index++)?.toInt()?.and(0xff) ?: -1
            }
        }

        val result = readBoundedRequestLine(
            input = slowInput,
            maxBytes = 8 * 1024,
            deadlineNanos = TimeUnit.SECONDS.toNanos(1),
            nanoTime = { nowNanos },
        )

        assertNull(result)
    }

    @Test
    fun tokenExchangePostsJsonAndParsesOpaqueTokens() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            assertEquals("/auth/native/token", request.url.encodedPath)
            requestBody = (request.body as TextContent).text
            respond(
                content = """{
                    "access_token":"opaque-access",
                    "refresh_token":"opaque-refresh",
                    "expires_at":2000000000,
                    "provider":"nous",
                    "user_id":"user"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesNativeAuthClient(HttpClient(engine))

        val tokens = client.exchange(
            ServerOrigin.parse("https://hermes.example"),
            code = "one-time-code",
            verifier = "pkce-verifier",
        )

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("one-time-code", body.getValue("code").toString().trim('"'))
        assertEquals("pkce-verifier", body.getValue("code_verifier").toString().trim('"'))
        assertEquals("opaque-access", tokens.accessToken)
        assertEquals("opaque-refresh", tokens.refreshToken)
        assertEquals("nous", tokens.provider)
    }

    @Test
    fun tokenExchangeRetriesTransientDnsResolutionBeforeSendingRequest() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            if (attempts < 3) throw UnknownHostException("temporary dns failure")
            respond(
                content = """{
                    "access_token":"opaque-access",
                    "refresh_token":"opaque-refresh",
                    "expires_at":2000000000,
                    "provider":"nous",
                    "user_id":"user"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesNativeAuthClient(HttpClient(engine))

        val tokens = client.exchange(
            ServerOrigin.parse("https://hermes.example"),
            code = "one-time-code",
            verifier = "pkce-verifier",
        )

        assertEquals(3, attempts)
        assertEquals("opaque-access", tokens.accessToken)
    }

    @Test
    fun tokenExchangeRetriesCioUnresolvedAddressFailure() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            if (attempts == 1) throw UnresolvedAddressException()
            respond(
                content = """{
                    "access_token":"opaque-access",
                    "refresh_token":"opaque-refresh",
                    "expires_at":2000000000,
                    "provider":"nous",
                    "user_id":"user"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesNativeAuthClient(HttpClient(engine))

        val tokens = client.exchange(
            ServerOrigin.parse("https://hermes.example"),
            code = "one-time-code",
            verifier = "pkce-verifier",
        )

        assertEquals(2, attempts)
        assertEquals("opaque-access", tokens.accessToken)
    }

    @Test
    fun tokenExchangeAcceptsAccessTokenOnlySessionWhenPortalOmitsRefreshToken() = runTest {
        val engine = MockEngine {
            respond(
                content = """{
                    "access_token":"opaque-access",
                    "expires_at":2000000000,
                    "provider":"nous",
                    "user_id":"user"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesNativeAuthClient(HttpClient(engine))

        val tokens = client.exchange(
            ServerOrigin.parse("https://hermes.example"),
            code = "one-time-code",
            verifier = "pkce-verifier",
        )

        assertEquals("opaque-access", tokens.accessToken)
        assertEquals("", tokens.refreshToken)
    }

    @Test
    fun tokenExchangeRejectsResponseMissingRequiredFields() = runTest {
        val incompleteResponses = listOf(
            """{"access_token":"access","refresh_token":"refresh","provider":"nous","user_id":"user"}""",
            """{"access_token":"access","refresh_token":"refresh","expires_at":2000000000,"user_id":"user"}""",
            """{"access_token":"access","refresh_token":"refresh","expires_at":2000000000,"provider":"nous"}""",
            """{"access_token":"access","refresh_token":"refresh","expires_at":0,"provider":"nous","user_id":"user"}""",
        )

        for (responseBody in incompleteResponses) {
            val engine = MockEngine {
                respond(
                    content = responseBody,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val client = HttpHermesNativeAuthClient(HttpClient(engine))

            assertTrue(
                runCatching {
                    client.exchange(
                        ServerOrigin.parse("https://hermes.example"),
                        code = "code",
                        verifier = "verifier",
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun nativeLoginReceivesLoopbackCodeAndExchangesIt() = runBlocking {
        val exchanger = FakeNativeTokenExchanger()
        val login = HermesNativeLogin(
            exchanger = exchanger,
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )

        val tokens = login.signIn(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            openBrowser = { authorizeUrl ->
                val authorize = Url(authorizeUrl)
                val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                val state = requireNotNull(authorize.parameters["state"])
                Socket(redirect.host, redirect.port).use { socket ->
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write(
                        "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\nConnection: close\r\n\r\n",
                    )
                    writer.flush()
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains("200"))
                }
                assertEquals("/auth/native/authorize", authorize.encodedPath)
                assertEquals("nous", authorize.parameters["provider"])
            },
        )

        assertEquals("gateway-code", exchanger.code)
        assertTrue(exchanger.verifier.length in 43..128)
        assertEquals("opaque-access", tokens.accessToken)
    }

    @Test
    fun nativeLoginWaitsForBrowserReturnBeforeRedeemingCode() = runBlocking {
        val authorizeUrl = CompletableDeferred<String>()
        val browserReturned = CompletableDeferred<Unit>()
        val exchangeStarted = CompletableDeferred<Unit>()
        val login = HermesNativeLogin(
            exchanger = object : NativeTokenExchanger {
                override suspend fun exchange(
                    serverOrigin: ServerOrigin,
                    code: String,
                    verifier: String,
                ): NativeTokenSet {
                    exchangeStarted.complete(Unit)
                    return NativeTokenSet(
                        accessToken = "opaque-access",
                        refreshToken = "opaque-refresh",
                        expiresAt = 2_000_000_000,
                        provider = "nous",
                        userId = "user",
                    )
                }
            },
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
            awaitExchangeReady = { browserReturned.await() },
        )
        val signIn = async {
            login.signIn(
                serverOrigin = ServerOrigin.parse("https://hermes.example"),
                provider = "nous",
                openBrowser = { url ->
                    authorizeUrl.complete(url)
                    browserReturned.await()
                },
            )
        }
        val authorize = Url(authorizeUrl.await())
        val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
        val state = requireNotNull(authorize.parameters["state"])
        val callback = async(Dispatchers.IO) {
            sendCallbackRequest(
                redirect,
                "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
            )
        }

        assertNull(withTimeoutOrNull(250) { exchangeStarted.await() })
        browserReturned.complete(Unit)

        assertTrue(callback.await().startsWith("HTTP/1.1 200"))
        assertEquals("opaque-access", signIn.await().accessToken)
        assertTrue(exchangeStarted.isCompleted)
    }

    @Test
    fun nativeLoginShowsFailurePageForOAuthErrorCallback() = runBlocking {
        val login = HermesNativeLogin(
            exchanger = FakeNativeTokenExchanger(),
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )
        var browserResponse = ""

        val result = runCatching {
            login.signIn(
                serverOrigin = ServerOrigin.parse("https://hermes.example"),
                provider = "nous",
                openBrowser = { authorizeUrl ->
                    val authorize = Url(authorizeUrl)
                    val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                    val state = requireNotNull(authorize.parameters["state"])
                    browserResponse = sendCallbackRequest(
                        redirect,
                        "GET ${redirect.path}?error=access_denied&state=$state HTTP/1.1\r\n",
                    )
                },
            )
        }

        assertTrue(result.isFailure)
        assertTrue(browserResponse.startsWith("HTTP/1.1 400"))
        assertFalse(browserResponse.contains("Signed in to Hermes"))
        assertTrue(browserResponse.contains("history.replaceState(null,\"\",\"/complete\")"))
    }

    @Test
    fun nativeLoginAcknowledgesCallbackBeforeTokenExchangeFailure() = runBlocking {
        val login = HermesNativeLogin(
            exchanger = object : NativeTokenExchanger {
                override suspend fun exchange(
                    serverOrigin: ServerOrigin,
                    code: String,
                    verifier: String,
                ): NativeTokenSet = throw HermesConnectionException("Token exchange failed")
            },
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )
        var browserResponse = ""

        val result = runCatching {
            login.signIn(
                serverOrigin = ServerOrigin.parse("https://hermes.example"),
                provider = "nous",
                openBrowser = { authorizeUrl ->
                    val authorize = Url(authorizeUrl)
                    val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                    val state = requireNotNull(authorize.parameters["state"])
                    browserResponse = sendCallbackRequest(
                        redirect,
                        "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
                    )
                },
            )
        }

        assertTrue(result.isFailure)
        assertTrue(browserResponse.startsWith("HTTP/1.1 200"))
        assertTrue(browserResponse.contains("Return to Hermes"))
        assertTrue(browserResponse.contains("Sign-in will finish securely in the app."))
        assertFalse(browserResponse.contains("Signed in to Hermes"))
        assertTrue(browserResponse.contains("history.replaceState(null,\"\",\"/complete\")"))
    }

    @Test
    fun nativeLoginRejectsMalformedRequestsAndContinuesToValidCallback() = runBlocking {
        val exchanger = FakeNativeTokenExchanger()
        val login = HermesNativeLogin(
            exchanger = exchanger,
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )

        val tokens = login.signIn(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            openBrowser = { authorizeUrl ->
                val authorize = Url(authorizeUrl)
                val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                val state = requireNotNull(authorize.parameters["state"])

                val malformedResponses = listOf(
                    "POST ${redirect.path}?code=wrong&state=$state HTTP/1.1",
                    "GET /wrong-path?code=wrong&state=$state HTTP/1.1",
                    "GET ${redirect.path}?code=wrong&error=denied&state=$state HTTP/1.1",
                    "GET ${redirect.path}?code=wrong&state=attacker HTTP/1.1",
                    "GET ${redirect.path}?code=wrong%ZZ&state=$state HTTP/1.1",
                    "GET ${redirect.path}?code=wrong&state=$state HTTP/2",
                )
                for (requestLine in malformedResponses) {
                    val response = sendCallbackRequest(redirect, "$requestLine\r\n")
                    assertTrue(response.startsWith("HTTP/1.1 400"))
                    assertFalse(response.contains("Signed in to Hermes"))
                }

                val validResponse = sendCallbackRequest(
                    redirect,
                    "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
                )
                assertTrue(validResponse.startsWith("HTTP/1.1 200"))
                assertTrue(validResponse.contains("Return to Hermes"))
                assertTrue(validResponse.contains("Sign-in will finish securely in the app."))
                assertTrue(validResponse.contains("Cache-Control: no-store"))
                assertTrue(validResponse.contains("Referrer-Policy: no-referrer"))
                assertTrue(validResponse.contains("history.replaceState(null,\"\",\"/complete\")"))
                assertFalse(validResponse.contains("gateway-code"))
                assertFalse(validResponse.contains(state))
            },
        )

        assertEquals("gateway-code", exchanger.code)
        assertEquals("opaque-access", tokens.accessToken)
    }

    @Test
    fun nativeLoginBoundsRequestLineBeforeContinuingToValidCallback() = runBlocking {
        val exchanger = FakeNativeTokenExchanger()
        val login = HermesNativeLogin(
            exchanger = exchanger,
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )

        login.signIn(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            openBrowser = { authorizeUrl ->
                val authorize = Url(authorizeUrl)
                val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                val state = requireNotNull(authorize.parameters["state"])
                val oversizedTarget = "/callback?code=${"x".repeat(10_000)}&state=$state"
                val oversizedResponse = sendCallbackRequest(
                    redirect,
                    "GET $oversizedTarget HTTP/1.1\r\n",
                )
                assertTrue(oversizedResponse.startsWith("HTTP/1.1 400"))
                assertFalse(oversizedResponse.contains("Signed in to Hermes"))

                val validResponse = sendCallbackRequest(
                    redirect,
                    "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
                )
                assertTrue(validResponse.startsWith("HTTP/1.1 200"))
            },
        )

        assertEquals("gateway-code", exchanger.code)
    }

    @Test
    fun nativeLoginTimesOutAStalledRequestAndContinuesToValidCallback() = runBlocking {
        val exchanger = FakeNativeTokenExchanger()
        val login = HermesNativeLogin(
            exchanger = exchanger,
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )

        login.signIn(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            openBrowser = { authorizeUrl ->
                val authorize = Url(authorizeUrl)
                val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                val state = requireNotNull(authorize.parameters["state"])
                Socket(redirect.host, redirect.port).use { socket ->
                    socket.soTimeout = 3_000
                    socket.getOutputStream().apply {
                        write("GET ${redirect.path}?code=slow&state=$state HTTP/1.1".toByteArray())
                        flush()
                    }
                    assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 400"))
                }

                val validResponse = sendCallbackRequest(
                    redirect,
                    "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
                )
                assertTrue(validResponse.startsWith("HTTP/1.1 200"))
            },
        )

        assertEquals("gateway-code", exchanger.code)
    }

    @Test
    fun nativeLoginIgnoresClientDisconnectBeforeRequestLine() = runBlocking {
        val exchanger = FakeNativeTokenExchanger()
        val login = HermesNativeLogin(
            exchanger = exchanger,
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )

        login.signIn(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            provider = "nous",
            openBrowser = { authorizeUrl ->
                val authorize = Url(authorizeUrl)
                val redirect = URI(requireNotNull(authorize.parameters["redirect_uri"]))
                Socket(redirect.host, redirect.port).use { socket ->
                    socket.getOutputStream().apply {
                        write("GET /callback".toByteArray())
                        flush()
                    }
                }

                val state = requireNotNull(authorize.parameters["state"])
                val validResponse = sendCallbackRequest(
                    redirect,
                    "GET ${redirect.path}?code=gateway-code&state=$state HTTP/1.1\r\n",
                )
                assertTrue(validResponse.startsWith("HTTP/1.1 200"))
            },
        )

        assertEquals("gateway-code", exchanger.code)
    }

    @Test
    fun cancellingNativeLoginClosesLoopbackListenerPromptly() = runBlocking {
        val authorizeUrl = CompletableDeferred<String>()
        val login = HermesNativeLogin(
            exchanger = FakeNativeTokenExchanger(),
            randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
        )
        val job = launch {
            login.signIn(
                serverOrigin = ServerOrigin.parse("https://hermes.example"),
                provider = "nous",
                openBrowser = { authorizeUrl.complete(it) },
            )
        }

        val redirect = URI(requireNotNull(Url(authorizeUrl.await()).parameters["redirect_uri"]))
        val startedAt = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("listener cancellation took ${elapsedMillis}ms", elapsedMillis < 1_000)
        val socket = Socket()
        val connection = runCatching {
            socket.connect(InetSocketAddress(redirect.host, redirect.port), 250)
        }
        socket.close()
        assertTrue(connection.isFailure)
        assertTrue(job.isCancelled)
    }
}

private fun sendCallbackRequest(redirect: URI, request: String): String =
    Socket(redirect.host, redirect.port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().bufferedWriter().apply {
            write(request)
            write("Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
            flush()
        }
        socket.getInputStream().bufferedReader().readText()
    }

private class FakeNativeTokenExchanger : NativeTokenExchanger {
    var code = ""
    var verifier = ""

    override suspend fun exchange(
        serverOrigin: ServerOrigin,
        code: String,
        verifier: String,
    ): NativeTokenSet {
        this.code = code
        this.verifier = verifier
        return NativeTokenSet(
            accessToken = "opaque-access",
            refreshToken = "opaque-refresh",
            expiresAt = 2_000_000_000,
            provider = "nous",
            userId = "user",
        )
    }
}
