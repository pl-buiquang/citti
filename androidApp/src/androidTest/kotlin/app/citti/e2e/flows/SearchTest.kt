package app.citti.e2e.flows

import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class SearchTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    @Test
    fun foldsAccentsFindsHiddenNotesAndNothingUnsynced() {
        val search = ListRobot(e2e).openSearch()
        E2e.server.clearRequests()

        search.search("cafe")
        search.assertResult("Recipes")

        // The ligature as typed. "oeuvre" does not find "œuvre": unicode61 folds diacritics, not ligatures.
        search.search("œuvre")
        search.assertResult("Reading list")

        // archive/ is Hidden: searchable, not listed.
        search.search("zanzibar")
        search.assertResult("Old plan")

        // private/ is Unsynced: never on the phone.
        search.search("quokka")
        search.assertNothingFound("quokka")

        ServerAssert().assertNoRequests()
    }
}
