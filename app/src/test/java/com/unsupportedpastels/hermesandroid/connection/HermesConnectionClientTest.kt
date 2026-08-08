package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.unsupportedpastels.hermesandroid.app.DurableSessionId

class HermesConnectionClientTest {
    @Test
    fun productionHttpConfigurationRejectsRedirects() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/api/status"),
            )
        }
        val client = HttpClient(engine) { configureHermesHttpClient() }

        val response = client.get("https://hermes.example/api/status")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, requestCount)
        client.close()
    }

    @Test
    fun probeRejectsStatusMissingRequiredAuthRequiredField() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"version":"0.20.0"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeRejectsProvidersResponseMissingEnvelope() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":true}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respond(
                    content = "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeRejectsOversizedResponseBody() = runTest {
        val oversizedBody = """{"auth_required":false,"padding":"${"x".repeat(70_000)}"}"""
        val engine = MockEngine {
            respond(
                content = oversizedBody,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeLoadsDurableSessionsWhenAuthenticationIsNotRequired() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[{"session_key":"stored-1","title":"First session"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val result = client.probe(ServerOrigin.parse("https://hermes.example"))

        assertEquals(listOf("/api/status", "/api/profiles/sessions"), requestedPaths)
        assertFalse(result.authRequired)
        assertEquals(listOf("First session"), result.sessions.map { it.title })
    }

    @Test
    fun probeDiscoversReachableGatedServerAndNativeNousLogin() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{
                        "version":"0.20.0",
                        "auth_required":true,
                        "auth_flows":["native_pkce"],
                        "future_field":{"ignored":true}
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respond(
                    content = """{
                        "providers":[{
                            "name":"nous",
                            "display_name":"Nous Research",
                            "supports_password":false,
                            "future_field":"ignored"
                        }]
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val result = client.probe(ServerOrigin.parse("https://hermes.example"))

        assertEquals(listOf("/api/status", "/api/auth/providers"), requestedPaths)
        assertEquals("0.20.0", result.version)
        assertTrue(result.authRequired)
        assertTrue(result.nativeOAuthSupported)
        assertEquals(listOf("nous"), result.providers.map { it.name })
    }

    @Test
    fun probeDoesNotPresentFailedProviderDiscoveryAsConnected() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":true}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respondError(HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun authenticatedConnectionVerifiesBearerAndLoadsDurableSessions() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath == "/api/profiles/sessions") {
                assertEquals("20", request.url.parameters["limit"])
            }
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user","provider":"nous"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{
                        "sessions":[
                            {"session_key":"stored-1","title":"First session"},
                            {"session_key":"stored-2","title":""},
                            {"session_key":"stored-1","title":"Duplicate aggregate row"}
                        ],
                        "future_field":"ignored"
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(listOf("/api/auth/me", "/api/profiles/sessions"), requestedPaths)
        assertEquals("user", authenticated.userId)
        assertEquals(
            listOf(
                DurableSessionId("stored-1") to "First session",
                DurableSessionId("stored-2") to "Untitled session",
            ),
            authenticated.sessions.map { it.id to it.title },
        )
    }

    @Test
    fun authenticatedConnectionReadsMultiChunkSessionResponseWithinLimit() = runTest {
        val largeTitle = "t".repeat(40_000)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[{"session_key":"stored-1","title":"$largeTitle"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(largeTitle, authenticated.sessions.single().title)
    }

    @Test
    fun authenticatedConnectionEnforcesTwentySessionLimitOnServerResponse() = runTest {
        val rows = (1..25).joinToString(",") { index ->
            "{\"session_key\":\"stored-$index\",\"title\":\"Session $index\"}"
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[$rows]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(20, authenticated.sessions.size)
        assertEquals("stored-20", authenticated.sessions.last().id.value)
    }

    @Test
    fun authenticatedConnectionRejectsBlankUserId() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"   "}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun authenticatedConnectionRejectsSessionsResponseMissingEnvelope() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }
}
