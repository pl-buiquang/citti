package app.citti.e2e

import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeNote
import app.citti.core.fake.FakeNotesServer
import app.citti.core.fake.RecordedRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Assertions on the fake server's state and request log, in Glossary terms. */
class ServerAssert(
    private val server: FakeNotesServer = E2e.server,
) {
    fun note(
        title: String,
        category: String? = null,
    ): FakeNote? = server.notes.singleOrNull { it.title == title && (category == null || it.category == category) }

    fun assertNote(
        title: String,
        category: String,
        content: String? = null,
    ) {
        val n = assertNotNull(note(title, category), "no Note $title in '$category' on the server")
        if (content != null) assertEquals(content, n.content, "content of $title")
    }

    fun assertNoNote(
        title: String,
        category: String? = null,
    ) = assertNull(note(title, category), "Note $title is on the server")

    fun requests(
        endpoint: Endpoint,
        predicate: (RecordedRequest) -> Boolean = { true },
    ): List<RecordedRequest> = server.requests.filter { it.endpoint == endpoint && predicate(it) }

    fun assertRequests(
        endpoint: Endpoint,
        count: Int,
        predicate: (RecordedRequest) -> Boolean = { true },
    ) = assertEquals(count, requests(endpoint, predicate).size, "requests to $endpoint: ${server.requests.map { it.endpoint }}")

    fun assertNoRequests() = assertTrue(server.requests.isEmpty(), "requests: ${server.requests.map { "${it.endpoint} ${it.path}" }}")

    /** Waits until such a request was sent (it may already have been) and returns it. */
    fun awaitRequest(
        endpoint: Endpoint,
        timeout: Duration = 5.seconds,
        predicate: (RecordedRequest) -> Boolean = { true },
    ): RecordedRequest = runBlocking { server.awaitRequest(endpoint, timeout.inWholeMilliseconds, predicate) }
}
