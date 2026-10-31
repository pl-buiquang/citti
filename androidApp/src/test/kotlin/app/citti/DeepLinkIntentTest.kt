package app.citti

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepLinkIntentTest {
    @Test
    fun aFreshLaunchAppliesTheLink() {
        assertTrue(shouldApplyDeepLink(isNewIntent = false, hasSavedState = false, flags = 0))
    }

    @Test
    fun aRecreationDoesNotApplyItAgain() {
        assertFalse(shouldApplyDeepLink(isNewIntent = false, hasSavedState = true, flags = 0))
    }

    @Test
    fun aRelaunchFromRecentsDoesNotApplyIt() {
        val flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK
        assertFalse(shouldApplyDeepLink(isNewIntent = false, hasSavedState = false, flags = flags))
    }

    @Test
    fun aNewIntentAlwaysAppliesIt() {
        assertTrue(shouldApplyDeepLink(isNewIntent = true, hasSavedState = true, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }
}
