package app.citti.e2e

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs the E2E tests on [E2eApp], which wires the app to the fake server. */
class E2eRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, E2eApp::class.java.name, context)
}
