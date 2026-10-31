package app.citti.e2e.rules

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The outermost rule of every E2E test.
 *
 * - Fails the test when a request missed the fake's routes ([Endpoint.UNKNOWN]): no request may reach a real
 *   server, and every route the app calls must be faked.
 * - On failure, saves the request log to the test storage (pulled by the managed device into
 *   `androidApp/build/outputs/managed_device_android_test_additional_output/`) and logs it.
 *
 * The screenshot comes from [FailureScreenshot], which runs inside the Compose rule while the Activity is still up.
 */
class E2eRule : TestWatcher() {
    override fun succeeded(description: Description) {
        val unknown = E2e.server.requests.filter { it.endpoint == Endpoint.UNKNOWN }
        check(unknown.isEmpty()) {
            "requests outside the fake's routes:\n" + unknown.joinToString("\n") { "${it.method.value} ${it.path}" }
        }
    }

    override fun failed(
        e: Throwable,
        description: Description,
    ) {
        val name = "${description.testClass.simpleName}-${description.methodName}"
        val log = requestLog()
        Log.e(TAG, "$name failed; requests:\n$log")
        runCatching {
            TestStorage().openOutputFile("e2e/$name-requests.txt").use { it.write(log.toByteArray()) }
        }.onFailure { Log.e(TAG, "could not save the request log", it) }
    }

    private fun requestLog(): String =
        E2e.server.requests.joinToString("\n") { r ->
            val query = if (r.query.isEmpty()) "" else "?" + r.query.entries.joinToString("&") { "${it.key}=${it.value}" }
            "${r.method.value} ${r.path}$query -> ${r.status ?: "IOException"} (${r.endpoint})"
        }

    private companion object {
        const val TAG = "E2eRule"
    }
}

/** Saves a screenshot of a failed test to the test storage, before the Activity closes. */
class FailureScreenshot : TestWatcher() {
    override fun failed(
        e: Throwable,
        description: Description,
    ) {
        val name = "${description.testClass.simpleName}-${description.methodName}"
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
                TestStorage().openOutputFile("e2e/$name.png").use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onFailure { Log.e("FailureScreenshot", "could not save the screenshot", it) }
    }
}
