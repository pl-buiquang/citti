package app.citti

import android.app.Application

/** Application class: owns the [AppContainer] so Activities, services and workers share one graph. */
class CittiApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
