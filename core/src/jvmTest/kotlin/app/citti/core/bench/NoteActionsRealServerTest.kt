package app.citti.core.bench

import app.citti.core.api.ApiResult
import app.citti.core.api.ListResult
import app.citti.core.api.NewNote
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.RemoteNote
import app.citti.core.api.UpdateNote
import app.citti.core.api.UpdateResult
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavRoot
import app.citti.core.dav.VaultPath
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M5 real-server checks (spec §2): what the server does with duplicate titles on POST, rename and move, whether the
 * title match is case-sensitive, how `category=` filters the list, and WebDAV `PUT` with `If-None-Match: *`.
 * It records answers rather than asserting them. Skipped unless `CITTI_PUSH_REAL=1`.
 *
 * WRITES, but only throwaway Notes titled `citti-push-test-<epoch>*` in `scratch` (and one sub-Folder of it with the
 * same prefix), plus one file `scratch/citti-dav-test-<epoch>.png`. A guard refuses any write this run did not
 * create; everything is deleted in `finally`.
 *
 * ```
 * set -a; source nextcloud.env; set +a; CITTI_PUSH_REAL=1 ./gradlew :core:jvmTest --tests '*NoteActionsRealServerTest*' -i
 * ```
 */
class NoteActionsRealServerTest {
    private val cfg: NotesApiConfig by lazy {
        NotesApiConfig(env("NEXTCLOUD_URL"), env("NEXTCLOUD_USER"), env("NEXTCLOUD_PASSWORD"))
    }

    private fun env(name: String) = requireNotNull(System.getenv(name)) { "$name is not set" }

    private val created: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private val davAllowed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val violations = Collections.synchronizedList(mutableListOf<String>())

    private val guard =
        Interceptor { chain ->
            val request = chain.request()
            val segments = request.url.pathSegments.filter { it.isNotEmpty() }
            val last = segments.lastOrNull().orEmpty()
            val isDav = "dav" in segments && "files" in segments
            val allowed =
                when {
                    request.method == "GET" || request.method == "PROPFIND" -> true
                    isDav -> request.method in setOf("PUT", "DELETE") && request.url.toString() in davAllowed
                    request.method == "POST" -> last == "notes"
                    request.method in setOf("PUT", "DELETE") -> last.toLongOrNull()?.let { it in created } == true
                    else -> false
                }
            if (!allowed) {
                violations += "${request.method} refused: ${segments.takeLast(2)}"
                throw IOException("guard refused ${request.method}")
            }
            chain.proceed(request)
        }

    private val http = OkHttpClient.Builder().addInterceptor(guard).build()

