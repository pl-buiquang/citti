package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class FirstSyncTest {
    private val release = CountDownLatch(1)

    // The first LIST is held until the test saw the first-sync state.
    @get:Rule val e2e =
        E2eRules(
            Setup.signedIn(extra = {
                E2e.server.beforeDispatch = { endpoint -> if (endpoint == Endpoint.LIST) release.await(10, TimeUnit.SECONDS) }
            }),
        )

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun showsProgressThenTheRowsWithoutUnsyncedFolders() {
        e2e.idling.paused { list.assertFirstSyncEmpty() }
        release.countDown()

        list.assertRow("Roadmap")
        list.assertRow("alpha")
        list.assertNoRow("Diary")

        val privateIds =
            E2e.server.notes
                .filter { it.category == "private" }
                .map { it.id.toString() }
        assertTrue(server.requests(Endpoint.GET_NOTE) { it.path.substringAfterLast('/') in privateIds }.isEmpty())
        assertTrue(server.requests(Endpoint.LIST).none { "chunkCursor" in it.query }, "the fixture fits in one page")
    }
}
