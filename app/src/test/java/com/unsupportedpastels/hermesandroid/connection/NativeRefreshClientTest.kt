package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRefreshClientTest {
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val refreshToken = "old-refresh-token"

    @Test
    fun refreshPostsBoundedJsonAndParsesTheTokenSet() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            assertEquals("/auth/native/refresh", request.url.encodedPath)
            requestBody = (request.body as TextContent).text
            respond(
                content = """
                    {
                        "access_token":"new-access",
                        "refresh_token":"new-refresh",
                        "expires_at":2000000000,
                        "provider":"nous",
                        "user_id":"user-1"
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = client(engine)

        val tokens = client.refresh(origin, refreshToken, provider = "nous")

        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals(refreshToken, body.getValue("refresh_token").toString().trim('"'))
        assertEquals("nous", body.getValue("provider").toString().trim('"'))
        assertEquals("new-access", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
        assertEquals("nous", tokens.provider)
        assertEquals("user-1", tokens.userId)
    }

    @Test
    fun unauthorizedRefreshIsClassifiedAsExpiredWithoutEchoingToken() = runTest {
        val client = client(MockEngine {
            respond(
                content = "server says $refreshToken is expired",
                status = HttpStatusCode.Unauthorized,
            )
        })

        val failure = runCatching { client.refresh(origin, refreshToken, "nous") }
            .exceptionOrNull()

        assertTrue(failure is NativeRefreshExpiredException)
        assertFalse(failureMessage(failure).contains(refreshToken))
    }

    @Test
    fun unavailableProviderIsClassifiedAsTransientWithoutEchoingToken() = runTest {
        val client = client(MockEngine {
            respond(
                content = "provider unavailable for $refreshToken",
                status = HttpStatusCode.ServiceUnavailable,
            )
        })

        val failure = runCatching { client.refresh(origin, refreshToken, "nous") }
            .exceptionOrNull()

        assertTrue(failure is NativeRefreshTransientException)
        assertFalse(failureMessage(failure).contains(refreshToken))
    }

    @Test
    fun refreshRejectsIncompleteSuccessResponses() = runTest {
        val responses = listOf(
            """{"refresh_token":"new-refresh","expires_at":2000000000,"provider":"nous","user_id":"user-1"}""",
            """{"access_token":"new-access","expires_at":2000000000,"provider":"nous","user_id":"user-1"}""",
            """{"access_token":"new-access","refresh_token":"new-refresh","provider":"nous","user_id":"user-1"}""",
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":2000000000,"user_id":"user-1"}""",
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":2000000000,"provider":"nous"}""",
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":0,"provider":"nous","user_id":"user-1"}""",
        )

        for (responseBody in responses) {
            val failure = runCatching {
                client(MockEngine {
                    respond(
                        content = responseBody,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }).refresh(origin, refreshToken, "nous")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(failureMessage(failure).contains(refreshToken))
        }
    }

    @Test
    fun oversizedResponseIsRejectedByTheBoundedBodyReader() = runTest {
        val responseBody = """{"access_token":"new-access","padding":"${"x".repeat(70_000)}"}"""
        val failure = runCatching {
            client(MockEngine {
                respond(
                    content = responseBody,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }).refresh(origin, refreshToken, "nous")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(failureMessage(failure).contains(refreshToken))
    }

    @Test
    fun configuredHttpClientDoesNotFollowRefreshRedirects() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/auth/native/refresh"),
            )
        }
        val client = HttpHermesNativeRefreshClient(
            HttpClient(engine) { configureHermesHttpClient() },
        )

        val failure = runCatching { client.refresh(origin, refreshToken, "nous") }
            .exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, requestCount)
    }

    @Test
    fun blankRefreshInputsAreRejectedBeforeARequest() = runTest {
        var requestCount = 0
        val client = client(MockEngine {
            requestCount += 1
            respond(content = "{}")
        })

        val failure = runCatching { client.refresh(origin, "", "nous") }
            .exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0, requestCount)
    }

    private fun client(engine: MockEngine) = HttpHermesNativeRefreshClient(
        HttpClient(engine) { configureHermesHttpClient() },
    )

    private fun failureMessage(failure: Throwable?): String =
        failure?.message.orEmpty()
}
