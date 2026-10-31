package app.citti

import app.citti.core.settings.SyncNetwork

/** The sync settings the scheduler acts on, read from the core `SyncSettingsRepository`. */
data class ScheduleConfig(
    val intervalMinutes: Int,
    val network: SyncNetwork,
) {
    /** An interval of 0 means manual sync only: no periodic work. */
    val isManualOnly: Boolean get() = intervalMinutes == 0
}
