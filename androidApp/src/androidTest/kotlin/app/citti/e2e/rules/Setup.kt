package app.citti.e2e.rules

import app.citti.core.auth.Credentials
import app.citti.core.fake.FixtureVault
import app.citti.core.fake.seed
import app.citti.core.settings.ExclusionKind
import app.citti.core.sync.SyncOutcome
import app.citti.core.sync.SyncTrigger
import app.citti.e2e.E2e
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.ExternalResource

/**
 * The state a test starts from, set up before the Activity launches: the fake seeded from a Fixture Vault and,
 * unless [signedOut], an account written through the same code the login uses (no UI).
 *
 * [ids] maps each fixture Vault path (`work/projects/alpha.md`) to its server id.
 */
class Setup private constructor(
    private val fixture: String?,
    private val signIn: Boolean,
    private val sync: Boolean,
    private val hidden: List<String>,
    private val unsynced: List<String>,
    private val extra: () -> Unit,
) : ExternalResource() {
    var ids: Map<String, Long> = emptyMap()
        private set

    /** The server id of the fixture Note titled [title] (unique titles only). */
    fun id(title: String): Long =
        ids.entries
            .single { it.key.substringAfterLast('/').removeSuffix(".md") == title }
            .value

    override fun before() {
        if (fixture != null) ids = E2e.server.seed(FixtureVault.load(fixture))
        extra()
        if (!signIn) return
        val container = E2e.container
        runBlocking {
            container.onSignedIn(Credentials(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD))
            withContext(Dispatchers.IO) {
                hidden.forEach { container.exclusions.add(ExclusionKind.HIDDEN, it) }
                unsynced.forEach { container.exclusions.add(ExclusionKind.UNSYNCED, it) }
            }
        }
        if (sync) {
            val outcome = E2e.sync(SyncTrigger.AppOpen)
            check(outcome is SyncOutcome.Synced) { "setup sync ended with $outcome" }
        }
        E2e.server.clearRequests()
    }

    companion object {
        /** Signed in, exclusions set, first sync done: where most flows start. */
        fun signedInAndSynced(
            fixture: String = "vault",
            hidden: List<String> = listOf("archive"),
            unsynced: List<String> = listOf("private"),
            extra: () -> Unit = {},
        ) = Setup(fixture, signIn = true, sync = true, hidden, unsynced, extra)

        /** Signed in with the exclusions set, nothing synced yet: the first sync runs when the List opens. */
        fun signedIn(
            fixture: String = "vault",
            hidden: List<String> = listOf("archive"),
            unsynced: List<String> = listOf("private"),
            extra: () -> Unit = {},
        ) = Setup(fixture, signIn = true, sync = false, hidden, unsynced, extra)

        /** The login screen, with the fake optionally seeded. */
        fun signedOut(
            fixture: String? = null,
            extra: () -> Unit = {},
        ) = Setup(fixture, signIn = false, sync = false, emptyList(), emptyList(), extra)
    }
}
