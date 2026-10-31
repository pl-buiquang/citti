package app.citti.e2e.rules

import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import app.citti.e2e.E2e
import org.junit.rules.ExternalResource

/**
 * Idle when no sync runs and no push after an edit is pending. Registered with Espresso, which the Compose
 * test rule also waits on, so every finder waits for the sync to settle.
 *
 * A test that must look at the UI while a request is held (see `FakeNotesServer.beforeDispatch`) wraps that
 * part in [paused].
 */
class SyncIdlingResource : ExternalResource() {
    @Volatile
    private var enabled = true

    private val resource =
        object : IdlingResource {
            @Volatile
            private var callback: IdlingResource.ResourceCallback? = null

            override fun getName() = "citti-sync"

            override fun isIdleNow(): Boolean {
                val idle = !enabled || E2e.container.isSyncIdle()
                if (idle) callback?.onTransitionToIdle()
                return idle
            }

            override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
                this.callback = callback
            }
        }

    /** Runs [block] without waiting for the sync. */
    fun <T> paused(block: () -> T): T {
        enabled = false
        try {
            return block()
        } finally {
            enabled = true
        }
    }

    override fun before() {
        IdlingRegistry.getInstance().register(resource)
    }

    override fun after() {
        IdlingRegistry.getInstance().unregister(resource)
    }
}
