package app.citti.core.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.daily.DailyIndex
import app.citti.core.daily.LocaleDetector
import app.citti.core.daily.TemplateRenderer
import app.citti.core.daily.dailyDate
import app.citti.core.db.CittiDatabase
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.PullResult
import app.citti.core.sync.PullSync
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import okhttp3.Interceptor
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Daily notes benchmark (M6 spec §11) on a file DB. Skipped unless `CITTI_BENCH=1`.
 *
 * The DB is a copy of `CITTI_BENCH_DB` when set (e.g. `citti.db` pulled from the phone), otherwise a fresh
 * read-only pull of the real server (as in [SearchBenchmark]); every write goes to the copy. The Daily notes
 * settings are the DB's (defaults on a fresh pull: `journal`, `templates/daily.md`, `YYYY-MM-DD`). It times, [RUNS]
 * times each: the `DailyIndex` first emission over the Journal folder (the `selectJournalNotes` query and the
 * parse), the title parse alone, locale detection once, and `TemplateRenderer.render` on the Template for 366
 * consecutive dates. The Template is the local Note, else the cached text, else the vault's copy of
 * `templates/daily.md` as of M6. Prints only counts and timings (never note content); the table goes to stdout and
 * to `CITTI_BENCH_OUT` (default: a temp file).
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_BENCH=1 ./gradlew :core:jvmTest --tests '*DailyBenchmark*' -i
 * ```
 */
class DailyBenchmark {
    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private fun withDb(block: (CittiDatabase) -> Unit) {
        val dir = createTempDirectory("citti-daily-bench").toFile()
        try {
            val file = File(dir, "bench.db")
            val source = System.getenv("CITTI_BENCH_DB")
            if (source != null) File(source).copyTo(file)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val db = CittiDatabase(driver)
            if (source == null) {
                CittiDatabase.Schema.create(driver)
                pull(db)
            }
            block(db)
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

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

    private fun pct(
        sorted: List<Double>,
        p: Double,
    ): Double = sorted[((sorted.size - 1) * p).toInt()]

    private inline fun timeRuns(
        runs: Int,
        block: () -> Unit,
    ): List<Double> {
        block() // warm-up
        return List(runs) {
            val t = System.nanoTime()
            block()
            ms(t)
        }.sorted()
    }

    private fun StringBuilder.row(
        what: String,
        sorted: List<Double>,
    ) = appendLine("| %s | %d | %.3f | %.3f |".format(Locale.ROOT, what, sorted.size, pct(sorted, 0.5), pct(sorted, 0.95)))

    @Test
    fun benchmark() {
        assumeTrue("set CITTI_BENCH=1 to run the benchmark", System.getenv("CITTI_BENCH") == "1")
        val sb = StringBuilder()
        withDb { db ->
            val settings = DailySettings(db, ExclusionRepository(db, Dispatchers.Unconfined), Dispatchers.Unconfined)
            val config = settings.config()
            val locales = LocaleDetector(db, settings) { "en" }
            val index = DailyIndex(db, locales, Dispatchers.Unconfined)
            val rows = db.noteQueries.selectJournalNotes(config.folder).executeAsList()
            val reading = locales.readingLocales(config)
            val entries = runBlocking { index.flow(config).first() }
            val template = templateOf(db, settings, config.template)

            sb.appendLine("# Daily notes benchmark")
            sb.appendLine()
            sb.appendLine(
                "Journal folder: ${rows.size} notes, ${entries.size} Daily notes. Date format `${config.format}`, " +
                    "${reading.size} reading Date locales. Template: ${template.second}, ${template.first.length} chars.",
            )
            sb.appendLine()
            sb.appendLine("| Operation | Runs | p50 (ms) | p95 (ms) |")
            sb.appendLine("|---|---|---|---|")
            sb.row(
                "DailyIndex.flow first emission (query + parse)",
                timeRuns(RUNS) { runBlocking { index.flow(config).first() } },
            )
            sb.row(
                "Title parse only, ${rows.size} titles",
                timeRuns(RUNS) { rows.forEach { dailyDate(it.title, config.format, reading) } },
            )
            val t = System.nanoTime()
            val detected = locales.detect(config)
            sb.row("LocaleDetector.detect, once (${if (detected == null) "no decision" else "decided"})", listOf(ms(t)))

            val locale = locales.localeForRendering(config)
            val start = LocalDate(2026, 1, 1)
            val renders =
                (0 until DATES).map { i ->
                    val date = start.plus(i, DateTimeUnit.DAY)
                    val t0 = System.nanoTime()
                    TemplateRenderer.render(template.first, date, LocalTime(9, 30), config.format, locale)
                    ms(t0)
                }
            sb.row("TemplateRenderer.render, $DATES dates (first one cold)", renders.sorted())
            sb.row(
                "TemplateRenderer.render, warm",
                timeRuns(RUNS) { TemplateRenderer.render(template.first, start, LocalTime(9, 30), config.format, locale) },
            )
        }
        val table = sb.toString()
        println(table)
        val out = System.getenv("CITTI_BENCH_OUT")?.let(::File) ?: File.createTempFile("citti-daily-bench-", ".md")
        out.writeText(table)
        println("Results written to ${out.absolutePath}")
    }

    /** The Template text at [path] and where it came from. */
    private fun templateOf(
        db: CittiDatabase,
        settings: DailySettings,
        path: String,
    ): Pair<String, String> {
        val name = path.removeSuffix(".md")
        val local =
            db.noteQueries
                .selectExactByFolderAndTitle(name.substringBeforeLast('/', ""), name.substringAfterLast('/'))
                .executeAsOneOrNull()
                ?.let {
                    db.noteQueries
                        .selectByLocalId(it)
                        .executeAsOneOrNull()
                        ?.content
                }
        if (local != null) return local to "local Note"
        settings.templateCache()?.takeIf { it.path == path }?.let { return it.text to "cached" }
        return VAULT_DAILY to "built-in copy (not local)"
    }

    private companion object {
        const val RUNS = 50
        const val DATES = 366

        /** The vault's templates/daily.md as of M6, as in `TemplateRendererTest`. */
        const val VAULT_DAILY =
            "# {{title}}  [[journal/{{date:YYYY}}-W{{date:ww}}|Week {{date:ww}}]] | " +
                "[[journal/{{date:YYYY}}-{{date:MMMM}}|{{date:MMMM}}]] | " +
                "[[journal/{{date:YYYY}}-Q{{date:Q}}|Q{{date:Q}}]] | " +
                "[[journal/{{date:YYYY}}|{{date:YYYY}}]]\n" +
                "\n---\n\n\n\n---\n\n"
    }
}
