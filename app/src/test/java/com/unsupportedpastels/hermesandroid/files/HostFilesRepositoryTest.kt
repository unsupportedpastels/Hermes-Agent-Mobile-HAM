package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFilesRepositoryTest {
    @Test
    fun browseUpRefreshAndFilterStayMetadataOnly() = runTest {
        val transport = RecordingHostFilesTransport()
        val repository = HostFilesRepository(transport, testScope)

        val first = repository.browse("/srv")
        assertEquals("/srv", first.path)
        assertEquals(listOf("docs", "notes.txt"), repository.filteredEntries().map { it.name })
        assertEquals("/srv", transport.calls.single().path)

        repository.setFilter("notes")
        assertEquals(listOf("notes.txt"), repository.filteredEntries().map { it.name })
        repository.up()
        assertEquals("/", repository.state.value.listing?.path)
        repository.refresh()
        assertEquals(3, transport.calls.size)
        assertFalse(repository.state.value.contentsPersisted)
    }

    @Test
    fun staleBrowseCannotPublishAfterOriginOrProfileScopeChanges() = runTest {
        val response = CompletableDeferred<HostFileListing>()
        val transport = RecordingHostFilesTransport(response)
        val repository = HostFilesRepository(transport, testScope)
        val browse = backgroundScope.async { repository.browse("/srv") }

        transport.started.await()
        repository.updateScope(
            HostFileScope(ServerOrigin.parse("https://other.example"), "work"),
        )
        response.complete(transport.listing("/stale"))

        assertFailsCancellation { browse.await() }
        assertNull(repository.state.value.listing)
        assertEquals("https://other.example", repository.state.value.scope?.origin?.value)
    }

    @Test
    fun scopeChangeAlsoInvalidatesProfileAndDoesNotStoreReadBytes() = runTest {
        val transport = RecordingHostFilesTransport()
        val repository = HostFilesRepository(transport, testScope)
        repository.browse("/srv")
        val content = repository.read(HostFileEntry("notes.txt", "/srv/notes.txt", false, 5))

        assertEquals("hello", String(content.bytes))
        assertFalse(repository.state.value.contentsPersisted)
        repository.updateScope(testScope.copy(profile = "default"))
        assertNull(repository.state.value.listing)
    }
}

private suspend fun assertFailsCancellation(block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected cancellation")
    } catch (_: CancellationException) {
        // expected
    }
}

private class RecordingHostFilesTransport(
    private val deferred: CompletableDeferred<HostFileListing>? = null,
) : HostFilesTransport {
    data class Call(val path: String?, val profile: String)

    val calls = mutableListOf<Call>()
    val started = CompletableDeferred<Unit>()

    override suspend fun list(scope: HostFileScope, path: String?): HostFileListing {
        calls += Call(path, scope.profile)
        started.complete(Unit)
        return deferred?.await() ?: listing(path ?: "/")
    }

    override suspend fun read(scope: HostFileScope, entry: HostFileEntry): HostFileContent =
        HostFileContent(entry.name, entry.path, entry.mimeType ?: "text/plain", "hello".encodeToByteArray())

    override suspend fun download(scope: HostFileScope, entry: HostFileEntry): HostFileContent =
        read(scope, entry)

    fun listing(path: String): HostFileListing = HostFileListing(
        path = path,
        parentPath = if (path == "/") null else "/",
        entries = if (path == "/") emptyList() else listOf(
            HostFileEntry("docs", "/srv/docs", true),
            HostFileEntry("notes.txt", "/srv/notes.txt", false, 5, "text/plain"),
        ),
        root = "/",
        lockedRoot = null,
        canChangePath = true,
    )
}

private val testScope = HostFileScope(ServerOrigin.parse("https://hermes.example"), "work")
