package app.citti.e2e.rules

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.rule.GrantPermissionRule
import app.citti.MainActivity
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The rules of one E2E test, in order: [E2eRule], the notification permission, then [setup], then the sync idling resource, then the
 * Compose rule, which launches [MainActivity] last.
 *
 * ```
 * @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())
 * ```
 */
class E2eRules(
    val setup: Setup,
) : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> = createAndroidComposeRule<MainActivity>()
    val idling = SyncIdlingResource()

    private val chain: RuleChain =
        RuleChain
            .outerRule(E2eRule())
            // Granted up front, so the one-time permission prompt never covers the app.
            .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
            .around(setup)
            .around(idling)
            .around(compose)
            .around(FailureScreenshot())

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement = chain.apply(base, description)

    /** Recreates the Activity (configuration change), keeping the process. */
    fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }
}
