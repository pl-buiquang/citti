package app.citti.core.bench

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.ListResult
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.UpdateNote
import app.citti.core.api.UpdateResult
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.sync.PullResult
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushResult
import app.citti.core.sync.PushSync
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Push test against a real Nextcloud. WRITES, but only throwaway Notes created by this run: every title starts
 * with `citti-push-test-<epoch>`, in the existing Folder `scratch`. Skipped unless `CITTI_PUSH_REAL=1`.
 *
 * Safety: an HTTP guard refuses any PUT/DELETE on a remote id this run did not create, and everything created
 * is deleted in `finally` (then a final listing checks that no Note with the prefix remains).
 *
 * Reads `NEXTCLOUD_URL`, `NEXTCLOUD_USER`, `NEXTCLOUD_PASSWORD`; prints only counts, statuses and the titles of the
 * throwaway Notes. The table goes to stdout and to `CITTI_PUSH_OUT`.
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_PUSH_REAL=1 ./gradlew :core:jvmTest --tests '*PushRealServerTest*' -i
 * ```
 */
class PushRealServerTest {
    private val cfg: NotesApiConfig by lazy {
        NotesApiConfig(env("NEXTCLOUD_URL"), env("NEXTCLOUD_USER"), env("NEXTCLOUD_PASSWORD"))
    }

    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private val requests = AtomicInteger()
    private val failNext = AtomicInteger()
    private val created: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private val violations = Collections.synchronizedList(mutableListOf<String>())
    private val noteGetIds = Collections.synchronizedList(mutableListOf<Long>())

    private val guard =
        Interceptor { chain ->
            val request = chain.request()
            if (failNext.get() > 0 && failNext.getAndDecrement() > 0) throw IOException("simulated offline")
            val segments = request.url.pathSegments.filter { it.isNotEmpty() }
            val last = segments.lastOrNull().orEmpty()
            val lastId = last.toLongOrNull()
            val allowed =
                when (request.method) {
                    "GET" -> true
                    "POST" -> last == "notes"
                    "PUT", "DELETE" -> lastId != null && lastId in created
                    else -> false
                }
            if (!allowed) {
                violations += "${request.method} refused: ${segments.takeLast(2)}"
                throw IOException("push test guard refused ${request.method}")
            }
            if (request.method == "GET" && lastId != null && segments.getOrNull(segments.size - 2) == "notes") noteGetIds += lastId
            requests.incrementAndGet()
            chain.proceed(request)
        }

    private class Row(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val requests: Int,
    )

    @Test
    fun push() {
        assumeTrue("set CITTI_PUSH_REAL=1 to run against the real server", System.getenv("CITTI_PUSH_REAL") == "1")
        val epoch = System.currentTimeMillis() / 1000
        val prefix = "citti-push-test-$epoch"
        val category = "scratch"
        val rows = mutableListOf<Row>()
        val client = createNotesHttpClient(OkHttp.create { addInterceptor(guard) }, cfg)
        val api = NotesApi(client, cfg)
        val dir = createTempDirectory("citti-push").toFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "push.db").absolutePath}")
        CittiDatabase.Schema.create(driver)
        val db = CittiDatabase(driver)
        val q = db.noteQueries
        var cleanupLeft = -1
        var cleanupDeleted = 0
        var smokeOk = false
        try {
            // Smoke test: read only, abort cleanly on any error. Also checks that the Folder `scratch` exists.
            val smoke = runBlocking { api.capabilities() }
            if (smoke is ApiResult.Failure) fail("capabilities() failed: ${smoke.error::class.simpleName}; nothing was written")
            val listed = runBlocking { api.listNotes(excludeContent = true) }
            val all =
                ((listed as? ApiResult.Success)?.value as? ListResult.Page)?.notes
                    ?: fail("initial listing failed; nothing was written")
            val scratchCount = all.count { (it.category ?: "") == category }
            assertTrue(scratchCount > 0, "Folder `$category` does not exist on the server; refusing to create one")
            println("smoke: capabilities ok, ${all.size} notes on server, $scratchCount in `$category`")
            smokeOk = true

            fun now() = System.currentTimeMillis() / 1000

            fun push(): PushResult = runBlocking { PushSync(api, db, now = ::now).push() }

            /** Registers the remote ids of our local rows so the guard allows updating/deleting them. */
            fun track() {
                val ids =
                    driver
                        .executeQuery(
                            null,
                            "SELECT remote_id FROM note WHERE title LIKE '$prefix%' AND remote_id IS NOT NULL",
                            { c ->
                                val l = mutableListOf<Long>()
                                while (c.next().value) c.getLong(0)?.let(l::add)
                                QueryResult.Value(l)
                            },
                            0,
                        ).value
                created += ids
            }

            fun scenario(
                name: String,
                block: () -> String,
            ) {
                val before = requests.get()
                val row =
                    try {
                        Row(name, true, block(), requests.get() - before)
                    } catch (e: Throwable) {
                        Row(name, false, "FAIL ${e::class.simpleName}: ${e.message?.take(200)}", requests.get() - before)
                    } finally {
                        track()
                    }
                rows += row
            }

            fun remote(id: Long) = (runBlocking { api.getNote(id) } as? ApiResult.Success)?.value ?: fail("getNote($id) failed")

            fun rowOf(localId: Long) = q.selectByLocalId(localId).executeAsOne()

            fun newNote(
                title: String,
                content: String,
            ): Long =
                db.transactionWithResult {
                    q.insertNew(title, category, content, now(), null)
                    q.lastInsertedLocalId().executeAsOne()
                }

            val title = "$prefix a"
            val text1 = "# $title\n\nfirst version\n"
            val text2 = "# $title\n\nsecond version, local edit\n"
            val serverText = "# $title\n\nedited on the server\n"
            val localText = "# $title\n\nlocal edit that loses the race\n"
            var a = 0L
            var remoteA = 0L

            scenario("1. create") {
                a = newNote(title, text1)
                val r = push()
                check(r is PushResult.Done && r.created == 1 && r.failures.isEmpty()) { "push result $r" }
                val row = rowOf(a)
                assertEquals("SYNCED", row.sync_state)
                remoteA = row.remote_id ?: fail("no remote id stored")
                assertTrue(!row.etag.isNullOrEmpty(), "no etag stored")
                val server = remote(remoteA)
                assertEquals(text1, server.content, "server content")
                assertEquals(title, server.title)
                assertEquals(category, server.category)
                assertEquals(server.etag, row.etag, "stored etag matches the server")
                "created, remote id stored, SYNCED, content equal"
            }

            scenario("2. update (If-Match)") {
                q.markDirty(text2, now(), a)
                assertEquals("DIRTY", rowOf(a).sync_state)
                val r = push()
                check(r is PushResult.Done && r.updated == 1 && r.conflicts.isEmpty()) { "push result $r" }
                assertEquals("SYNCED", rowOf(a).sync_state)
                assertEquals(text2, remote(remoteA).content, "server content")
                assertEquals(remote(remoteA).etag, rowOf(a).etag, "etag refreshed")
                "updated, server has new content, etag refreshed"
            }

            scenario("3. conflict (412)") {
                val etag = rowOf(a).etag!!
                val res = runBlocking { api.updateNote(remoteA, UpdateNote(title, serverText), "\"$etag\"") }
                check((res as? ApiResult.Success)?.value is UpdateResult.Updated) { "direct server edit failed: $res" }
                q.markDirty(localText, now(), a)
                val r = push()
                check(r is PushResult.Done && r.conflicts.size == 1 && r.failures.isEmpty()) { "push result $r" }
                // The server version stays in place and replaces the local one.
                assertEquals(serverText, remote(remoteA).content, "server version untouched")
                assertEquals(serverText, rowOf(a).content, "local original replaced by the server version")
                assertEquals("SYNCED", rowOf(a).sync_state)
                val copy = rowOf(r.conflicts.single())
                assertTrue(Regex("""\(conflict \d{4}-\d{2}-\d{2}\)$""").containsMatchIn(copy.title), "conflict title: ${copy.title}")
                assertTrue(copy.title.startsWith("$title (conflict "), "conflict title: ${copy.title}")
                assertEquals(a, copy.conflict_of)
                assertEquals(localText, copy.content)
                assertEquals("SYNCED", copy.sync_state, "conflict copy pushed in the same run")
                val remoteCopy = remote(copy.remote_id ?: fail("copy has no remote id"))
                assertEquals(localText, remoteCopy.content, "conflict copy on the server")
                assertEquals(category, remoteCopy.category)
                assertEquals(copy.title, remoteCopy.title)
                "server kept, copy `${copy.title}` local+server with local text, conflict_of set"
            }

            scenario("6. pull after push") {
                // Exclude every other top-level Folder so the pull stays small (client-side, read only).
                val folders =
                    all
                        .mapNotNull { it.category?.substringBefore('/') }
                        .filter { it.isNotEmpty() && it != category }
                        .toSet()
                folders.forEach { db.settingQueries.insertExclusion(it, "UNSYNCED") }
                val mine = created.toSet()
                val getsBefore = noteGetIds.size
                val pulled = runBlocking { PullSync(api, db).pull() }
                val fetchedMine = noteGetIds.drop(getsBefore).count { it in mine }
                val detail =
                    when (pulled) {
                        is PullResult.Pulled -> "pulled, fetched ${pulled.contentFetched} (others), failures ${pulled.failures.size}"
                        PullResult.NothingChanged -> "nothing changed"
                        is PullResult.Failed -> fail("pull failed ${pulled.error::class.simpleName}")
                    }
                assertEquals(0, fetchedMine, "content GETs by the pull for notes just pushed")
                assertEquals(serverText, rowOf(a).content)
                assertEquals("SYNCED", rowOf(a).sync_state)
                "$detail; no pull content GET for our ${mine.size} pushed notes (excluded ${folders.size} other folders)"
            }

            scenario("4. delete") {
                q.markDeleted(a)
                val r = push()
                check(r is PushResult.Done && r.deleted == 1) { "push result $r" }
                val gone = runBlocking { api.getNote(remoteA) }
                assertTrue(gone is ApiResult.Failure && gone.error == ApiError.NotFound, "getNote after delete: $gone")
                assertEquals(0, q.selectByLocalId(a).executeAsList().size, "local row removed")
                "deleted, server answers 404, local row gone"
            }

            scenario("5. offline then online") {
                val b = newNote("$prefix b", "# $prefix b\n\nqueued offline\n")
                failNext.set(1)
                val first = push()
                assertTrue(first is PushResult.Failed && first.error is ApiError.Network, "first push should fail with Network: $first")
                assertEquals("NEW", rowOf(b).sync_state, "queue survives a failed push")
                val second = push()
                check(second is PushResult.Done && second.created == 1) { "second push $second" }
                val row = rowOf(b)
                assertEquals("SYNCED", row.sync_state)
                assertEquals("# $prefix b\n\nqueued offline\n", remote(row.remote_id ?: fail("no remote id")).content)
                "first push Network failure (queue intact), second push created it"
            }
        } finally {
            // Cleanup: runs on every path, deletes everything carrying our unique prefix.
            if (smokeOk) {
                try {
                    val page = runBlocking { api.listNotes(excludeContent = true) }
                    val mine =
                        (((page as? ApiResult.Success)?.value as? ListResult.Page)?.notes ?: emptyList())
                            .filter { it.title?.startsWith(prefix) == true && (it.category ?: "") == category }
                            .map { it.id }
                    created += mine
                    for (id in created.toList()) {
                        val d = runBlocking { api.deleteNote(id) }
                        if (d is ApiResult.Success) cleanupDeleted++
                    }
                    val after = runBlocking { api.listNotes(excludeContent = true) }
                    cleanupLeft =
                        (((after as? ApiResult.Success)?.value as? ListResult.Page)?.notes ?: emptyList())
                            .count { it.title?.startsWith(prefix) == true }
                } catch (e: Throwable) {
                    println("CLEANUP ERROR ${e::class.simpleName}")
                }
            }
            client.close()
            driver.close()
            dir.deleteRecursively()
        }

        val sb = StringBuilder()
        sb.appendLine("# Push test (real server, throwaway notes `$prefix*` in `$category`)")
        sb.appendLine()
        sb.appendLine("| Scenario | Result | Requests | Detail |")
        sb.appendLine("|---|---|---|---|")
        for (r in rows.sortedBy { it.name }) {
            sb.appendLine("| ${r.name} | ${if (r.ok) "PASS" else "FAIL"} | ${r.requests} | ${r.detail} |")
        }
        sb.appendLine()
        sb.appendLine("Total requests: ${requests.get()}. Guard violations: ${violations.size}.")
        sb.appendLine(
            "Cleanup: deleted $cleanupDeleted of ${created.size} tracked notes; notes with the prefix left on the server: $cleanupLeft.",
        )
        val table = sb.toString()
        println(table)
        val out =
            File(
                System.getenv("CITTI_PUSH_OUT")
                    ?: "/tmp/claude-1000/-home-paul-projects-citti/a55eb13c-43c3-4a02-adbf-8aaabb24b28e/scratchpad/push-real.md",
            )
        out.parentFile?.mkdirs()
        out.writeText(table)
        println("Results written to ${out.absolutePath}")

        assertEquals(0, cleanupLeft, "notes with the prefix remain on the server")
        assertTrue(violations.isEmpty(), "guard violations: $violations")
        val failed = rows.filter { !it.ok }
        assertTrue(failed.isEmpty(), "failed scenarios: ${failed.map { it.name }}")
    }
}
