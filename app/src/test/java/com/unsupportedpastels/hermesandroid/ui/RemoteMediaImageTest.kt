package com.unsupportedpastels.hermesandroid.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaImageTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpsMediaUrls() {
        assertTrue(validateRemoteMediaUrl("https://cdn.example/image.png"))
        assertFalse(validateRemoteMediaUrl("http://cdn.example/image.png"))
        assertFalse(validateRemoteMediaUrl("https://user:secret@cdn.example/image.png"))
        assertFalse(validateRemoteMediaUrl("https://cdn.example:8443/image.png"))
        assertFalse(validateRemoteMediaUrl("https://127.0.0.1/image.png"))
        assertFalse(validateRemoteMediaUrl("https://localhost/image.png"))
    }

    @Test
    fun acceptsImageHostPathsButRejectsNonImageAndMalformedPaths() {
        assertTrue(validateGatewayMediaPath("/home/mark/project/generated.jpg"))
        assertTrue(validateGatewayMediaPath("/home/mark/project/generated.PNG"))
        assertFalse(validateGatewayMediaPath("relative/generated.jpg"))
        assertFalse(validateGatewayMediaPath("/home/mark/project/notes.txt"))
        assertFalse(validateGatewayMediaPath("/home/mark/project/no-extension"))
    }

    @Test
    fun oversizedImageBodyIsRejectedBeforeDecode() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = ByteArray(2_049),
                headers = headersOf(HttpHeaders.ContentType, "image/png"),
            )
        }) { configureRemoteImageHttpClient() }

        val result = RemoteImageDownloader(client, maxBytes = 2_048)
            .download("https://cdn.example/image.png")

        assertTrue(result is RemoteImageDownloadResult.TooLarge)
        client.close()
    }

    @Test
    fun redirectIsNotFollowed() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests += 1
            respond(
                content = ByteArray(0),
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/image.png"),
            )
        }) { configureRemoteImageHttpClient() }

        val result = RemoteImageDownloader(client).download("https://cdn.example/image.png")

        assertTrue(result is RemoteImageDownloadResult.HttpFailure)
        assertEquals(302, (result as RemoteImageDownloadResult.HttpFailure).statusCode)
        assertEquals(1, requests)
        client.close()
    }
}
