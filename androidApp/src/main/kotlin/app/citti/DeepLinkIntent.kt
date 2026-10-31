package app.citti

import android.content.Intent

/**
 * Whether the Activity applies the `citti://` link of its intent. A new intent ([onNewIntent]) always does. At creation
 * it only does for a fresh launch: not after a recreation (rotation, process death: [hasSavedState]) and not when
 * Android relaunched the task from Recents ([Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]), which would re-apply a link
 * that was already consumed.
 */
fun shouldApplyDeepLink(
    isNewIntent: Boolean,
    hasSavedState: Boolean,
    flags: Int,
): Boolean = isNewIntent || (!hasSavedState && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0)
