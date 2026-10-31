package app.citti

/** First Android version with the POST_NOTIFICATIONS runtime permission (13, TIRAMISU). */
private const val MIN_SDK_FOR_NOTIFICATION_PERMISSION = 33

/**
 * Whether to show the POST_NOTIFICATIONS prompt: only from Android 13, only when not granted, and only once
 * (a refusal is respected; the sync works without the permission, its notification is just not shown).
 */
fun shouldAskNotificationPermission(
    sdk: Int,
    granted: Boolean,
    alreadyAsked: Boolean,
): Boolean = sdk >= MIN_SDK_FOR_NOTIFICATION_PERMISSION && !granted && !alreadyAsked