    private fun raw(
        method: String,
        url: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val b =
            Request
                .Builder()
                .url(url)
                .header("Authorization", Credentials.basic(cfg.user, cfg.appPassword))
                .header("OCS-APIRequest", "true")
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, body?.toRequestBody() ?: if (method in setOf("PUT", "POST", "PROPFIND")) ByteArray(0).toRequestBody() else null)
        return http.newCall(b.build()).execute()
    }

    private fun notesUrl(query: String) = cfg.baseUrl.trimEnd('/') + "/index.php/apps/notes/api/v1/notes?" + query

    private fun listRaw(query: String): Triple<Int, List<Pair<String, String>>, String?> =
        raw("GET", notesUrl(query)).use { r ->
            if (r.code != 200) return Triple(r.code, emptyList(), r.header("ETag"))
            val arr = Json.parseToJsonElement(r.body.string()).jsonArray
            val items =
                arr.map {
                    val o = it.jsonObject
                    (o["title"]?.jsonPrimitive?.content ?: "") to (o["category"]?.jsonPrimitive?.content ?: "")
                }
            Triple(r.code, items, r.header("ETag"))
        }

    @Test
    fun probe() {
        assumeTrue("set CITTI_PUSH_REAL=1 to run against the real server", System.getenv("CITTI_PUSH_REAL") == "1")
        val epoch = System.currentTimeMillis() / 1000
        val prefix = "citti-push-test-$epoch"
        val folder = "scratch"
        val sub = "$folder/$prefix-sub"
        val out = StringBuilder()

        fun log(line: String) {
            println(line)
            out.appendLine(line)
        }

        val client = createNotesHttpClient(OkHttp.create { addInterceptor(guard) }, cfg)
        val api = NotesApi(client, cfg)
        var smokeOk = false
        var davUrl: String? = null
        var subUrl: String? = null
        try {
            val listed = runBlocking { api.listNotes(excludeContent = true) }
            val all =
                ((listed as? ApiResult.Success)?.value as? ListResult.Page)?.notes
                    ?: fail("initial listing failed; nothing was written")
            val inFolder = all.count { (it.category ?: "") == folder }
            assertTrue(inFolder > 0, "Folder `$folder` does not exist on the server; refusing to create one")
            smokeOk = true
            log("smoke: ${all.size} notes, $inFolder in `$folder`, ${all.count { (it.category ?: "").isEmpty() }} at the root")

            fun create(
                title: String,
                category: String = folder,
            ): RemoteNote {
                val r = runBlocking { api.createNote(NewNote(title, "# $title\n\nbody of $title\n", category)) }
                val note = (r as? ApiResult.Success)?.value ?: fail("POST $title failed: $r")
                created += note.id
                return note
            }

            fun get(id: Long) = (runBlocking { api.getNote(id) } as? ApiResult.Success)?.value

            fun put(
                note: RemoteNote,
                update: UpdateNote,
            ): String =
                when (val r = runBlocking { api.updateNote(note.id, update, "\"${note.etag}\"") }) {
                    is ApiResult.Success -> {
                        when (val v = r.value) {
                            is UpdateResult.Updated -> "200, title `${v.note.title}`, category `${v.note.category}`"
                            UpdateResult.PreconditionFailed -> "412"
                        }
                    }

                    is ApiResult.Failure -> {
                        "failure ${r.error}"
                    }
                }

            // 1. POST of an existing title.
            val a = create("$prefix a")
            val a2 = create("$prefix a")
            log("1. POST existing title: first `${a.title}`, second `${a2.title}` (same id? ${a.id == a2.id})")
            log("   first still has its content: ${get(a.id)?.content == "# $prefix a\n\nbody of $prefix a\n"}")

            // 2a. PUT that changes only the title onto an existing title in the same Folder.
            val b = create("$prefix b")
            val renamed = put(b, UpdateNote(title = "$prefix a"))
            log("2a. PUT rename onto existing title: $renamed")
            log("    `a` untouched: ${get(a.id)?.let { it.title == "$prefix a" && it.content == "# $prefix a\n\nbody of $prefix a\n" }}")
            log("    `b` content now: ${get(b.id)?.content?.lines()?.first()}")

            // 5 / 4.1. Category filter, before the move so the sub-Folder has a note.
            val d = create("$prefix a", category = sub)
            log("    note in sub-Folder created as `${d.title}` in `${d.category}`")
            val (c1, f1, e1) = listRaw("exclude=content&category=" + folder)
            log(
                "5a. category=$folder: $c1, ${f1.size} items, categories ${f1.map { it.second }.toSet()}, " +
                    "includes the sub-Folder note: ${f1.any { it.second == sub }}, ETag ${e1 != null}",
            )
            val (c2, f2, _) = listRaw("exclude=content&category=")
            log(
                "5b. category= (empty): $c2, ${f2.size} items (all ${all.size + 5}, root ${all.count {
                    (it.category ?: "").isEmpty()
                }}), " +
                    "categories sample ${f2.map { it.second }.toSet().take(5)}",
            )
            val (c3, f3, _) = listRaw("exclude=content&category=" + sub)
            log("5c. category=$sub: $c3, ${f3.size} items")
            if (e1 != null) {
                raw("GET", notesUrl("exclude=content&category=$folder"), headers = mapOf("If-None-Match" to e1)).use {
                    log("5d. category=$folder with its own ETag: ${it.code}")
                }
                val (_, _, eAll) = listRaw("exclude=content")
                log("5e. full-list ETag equals the folder ETag: ${eAll == e1}")
            }

            // 2b. PUT that changes only the category into a Folder that has the title.
            val dNow = get(d.id) ?: fail("no d")
            log("2b. PUT move into a Folder that has the title: ${put(dNow, UpdateNote(category = folder))}")

            // 3. Case of the title match.
            val up = create("$prefix CASE")
            val low = create("$prefix case")
            log("3. POST `${up.title}` then `$prefix case` gives `${low.title}`")

            // 4. WebDAV PUT with If-None-Match: *.
            val userId = (runBlocking { api.userId() } as? ApiResult.Success)?.value ?: fail("no user id")
            val notesPath = (runBlocking { api.settings() } as? ApiResult.Success)?.value?.notesPath ?: fail("no settings")
            val root = DavRoot(cfg.baseUrl, userId, notesPath)
            val url = root.url(VaultPath.parse("$folder/citti-dav-test-$epoch.png") ?: fail("bad path"))
            davUrl = url
            davAllowed += url
            subUrl = root.url(VaultPath.parse(sub) ?: fail("bad path"))
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
            val first =
                raw("PUT", url, png, mapOf("If-None-Match" to "*", "Content-Type" to "image/png")).use {
                    "${it.code}, ETag ${it.header("ETag") != null}, OC-ETag ${it.header("OC-ETag") != null}"
                }
            log("4a. DAV PUT If-None-Match:* onto a new path: $first")
            val second = raw("PUT", url, byteArrayOf(9, 9), mapOf("If-None-Match" to "*")).use { it.code.toString() }
            val kept = raw("GET", url).use { it.body.bytes().contentEquals(png) }
            log("4b. DAV PUT If-None-Match:* onto the existing file: $second, file unchanged: $kept")
        } finally {
            if (smokeOk) {
                try {
                    val page = runBlocking { api.listNotes(excludeContent = true) }
                    created +=
                        (((page as? ApiResult.Success)?.value as? ListResult.Page)?.notes ?: emptyList())
                            .filter { it.title?.startsWith(prefix) == true }
                            .map { it.id }
                    var deleted = 0
                    for (id in created.toList()) if (runBlocking { api.deleteNote(id) } is ApiResult.Success) deleted++
                    davUrl?.let { u -> raw("DELETE", u).use { log("cleanup: DAV DELETE ${it.code}") } }
                    subUrl?.let { u ->
                        val code = raw("PROPFIND", u, headers = mapOf("Depth" to "0")).use { it.code }
                        log("cleanup: sub-Folder after its notes went: PROPFIND $code (404 = removed by the server)")
                        if (code == 207) {
                            davAllowed += u
                            raw("DELETE", u).use { log("cleanup: sub-Folder DAV DELETE ${it.code}") }
                        }
                    }
                    val after = runBlocking { api.listNotes(excludeContent = true) }
                    val left =
                        (((after as? ApiResult.Success)?.value as? ListResult.Page)?.notes ?: emptyList())
                            .count { it.title?.startsWith(prefix) == true }
                    log("cleanup: deleted $deleted of ${created.size}, left with the prefix: $left")
                    davUrl?.let { u -> raw("GET", u).use { log("cleanup: DAV file GET after delete: ${it.code}") } }
                } catch (e: Throwable) {
                    log("CLEANUP ERROR ${e::class.simpleName}: ${e.message}")
                }
            }
            client.close()
        }
        log("guard violations: ${violations.size} $violations")
        File(System.getenv("CITTI_PUSH_OUT") ?: "build/m5-real-server.md").apply { parentFile?.mkdirs() }.writeText(out.toString())
        assertEquals(0, violations.size)
    }
}
