package app.citti

import android.app.Application
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Application class: owns the [AppContainer] so Activities, services and workers share one graph. */
class CittiApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(this, container.syncSettings) }

    override fun onCreate() {
        super.onCreate()
        // Periodic sync runs only while signed in: apply() on start or sign-in, cancel otherwise.
        // It is also re-applied whenever Settings changes the interval or the network constraint.
        container.appScope.launch {
            combine(
                container.signedIn,
                container.syncSettings.intervalMinutesFlow(),
                container.syncSettings.networkFlow(),
            ) { signedIn, _, _ -> signedIn }
                .collect { signedIn ->
                    when (signedIn) {
                        true -> syncScheduler.apply()
                        false -> syncScheduler.cancel()
                        null -> Unit
                    }
                }
        }
    }
}
