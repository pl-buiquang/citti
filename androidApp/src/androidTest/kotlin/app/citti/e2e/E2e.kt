package app.citti.e2e

import androidx.test.platform.app.InstrumentationRegistry
import app.citti.AppContainer
import app.citti.CittiApp
import app.citti.core.fake.E2E_SERVER
import app.citti.core.fake.FakeNotesServer
import app.citti.core.sync.SyncOutcome
import app.citti.core.sync.SyncTrigger
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A [Clock] the test moves by hand. */
class TestClock(
    start: Instant,
) : Clock {
    @Volatile
    private var current = start

    override fun now(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(by: Duration) {
        current += by
    }
}

/**
 * Process-wide state of one E2E test: the fake server and the clock. Orchestrator starts a new process per
 * test, so every test gets fresh ones.
 */
object E2e {
    /** 2026-10-30T12:00:00Z, the "today" of the Fixture Vault. */
    val TODAY: Instant = Instant.parse("2026-10-30T12:00:00Z")
    val ZONE: ZoneId = ZoneId.of("Europe/Paris")
    const val SERVER = E2E_SERVER
    const val USER = "alice"
    const val APP_PASSWORD = "app-password-e2e"

    val clock = TestClock(TODAY)

    /** The only engine of the app (see [E2eApp]); it answers with the credentials [USER] / [APP_PASSWORD]. */
    val server =
        FakeNotesServer(now = TODAY.epochSeconds).apply {
            credentials = USER to APP_PASSWORD
        }

    val app: CittiApp get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CittiApp

    val container: AppContainer get() = app.container

    /** Runs one sync from the test thread, as the app would on a pull-to-refresh. */
    fun sync(trigger: SyncTrigger = SyncTrigger.Manual): SyncOutcome? = runBlocking { container.runSync(trigger, ownForeground = false) }
}
