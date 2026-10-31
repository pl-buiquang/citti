package app.citti.core.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.sync.PullResult
import app.citti.core.sync.PullSync
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sync benchmark against a real Nextcloud. READ ONLY: only [PullSync] runs, and the counting interceptor
 * fails any request that is not a GET. Skipped unless `CITTI_BENCH=1`.
 *
 * Reads `NEXTCLOUD_URL`, `NEXTCLOUD_USER`, `NEXTCLOUD_PASSWORD`; prints only counts, timings and bytes (never
 * credentials or note content). The Markdown table goes to stdout and to `CITTI_BENCH_OUT` (default: a temp file).
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_BENCH=1 ./gradlew :core:jvmTest --tests '*SyncBenchmark*' -i
 * ```
 */
class SyncBenchmark {
    private class Stats {
        val requests = AtomicInteger()
        val listRequests = AtomicInteger()
        val noteGets = AtomicInteger()
        val status304 = AtomicInteger()
        val status200 = AtomicInteger()
        val other = AtomicInteger()
        val gzip = AtomicInteger()
        val wireBytes = AtomicLong()
        val firstNoteGetAtNanos = AtomicLong(0)
        val slowestMs = AtomicLong()
    }

    private class Run(
        val name: String,
        val millis: Long,
        val metadataMillis: Long?,
        val stats: Stats,
        val note: String,
    )

    private val cfg: NotesApiConfig by lazy {
        NotesApiConfig(env("NEXTCLOUD_URL"), env("NEXTCLOUD_USER"), env("NEXTCLOUD_PASSWORD"))
    }

    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private fun counting(stats: Stats) =
        Interceptor { chain ->
            val request = chain.request()
            check(request.method == "GET") { "benchmark is read-only, refusing ${request.method}" }
            if (request.url.encodedPath.endsWith("/notes")) {
                stats.listRequests.incrementAndGet()
            } else {
                stats.noteGets.incrementAndGet()
                stats.firstNoteGetAtNanos.compareAndSet(0, System.nanoTime())
            }
            stats.requests.incrementAndGet()
            val started = System.nanoTime()
            val response = chain.proceed(request)
            when (response.code) {
                304 -> stats.status304.incrementAndGet()
                200 -> stats.status200.incrementAndGet()
                else -> stats.other.incrementAndGet()
            }
            if (response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) stats.gzip.incrementAndGet()
            // Count the bytes as they come off the wire (compressed when gzip), headers excluded.
            val body = response.body
            val counted =
                object : ForwardingSource(body.source()) {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        val n = super.read(sink, byteCount)
                        if (n > 0) stats.wireBytes.addAndGet(n)
                        return n
                    }

                    override fun close() {
                        val ms = (System.nanoTime() - started) / 1_000_000
                        stats.slowestMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
                        super.close()
                    }
                }
            response.newBuilder().body(counted.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }

    private fun withDb(block: (CittiDatabase) -> Unit) {
        val dir = createTempDirectory("citti-bench").toFile()
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "bench.db").absolutePath}")
            CittiDatabase.Schema.create(driver)
            block(CittiDatabase(driver))
            driver.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** One timed pull on [db]; every call builds its own client so the counters are per scenario. */
    private fun timedPull(
        name: String,
        db: CittiDatabase,
        parallelism: Int = PullSync.DEFAULT_PARALLELISM,
    ): Run {
        val stats = Stats()
        val client = createNotesHttpClient(OkHttp.create { addNetworkInterceptor(counting(stats)) }, cfg)
        try {
            val api = NotesApi(client, cfg)
            val start = System.nanoTime()
            val result = runBlocking { PullSync(api, db, 500, parallelism).pull() }
            val end = System.nanoTime()
            val first = stats.firstNoteGetAtNanos.get()
            val note =
                when (result) {
                    PullResult.NothingChanged -> "nothing changed"
                    is PullResult.Pulled -> "content fetched ${result.contentFetched}, failures ${result.failures.size}"
                    is PullResult.Failed -> "FAILED ${result.error::class.simpleName}"
                }
            return Run(name, (end - start) / 1_000_000, if (first > 0) (first - start) / 1_000_000 else null, stats, note)
        } finally {
            client.close()
        }
    }

    @Test
    fun benchmark() {
        assumeTrue("set CITTI_BENCH=1 to run the benchmark", System.getenv("CITTI_BENCH") == "1")
        val runs = mutableListOf<Run>()
        var totalNotes = 0L
        var excludedGets = -1

        // (1) first full sync, (2) immediate 304, (3) forced 200 plain-list reconcile.
        withDb { db ->
            runs += timedPull("1. first full sync (chunk 500, par 8)", db)
            totalNotes = db.noteQueries.countNotes().executeAsOne()
            runs += timedPull("2. immediate second sync", db)
            check(
                runs
                    .last()
                    .stats.requests
                    .get() == 1 && runs
                    .last()
                    .stats.status304
                    .get() == 1,
            ) {
                "second sync was not exactly one 304"
            }
            db.syncStateQueries.clear(PullSync.KEY_LIST_PLAIN)
            runs += timedPull("3. forced 200 (ETag cleared, no content)", db)
        }

        // (4) Unsynced folder `_work`.
        withDb { db ->
            db.settingQueries.insertExclusion("_work", "UNSYNCED")
            val run = timedPull("4. first sync, Unsynced `_work`", db)
            runs += run
            val kept = db.noteQueries.countNotes().executeAsOne()
            excludedGets = runs
                .first()
                .stats.noteGets
                .get() - run.stats.noteGets.get()
            assertEquals(kept.toInt(), run.stats.noteGets.get(), "one GET per kept Note, none for excluded ones")
            assertEquals(0L, db.noteQueries.countUnderPrefix("_work").executeAsOne())
        }

        // (5) parallelism sweep on fresh DBs.
        for (p in listOf(4, 8, 16)) {
            withDb { db -> runs += timedPull("5. parallelism $p", db, parallelism = p) }
        }

        val sb = StringBuilder()
        sb.appendLine("# Sync benchmark (real server, read only)")
        sb.appendLine()
        sb.appendLine("Notes after scenario 1: $totalNotes. Note GETs saved by excluding `_work`: $excludedGets.")
        sb.appendLine()
        sb.appendLine(
            "| Scenario | Time (s) | Metadata (s) | Requests | List | Note GETs | 304 | 200 | other | gzip | Wire KB | Slowest (ms) | Result |",
        )
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in runs) {
            val s = r.stats
            sb.appendLine(
                "| ${r.name} | %.2f | %s | %d | %d | %d | %d | %d | %d | %d | %.1f | %d | %s |".format(
                    java.util.Locale.ROOT,
                    r.millis / 1000.0,
                    r.metadataMillis?.let { "%.2f".format(java.util.Locale.ROOT, it / 1000.0) } ?: "-",
                    s.requests.get(),
                    s.listRequests.get(),
                    s.noteGets.get(),
                    s.status304.get(),
                    s.status200.get(),
                    s.other.get(),
                    s.gzip.get(),
                    s.wireBytes.get() / 1024.0,
                    s.slowestMs.get(),
                    r.note,
                ),
            )
        }
        sb.appendLine()
        sb.appendLine("Metadata = time until the first Note content GET started (chunked pages plus the plain list).")
        val table = sb.toString()
        println(table)
        val out = System.getenv("CITTI_BENCH_OUT")?.let(::File) ?: File.createTempFile("citti-bench-", ".md")
        out.writeText(table)
        println("Results written to ${out.absolutePath}")
    }
}
