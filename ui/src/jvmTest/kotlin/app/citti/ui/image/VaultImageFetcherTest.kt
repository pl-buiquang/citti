package app.citti.ui.image

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavRoot
import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ObsidianConfig
import app.citti.core.dav.VaultPath
import app.citti.core.db.CittiDatabase
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VaultImageFetcherTest {
    private val dir = Files.createTempDirectory("citti-image-cache").toFile()
    private val cache =
        DiskCache
            .Builder()
            .directory(
                dir.toOkioPath(),
            ).fileSystem(FileSystem.SYSTEM)
            .maxSizeBytes(50L * 1024 * 1024)
            .build()
    private val db =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private var now = 1_000_000_000L
    private val files = HashMap<String, ByteArray>()
    private val etags = HashMap<String, String>()
    private val seen = ArrayList<Pair<String, String?>>()
    private var offline = false

    private val engine =
        MockEngine { request ->
            if (offline) throw IOException("no network")
            val path =
                request.url.encodedPath
                    .substringAfter("/remote.php/dav/files/alice/Notes/")
                    .replace("%20", " ")
            val sent = request.headers[HttpHeaders.IfNoneMatch]
            seen += path to sent
            val body = files[path]
            when {
                body == null -> {
                    respondError(HttpStatusCode.NotFound)
                }

                sent != null && sent == etags[path] -> {
                    respond(ByteArray(0), HttpStatusCode.NotModified)
                }

                else -> {
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headers {
                            append(HttpHeaders.ETag, etags[path] ?: "\"e\"")
                            append(HttpHeaders.ContentType, "image/png")
                        },
                    )
                }
            }
        }

    private val dav =
        DavClient(
            createNotesHttpClient(engine, NotesApiConfig("https://host/", "alice", "x")),
            { DavRoot("https://host/", "alice", "Notes") },
        )

    /** A new session: the resolver forgets what it learned. */
    private fun fetcher(
        folder: String,
        link: String,
    ): VaultImageFetcher {
        val resolver = ImageResolver(dav, ObsidianConfig(dav, db))
        return VaultImageFetcher(VaultImageKey(folder, link), { resolver }, cache) { now }
    }

    private suspend fun VaultImageFetcher.bytes(): ByteArray {
        val result = assertNotNull(fetch() as? SourceFetchResult)
        return result.source.use { it.source().readByteArray() }
    }

    private fun imageRequests() = seen.filter { !it.first.startsWith(".obsidian") }

    @AfterTest
    fun cleanUp() {
        cache.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun downloadsOnceAndThenShowsFromTheCacheWithNoRequest() =
        runTest {
            files["media/supernote/a.png"] = byteArrayOf(1, 2, 3)
            assertContentEquals(byteArrayOf(1, 2, 3), fetcher("journal", "supernote/a.png").bytes())
            seen.clear()
            assertContentEquals(byteArrayOf(1, 2, 3), fetcher("journal", "supernote/a.png").bytes())
            assertEquals(emptyList(), seen)
        }

    @Test
    fun twoNotesPointingAtTheSameFileShareOneCacheEntry() =
        runTest {
            files["media/x.png"] = byteArrayOf(9)
            fetcher("a", "x.png").bytes()
            seen.clear()
            // Another Note, another way to write the link, the same resolved path.
            assertContentEquals(byteArrayOf(9), fetcher("b", "../media/x.png").bytes())
            assertEquals(emptyList(), seen)
            assertNotNull(cache.openSnapshot("media/x.png")).close()
        }

    @Test
    fun anOldEntryIsRevalidatedWithItsEtagAndAnUnchangedFileKeepsItsBytes() =
        runTest {
            files["a/x.png"] = byteArrayOf(5)
            etags["a/x.png"] = "\"v1\""
            fetcher("a", "x.png").bytes()
            seen.clear()
            now += IMAGE_REVALIDATE_AFTER_MS + 1
            assertContentEquals(byteArrayOf(5), fetcher("a", "x.png").bytes())
            assertEquals(listOf("a/x.png" to "\"v1\""), imageRequests())
            // A 304 renewed the entry: no request in the next 24 hours.
            seen.clear()
            now += IMAGE_REVALIDATE_AFTER_MS - 1
            fetcher("a", "x.png").bytes()
            assertEquals(emptyList(), seen)
        }

    @Test
    fun aChangedFileReplacesTheCachedBytes() =
        runTest {
            files["a/x.png"] = byteArrayOf(1)
            etags["a/x.png"] = "\"v1\""
            fetcher("a", "x.png").bytes()
            files["a/x.png"] = byteArrayOf(2, 2)
            etags["a/x.png"] = "\"v2\""
            now += IMAGE_REVALIDATE_AFTER_MS
            assertContentEquals(byteArrayOf(2, 2), fetcher("a", "x.png").bytes())
            seen.clear()
            assertContentEquals(byteArrayOf(2, 2), fetcher("a", "x.png").bytes())
            assertEquals(emptyList(), seen)
        }

    @Test
    fun anOldEntryStillShowsOffline() =
        runTest {
            files["a/x.png"] = byteArrayOf(7)
            fetcher("a", "x.png").bytes()
            now += 10 * IMAGE_REVALIDATE_AFTER_MS
            offline = true
            assertContentEquals(byteArrayOf(7), fetcher("a", "x.png").bytes())
        }

    @Test
    fun offlineWithoutACachedCopyIsReportedAsOffline() =
        runTest {
            offline = true
            val e = assertFailsWith<ImageUnavailable> { fetcher("a", "x.png").fetch() }
            assertEquals(ImageUnavailable.Reason.Offline, e.reason)
            assertEquals(true, e.offline)
        }

    @Test
    fun aFileThatIsNotOnTheServerIsReportedAsMissing() =
        runTest {
            val e = assertFailsWith<ImageUnavailable> { fetcher("a", "none.png").fetch() }
            assertEquals(ImageUnavailable.Reason.Missing, e.reason)
            assertEquals(false, e.offline)
            assertNull(cache.openSnapshot("a/none.png"))
        }

    @Test
    fun theMetadataIsKeptNextToTheBytes() =
        runTest {
            files["a/x.png"] = byteArrayOf(1)
            etags["a/x.png"] = "\"v1\""
            fetcher("a", "x.png").bytes()
            val snapshot = assertNotNull(cache.openSnapshot("a/x.png"))
            val meta = snapshot.use { FileSystem.SYSTEM.read(it.metadata) { readUtf8() } }
            assertEquals(CachedImageMeta(now, "\"v1\"", "image/png"), CachedImageMeta.decode(meta))
        }

    @Test
    fun aSeededUploadShowsFromAnyFolderWithNoRequest() =
        runTest {
            val path = assertNotNull(VaultPath.parse("media/IMG_1.jpg"))
            seedImageCache(cache, path, byteArrayOf(7, 8), "\"up\"", "image/jpeg", now)
            val resolver = ImageResolver(dav, ObsidianConfig(dav, db))
            resolver.rememberUpload("IMG_1.jpg", path)
            for (folder in listOf("", "journal", "projects/citti")) {
                val fetcher = VaultImageFetcher(VaultImageKey(folder, "IMG_1.jpg"), { resolver }, cache) { now }
                assertContentEquals(byteArrayOf(7, 8), fetcher.bytes())
            }
            assertEquals(emptyList(), seen)
        }
}
