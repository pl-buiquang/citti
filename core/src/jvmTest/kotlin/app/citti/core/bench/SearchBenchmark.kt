package app.citti.core.bench

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.search.NoteSearch
import app.citti.core.search.toFtsMatch
import app.citti.core.sync.PullResult
import app.citti.core.sync.PullSync
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Search benchmark (M3 spec §11) on a file DB. Skipped unless `CITTI_BENCH=1`.
 *
 * The DB is a copy of `CITTI_BENCH_DB` when set (e.g. `citti.db` pulled from the phone), otherwise a fresh
 * read-only pull of the real server (`NEXTCLOUD_URL`, `NEXTCLOUD_USER`, `NEXTCLOUD_PASSWORD`, as in
 * [SyncBenchmark]). It times the FTS `rebuild`, then each query of `CITTI_BENCH_QUERIES` (`;`-separated,
 * default: 20 typical ones: one character, one word, two words, a phrase, accented words) as `hits()` plus the
 * first snippet page, [RUNS] times each. Prints only counts and timings (never note content); the Markdown table
 * goes to stdout and to `CITTI_BENCH_OUT` (default: a temp file).
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_BENCH=1 ./gradlew :core:jvmTest --tests '*SearchBenchmark*' -i
 * ```
 */
class SearchBenchmark {
    private class Timing(
        val query: String,
        val hits: Int,
        val millis: List<Double>,
    )

    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private fun withDb(block: (SqlDriver) -> Unit) {
        val dir = createTempDirectory("citti-search-bench").toFile()
        try {
            val file = File(dir, "bench.db")
            val source = System.getenv("CITTI_BENCH_DB")
            if (source != null) File(source).copyTo(file)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            if (source == null) {
                CittiDatabase.Schema.create(driver)
                pull(CittiDatabase(driver))
            }
            block(driver)
            driver.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pull(db: CittiDatabase) {
        val cfg = NotesApiConfig(env("NEXTCLOUD_URL"), env("NEXTCLOUD_USER"), env("NEXTCLOUD_PASSWORD"))
        val readOnly =
            Interceptor { chain ->
                check(chain.request().method == "GET") { "benchmark is read-only, refusing ${chain.request().method}" }
                chain.proceed(chain.request())
            }
        val client = createNotesHttpClient(OkHttp.create { addNetworkInterceptor(readOnly) }, cfg)
        try {
            val result = runBlocking { PullSync(NotesApi(client, cfg), db).pull() }
            check(result is PullResult.Pulled) { "pull did not complete: ${result::class.simpleName}" }
        } finally {
            client.close()
        }
    }

    private fun count(
        driver: SqlDriver,
        sql: String,
    ): Long = driver.executeQuery(null, sql, { c -> QueryResult.Value(c.next().value.let { c.getLong(0)!! }) }, 0).value

    private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000.0

    private fun percentile(
        sorted: List<Double>,
        p: Double,
    ): Double = sorted[((sorted.size - 1) * p).toInt()]

    @Test
    fun benchmark() {
        assumeTrue("set CITTI_BENCH=1 to run the benchmark", System.getenv("CITTI_BENCH") == "1")
        val queries =
            System
                .getenv("CITTI_BENCH_QUERIES")
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: DEFAULT_QUERIES
        val sb = StringBuilder()
        withDb { driver ->
            val notes = count(driver, "SELECT count(*) FROM note")
            val start = System.nanoTime()
            driver.execute(null, "INSERT INTO note_fts(note_fts) VALUES ('rebuild')", 0)
            val rebuildMs = millisSince(start)
            val pages = count(driver, "PRAGMA page_count")
            val pageSize = count(driver, "PRAGMA page_size")
            val search = NoteSearch(driver, Dispatchers.Unconfined)

            val timings =
                queries.map { raw ->
                    val fts = checkNotNull(toFtsMatch(raw)) { "query #${queries.indexOf(raw) + 1} has nothing to search" }
                    var hits = 0
                    // One warm-up run, then RUNS timed ones.
                    val millis =
                        (0..RUNS).map {
                            val t = System.nanoTime()
                            runBlocking {
                                val found = search.hits(fts).first()
                                search.snippets(fts, found.take(PAGE).map { it.localId })
                                hits = found.size
                            }
                            millisSince(t)
                        }
                    Timing(raw, hits, millis.drop(1))
                }

            val all = timings.flatMap { it.millis }.sorted()
            sb.appendLine("# Search benchmark")
            sb.appendLine()
            sb.appendLine(
                "Notes: $notes. DB size: %.1f MB. FTS rebuild: %.0f ms.".format(Locale.ROOT, pages * pageSize / 1e6, rebuildMs),
            )
            sb.appendLine(
                "hits() + first snippet page, $RUNS runs per query: p50 %.1f ms, p95 %.1f ms.".format(
                    Locale.ROOT,
                    percentile(all, 0.50),
                    percentile(all, 0.95),
                ),
            )
            sb.appendLine()
            sb.appendLine("| # | Kind | Hits | p50 (ms) | max (ms) |")
            sb.appendLine("|---|---|---|---|---|")
            timings.forEachIndexed { i, t ->
                val sorted = t.millis.sorted()
                sb.appendLine(
                    "| %d | %s | %d | %.1f | %.1f |".format(
                        Locale.ROOT,
                        i + 1,
                        kindOf(t.query),
                        t.hits,
                        percentile(sorted, 0.5),
                        sorted.last(),
                    ),
                )
            }
        }
        val table = sb.toString()
        println(table)
        val out = System.getenv("CITTI_BENCH_OUT")?.let(::File) ?: File.createTempFile("citti-search-bench-", ".md")
        out.writeText(table)
        println("Results written to ${out.absolutePath}")
    }

    /** The shape of a query, printed instead of the query itself (it may come from the user's notes). */
    private fun kindOf(raw: String): String {
        val fts = toFtsMatch(raw)
        val accented = raw.any { it.code > 127 }
        return when {
            fts == null -> "none"
            fts.titleOnly -> "one char"
            raw.contains('"') -> "phrase"
            fts.phrases == 1 -> if (accented) "one word, accented" else "one word"
            else -> "${fts.phrases} words"
        }
    }

    private companion object {
        const val RUNS = 5
        const val PAGE = 50

        val DEFAULT_QUERIES =
            listOf(
                "a",
                "j",
                "2",
                "note",
                "sync",
                "projet",
                "journal",
                "todo",
                "ete",
                "été",
                "réunion",
                "idée",
                "café",
                "projet client",
                "notes app",
                "liste courses",
                "réunion équipe",
                "\"to do\"",
                "\"de la\"",
                "\"pour le\"",
            )
    }
}
