package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoChangeSyncTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun pullToRefreshWithNoChangeIsOne304() {
        list.assertRow("Roadmap")
        E2e.server.clearRequests()
        list.refresh()
        list.assertRow("Roadmap")

        val lists = server.requests(Endpoint.LIST)
        assertEquals(listOf(304), lists.map { it.status }, "LIST statuses")
        server.assertRequests(Endpoint.GET_NOTE, 0)
        server.assertRequests(Endpoint.UPDATE, 0)
        assertTrue(E2e.server.requests.all { it.endpoint == Endpoint.LIST }, "requests: ${E2e.server.requests.map { it.endpoint }}")
    }
}

class LargeFirstSyncTest {
    @get:Rule val e2e = E2eRules(Setup.signedIn(extra = { E2e.server.generateNotes(500) }))

    @Test
    fun firstSyncOfMoreThanOnePagePagesWithTheCursor() {
        ListRobot(e2e).assertRow("Roadmap")
        assertTrue(ServerAssert().requests(Endpoint.LIST).any { "chunkCursor" in it.query }, "no chunkCursor in the LIST requests")
    }
}
