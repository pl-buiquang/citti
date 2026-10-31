package app.citti

import android.app.Application
import kotlinx.coroutines.launch

/** Application class: owns the [AppContainer] so Activities, services and workers share one graph. */
class CittiApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(this, container.database) }

    override fun onCreate() {
        super.onCreate()
        // Periodic sync runs only while signed in: apply() on start or sign-in, cancel otherwise.
        container.appScope.launch {
            container.signedIn.collect { signedIn ->
                when (signedIn) {
                    true -> syncScheduler.apply()
                    false -> syncScheduler.cancel()
                    null -> Unit
                }
            }
        }
    }
}
