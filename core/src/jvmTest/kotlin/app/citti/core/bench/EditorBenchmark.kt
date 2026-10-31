package app.citti.core.bench

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.markdown.LineLexer
import app.citti.core.markdown.LineState
import app.citti.core.markdown.ObsidianMarkdown
import app.citti.core.note.LinkResolver
import app.citti.core.note.NoteEditor
import app.citti.core.sync.PullResult
import app.citti.core.sync.PullSync
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test

/**
 * Editor benchmark (M4 spec §11) on a file DB. Skipped unless `CITTI_BENCH=1`.
 *
 * The DB is a copy of `CITTI_BENCH_DB` when set (e.g. `citti.db` pulled from the phone), otherwise a fresh
 * read-only pull of the real server (as in [SearchBenchmark]); every write goes to the copy. The "2.9k" and "9k"
 * Notes are the Notes whose line count is closest to 2,873 and the longest one. It times, per Note: `LineLexer`
 * over all lines, 1,000 single-character edits relexed the way the editor does (the edited line, then the
 * following lines until the state matches the previous one), `ObsidianMarkdown.parse`, and the editor `load()`.
 * On the 2.9k Note: `NoteEditor.save` (the `saveEdit` UPDATE with its FTS triggers and the Excerpt). Then
 * `LinkResolver.resolve` for every wikilink of the 20 most-linked Notes, split into plain and path-form targets
 * (path-form scans with `COLLATE NOCASE`). Prints only counts and timings (never note content); the table goes to
 * stdout and to `CITTI_BENCH_OUT` (default: a temp file).
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_BENCH=1 ./gradlew :core:jvmTest --tests '*EditorBenchmark*' -i
 * ```
 */
