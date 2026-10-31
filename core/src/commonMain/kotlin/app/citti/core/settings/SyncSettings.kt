package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `setting` keys shared with the background worker. */
const val KEY_SYNC_INTERVAL_MINUTES = "sync.interval-minutes"
const val KEY_SYNC_NETWORK = "sync.network"

const val DEFAULT_SYNC_INTERVAL_MINUTES = 30

/** Allowed intervals in minutes; 0 means "Manual only" (no periodic job). */
val SYNC_INTERVAL_CHOICES: List<Int> = listOf(15, 30, 60, 120, 360, 0)

/** Which networks the background sync may use. Stored by [name]. */
enum class SyncNetwork { ANY, UNMETERED }

/** Reads and writes the sync settings in the `setting` table. Unknown stored values fall back to the defaults. */
class SyncSettingsRepository(
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries

    fun intervalMinutes(): Int = parseInterval(queries.selectSetting(KEY_SYNC_INTERVAL_MINUTES).executeAsOneOrNull())

    fun network(): SyncNetwork = parseNetwork(queries.selectSetting(KEY_SYNC_NETWORK).executeAsOneOrNull())

    fun setIntervalMinutes(minutes: Int) {
        require(minutes in SYNC_INTERVAL_CHOICES) { "Unsupported interval: $minutes" }
        queries.upsertSetting(KEY_SYNC_INTERVAL_MINUTES, minutes.toString())
    }

    fun setNetwork(network: SyncNetwork) = queries.upsertSetting(KEY_SYNC_NETWORK, network.name)

    fun intervalMinutesFlow(): Flow<Int> =
        queries
            .selectSetting(KEY_SYNC_INTERVAL_MINUTES)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parseInterval)

    fun networkFlow(): Flow<SyncNetwork> =
        queries
            .selectSetting(KEY_SYNC_NETWORK)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parseNetwork)

    private fun parseInterval(raw: String?): Int =
        raw?.toIntOrNull()?.takeIf { it in SYNC_INTERVAL_CHOICES } ?: DEFAULT_SYNC_INTERVAL_MINUTES

    private fun parseNetwork(raw: String?): SyncNetwork = SyncNetwork.entries.firstOrNull { it.name == raw } ?: SyncNetwork.ANY
}
