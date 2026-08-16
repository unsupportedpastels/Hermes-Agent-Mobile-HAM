package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationalStatusClientTest {
    @Test
    fun statusRequestCarriesSelectedProfileAndParsesOfficialShape() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/status", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            respond(
                """{"version":"0.20.1","overall":"ok","components":{"gateway":{"status":"ok"}},"memory":{"pressure":"ok"},"disk":{"pressure":"critical"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val status = HttpHermesConnectionClient(HttpClient(engine)).loadOperationalStatus(
            ServerOrigin.parse("https://hermes.example"),
            profile = "work",
        )

        assertEquals("work", status.profile)
        assertEquals("0.20.1", status.version)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.OperationalPressure.Critical, status.diskPressure)
    }
}