class EditorBenchmark {
    private class Source(
        val id: Long,
        val folder: String,
        val text: String,
        val lines: List<String>,
    )

    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private fun withDb(block: (CittiDatabase, SqlDriver) -> Unit) {
        val dir = createTempDirectory("citti-editor-bench").toFile()
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
            block(db, driver)
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

    private fun readSources(driver: SqlDriver): List<Source> =
        driver
            .executeQuery(
                null,
                "SELECT local_id, category, content FROM note WHERE content IS NOT NULL AND sync_state != 'DELETED'",
                { c ->
                    val out = ArrayList<Source>()
                    while (c.next().value) {
                        val text = c.getString(2)!!
                        out += Source(c.getLong(0)!!, c.getString(1)!!, text, text.split('\n'))
                    }
                    QueryResult.Value(out)
                },
                0,
            ).value

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

    private fun pct(
        sorted: List<Double>,
        p: Double,
    ): Double = sorted[((sorted.size - 1) * p).toInt()]

    private inline fun timeRuns(
        runs: Int,
        block: (Int) -> Unit,
    ): List<Double> {
        block(-1) // warm-up
        return List(runs) {
            val t = System.nanoTime()
            block(it)
            ms(t)
        }.sorted()
    }

    private fun StringBuilder.row(
        what: String,
        sorted: List<Double>,
    ) = appendLine("| %s | %d | %.3f | %.3f |".format(Locale.ROOT, what, sorted.size, pct(sorted, 0.5), pct(sorted, 0.95)))

    private fun lexAll(lines: List<String>): Array<LineState> {
        var state = LineLexer.start
        return Array(lines.size) { i ->
            LineLexer.lexLine(lines[i], state, i).endState.also { state = it }
        }
    }

    /** The editor's incremental relex after a character typed in line [at]: stop once the end state is unchanged. */
    private fun relex(
        lines: MutableList<String>,
        states: Array<LineState>,
        at: Int,
    ) {
        var i = at
        while (i < lines.size) {
            val before = if (i == 0) LineLexer.start else states[i - 1]
            val end = LineLexer.lexLine(lines[i], before, i).endState
            val same = end == states[i]
            states[i] = end
            if (same && i > at) break
            if (same && i == at) break
            i++
        }
    }

    @Test
    fun benchmark() {
        assumeTrue("set CITTI_BENCH=1 to run the benchmark", System.getenv("CITTI_BENCH") == "1")
        val sb = StringBuilder()
        withDb { db, driver ->
            val sources = readSources(driver)
            val longest = sources.maxBy { it.lines.size }
            val mid = sources.minBy { kotlin.math.abs(it.lines.size - 2873) }
            sb.appendLine("# Editor benchmark")
            sb.appendLine()
            sb.appendLine(
                "Notes with text: ${sources.size}. 2.9k-class Note: ${mid.lines.size} lines; longest: ${longest.lines.size} lines.",
            )
            sb.appendLine()
            sb.appendLine("| Operation | Runs | p50 (ms) | p95 (ms) |")
            sb.appendLine("|---|---|---|---|")

            for ((name, n) in listOf(
                "${mid.lines.size} lines" to mid,
                "${longest.lines.size} lines" to longest,
            ).distinctBy { it.second.id }) {
                sb.row("LineLexer full lex, $name", timeRuns(RUNS) { lexAll(n.lines) })
                val lines = n.lines.toMutableList()
                val states = lexAll(lines)
                val rnd = Random(42)
                val edits = ArrayList<Double>(EDITS)
                repeat(EDITS) {
                    val at = rnd.nextInt(lines.size)
                    val line = lines[at]
                    lines[at] = line + "x"
                    val t = System.nanoTime()
                    relex(lines, states, at)
                    edits += ms(t)
                }
                sb.row("LineLexer 1 char relex, $name", edits.sorted())
                sb.row("ObsidianMarkdown.parse, $name", timeRuns(RUNS) { ObsidianMarkdown.parse(n.text) })
                sb.row("NoteEditor.load, $name", timeRuns(RUNS) { NoteEditor(db, n.id, { 1L }).load() })
            }

            // Save of the 2.9k Note: one char more each time, so every save is a real write (UPDATE + FTS triggers).
            val editor = NoteEditor(db, mid.id, { 1_793_404_800L })
            val doc = checkNotNull(editor.load()) { "the 2.9k Note is gone" }
            check(!doc.readonly) { "the 2.9k Note is read-only on this server" }
            var text = mid.text
            val etag = doc.baseEtag
            sb.row(
                "NoteEditor.save, ${mid.lines.size} lines (saveEdit + FTS + Excerpt)",
                timeRuns(SAVES) {
                    text += "x"
                    check(editor.save(text, etag) == NoteEditor.SaveResult.Saved) { "save did not return Saved" }
                },
            )

            // Link resolution: every wikilink of the 20 most-linked Notes.
            val resolver = LinkResolver(db)
            val top =
                sources
                    .map { it to WIKILINK.findAll(it.text).map { m -> m.groupValues[1].substringBefore('|') }.toList() }
                    .sortedByDescending { it.second.size }
                    .take(20)
            val plain = ArrayList<Double>()
            val path = ArrayList<Double>()
            val perNote = ArrayList<Double>()
            for ((note, links) in top) {
                val noteStart = System.nanoTime()
                for (target in links) {
                    val t = System.nanoTime()
                    resolver.resolve(note.folder, target)
                    (if ('/' in target) path else plain) += ms(t)
                }
                perNote += ms(noteStart)
            }
            sb.row("LinkResolver.resolve, plain target (first run, cold cache)", plain.sorted().ifEmpty { listOf(0.0) })
            sb.row("LinkResolver.resolve, path-form target (COLLATE NOCASE)", path.sorted().ifEmpty { listOf(0.0) })
            sb.row("All links of one of the 20 most-linked Notes (${top.sumOf { it.second.size }} links in all)", perNote.sorted())
        }
        val table = sb.toString()
        println(table)
        val out = System.getenv("CITTI_BENCH_OUT")?.let(::File) ?: File.createTempFile("citti-editor-bench-", ".md")
        out.writeText(table)
        println("Results written to ${out.absolutePath}")
    }

    private companion object {
        const val RUNS = 10
        const val EDITS = 1000
        const val SAVES = 30
        val WIKILINK = Regex("""(?<!!)\[\[([^\[\]\n]*)]]""")
    }
}
