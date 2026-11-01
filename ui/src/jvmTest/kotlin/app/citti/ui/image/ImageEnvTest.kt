package app.citti.ui.image

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavRoot
import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ObsidianConfig
import app.citti.core.db.CittiDatabase
import app.citti.core.settings.RemoteImages
import coil3.ImageLoader
import coil3.PlatformContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImageEnvTest {
    private var requests = 0
    private val db =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val dav =
        DavClient(
            createNotesHttpClient(
                MockEngine {
                    requests++
                    respondError(HttpStatusCode.NotFound)
                },
                NotesApiConfig("https://host/", "alice", "x"),
            ),
            { DavRoot("https://host/", "alice", "Notes") },
        )
    private val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

    private fun env(remote: RemoteImages) = ImageEnv(loader, ImageResolver(dav, ObsidianConfig(dav, db)), "journal", remote = remote)

    @Test
    fun askWaitsForATapBeforeAWebImageLoads() {
        val choice = env(RemoteImages.ASK).choice("https://example.com/a.png?x=1")
        assertEquals(ImageChoice.AskRemote("https://example.com/a.png?x=1"), choice)
        assertEquals(0, requests)
    }

    @Test
    fun alwaysLoadsAWebImageAtOnce() {
        assertEquals(ImageChoice.Load("https://example.com/a.png"), env(RemoteImages.ALWAYS).choice("https://example.com/a.png"))
    }

    @Test
    fun vaultImagesLoadWhateverTheChoice() {
        for (remote in RemoteImages.entries) assertIs<ImageChoice.Load>(env(remote).choice("media/a.png"))
    }

    @Test
    fun otherSchemesAreNeverLoaded() {
        for (remote in RemoteImages.entries) {
            for (link in listOf("file:///sdcard/a.png", "content://x/a", "javascript:alert(1)", "data:image/png;base64,AA")) {
                assertEquals(ImageChoice.Unsupported, env(remote).choice(link), link)
            }
        }
    }

    @Test
    fun theHostOfAWebImageIsShown() {
        assertEquals("example.com", remoteHost("https://user@example.com/a/b.png?q=1#f"))
        assertEquals("example.com:8443", remoteHost("https://example.com:8443/a.png"))
    }
}
