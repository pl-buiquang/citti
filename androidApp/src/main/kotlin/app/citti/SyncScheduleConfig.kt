package app.citti

/**
 * Background sync settings as stored in the `setting` table. The keys and the parsing live here
 * only until the Settings module owns them; then this file shrinks to the WorkManager mapping.
 */
object SyncScheduleKeys {
    const val INTERVAL_MINUTES = "sync.interval-minutes"
    const val NETWORK = "sync.network"
}

enum class SyncNetwork { ANY, UNMETERED }

/** [intervalMinutes] of 0 means manual sync only: no periodic work. */
data class ScheduleConfig(
    val intervalMinutes: Int,
    val network: SyncNetwork,
) {
    val isManualOnly: Boolean get() = intervalMinutes == 0
}

const val DEFAULT_INTERVAL_MINUTES = 30
val ALLOWED_INTERVAL_MINUTES = setOf(0, 15, 30, 60, 120, 360)

/** Parses raw setting values; anything missing or unknown falls back to the default. */
fun scheduleFrom(
    interval: String?,
    network: String?,
): ScheduleConfig {
    val minutes = interval?.trim()?.toIntOrNull()?.takeIf { it in ALLOWED_INTERVAL_MINUTES } ?: DEFAULT_INTERVAL_MINUTES
    val net = if (network?.trim()?.uppercase() == "UNMETERED") SyncNetwork.UNMETERED else SyncNetwork.ANY
    return ScheduleConfig(minutes, net)
}
