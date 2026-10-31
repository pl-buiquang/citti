package app.citti.core.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Request count and response bytes (Content-Length when the server sends it), for the sync summary line. */
class RequestStats {
    private val lock = Mutex()
    private var requests = 0
    private var bytes = 0L

    data class Snapshot(
        val requests: Int,
        val bytes: Long,
    )

    suspend fun record(responseBytes: Long) =
        lock.withLock {
            requests++
            bytes += responseBytes
        }

    suspend fun snapshot(): Snapshot = lock.withLock { Snapshot(requests, bytes) }
}
