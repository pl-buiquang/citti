package app.citti.e2e

import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.citti.AppConfig
import app.citti.AppContainer
import app.citti.CittiApp
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app under test: the fake server is its only HTTP engine, "today" is pinned and the push after an edit
 * comes after 100 ms. WorkManager is the test one, so periodic syncs never fire on their own ([BackgroundSync]).
 */
class E2eApp : CittiApp() {
    override fun onCreate() {
        // Before super.onCreate(): the app's SyncScheduler must get the test WorkManager. It replaces the instance
        // the app's startup initializer made, so the app manifest stays untouched.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        super.onCreate()
    }

    override fun createContainer(): AppContainer =
        AppContainer(
            this,
            AppConfig(
                httpEngine = { E2e.server.engine },
                clock = E2e.clock,
                timeZone = { E2e.ZONE },
                afterEditPushDelay = 100.milliseconds,
            ),
        )
}
