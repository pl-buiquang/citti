package app.citti.e2e

import android.content.Intent
import app.citti.MainActivity
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant

/** The local id of the fixture Note titled [title]: what `citti://note/<id>` and the widgets carry (not the server id). */
fun Setup.localId(title: String): Long =
    E2e.container.database.noteQueries
        .selectByRemoteId(id(title))
        .executeAsOne()
        .local_id

/** The Reading position of a Note, written the way leaving the Note screen does. */
fun setViewLine(
    localId: Long,
    line: Int,
) = runBlocking { E2e.container.setViewLine(localId, line) }

/** The Reading position stored for [localId], or null. */
fun viewLine(localId: Long): Long? =
    E2e.container.database.noteQueries
        .selectViewLine(localId)
        .executeAsOne()
        .view_line

/** Moves the app's clock and the server's to [instant]: they are separate. */
fun moveTo(instant: Instant) {
    E2e.clock.set(instant)
    E2e.server.now = instant.epochSeconds
}

/**
 * A widget tap reaches the running Activity through `onNewIntent`, which calls `setIntent`. ActivityScenario ignores
 * the lifecycle events of an Activity whose intent is not the one it started and then fails to close it, so a flow
 * that taps a widget calls [restore] right after the tap (a harness detail: the app keeps the link's intent in real use).
 */
class AppIntent(
    private val e2e: E2eRules,
) {
    /** The intent ActivityScenario started MainActivity with: it compares the Activity's intent with this one. */
    private val original: Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(E2e.app, MainActivity::class.java.name)

    /** Ends the test cleanly: ActivityScenario cannot close an Activity it lost track of after a widget tap. */
    fun finish() {
        restore()
        e2e.compose.activityRule.scenario
            .onActivity { it.finish() }
    }

    fun restore() {
        e2e.compose.waitForIdle()
        e2e.compose.activityRule.scenario
            .onActivity { it.intent = original }
    }
}
