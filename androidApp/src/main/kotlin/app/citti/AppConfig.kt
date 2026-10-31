package app.citti

import app.citti.core.api.defaultHttpEngine
import app.citti.core.sync.AfterEditPush
import io.ktor.client.engine.HttpClientEngine
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The outside world of [AppContainer], with the production defaults. The E2E tests swap in the fake server's
 * engine, a pinned clock and a short push delay; nothing else changes.
 *
 * Every HTTP client of the app is built from [httpEngine], so the E2E fake sees every request.
 */
data class AppConfig(
    /** A new engine per client; production: OkHttp. */
    val httpEngine: () -> HttpClientEngine = ::defaultHttpEngine,
    val clock: Clock = Clock.System,
    val timeZone: () -> ZoneId = ZoneId::systemDefault,
    /** How long after the last edit [AfterEditPush] pushes. */
    val afterEditPushDelay: Duration = AfterEditPush.DEFAULT_DELAY.milliseconds,
) {
    /** Epoch seconds from [clock]. */
    fun nowSeconds(): Long = clock.now().epochSeconds

    /** Epoch milliseconds from [clock]. */
    fun nowMillis(): Long = clock.now().toEpochMilliseconds()
}
