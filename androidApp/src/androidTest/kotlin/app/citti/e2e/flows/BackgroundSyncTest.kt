package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.BackgroundSync
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class BackgroundSyncTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)

    @Test
    fun periodicWorkerPullsAServerChange() {
        list.assertRow("Roadmap")
        E2e.server.advance(60)
        E2e.clock.advance(1.minutes)
        E2e.server.editOnServer(e2e.setup.id("Roadmap"), title = "Roadmap 2027")
        E2e.server.clearRequests()

        BackgroundSync.runPeriodic()

        list.assertRow("Roadmap 2027")
        list.assertNoRow("Roadmap")
        ServerAssert().assertRequests(Endpoint.LIST, 1)
    }
}
