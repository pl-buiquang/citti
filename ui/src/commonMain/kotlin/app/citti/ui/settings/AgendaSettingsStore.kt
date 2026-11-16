package app.citti.ui.settings

import app.citti.core.agenda.PastMode
import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.daily.DateLocale
import app.citti.core.daily.MomentFormat
import app.citti.core.dav.CalDavClient
import app.citti.core.settings.AgendaConfig
import app.citti.core.settings.AgendaSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

/** What Settings › Agenda reads and changes; [CoreAgendaSettingsStore] adapts core, tests use a fake. */
interface AgendaSettingsStore {
    fun config(): Flow<AgendaConfig>

    /** One PROPFIND of the Account's calendar home; on success the list is merged into the settings. */
    suspend fun discover(): ApiResult<Int>

    suspend fun setEnabled(
        href: String,
        enabled: Boolean,
    )

    suspend fun rename(
        href: String,
        name: String,
    )

    /** `#rrggbb`, or null for the server's color. */
    suspend fun recolor(
        href: String,
        color: String?,
    )

    suspend fun setTemplate(template: String)

    suspend fun setTimeFormat(format: String)

    suspend fun setDayFormat(format: String)

    suspend fun setDays(days: Int)

    suspend fun setPast(mode: PastMode)

    suspend fun setShowAllDay(show: Boolean)

    suspend fun setHideCancelled(hide: Boolean)
}

/** [AgendaSettingsStore] over core, every database read and write and the request on [io]. */
class CoreAgendaSettingsStore(
    private val settings: AgendaSettings,
    /** Null while signed out. */
    private val calDav: () -> CalDavClient?,
    private val io: CoroutineDispatcher,
) : AgendaSettingsStore {
    override fun config(): Flow<AgendaConfig> = settings.configFlow()

    override suspend fun discover(): ApiResult<Int> {
        val client = calDav() ?: return ApiResult.Failure(ApiError.DavNotReady)
        return withContext(io) {
            when (val found = client.calendars()) {
                is ApiResult.Success -> ApiResult.Success(found.value.size).also { settings.mergeDiscovered(found.value) }
                is ApiResult.Failure -> found
            }
        }
    }

    override suspend fun setEnabled(
        href: String,
        enabled: Boolean,
    ) = withContext(io) { settings.setEnabled(href, enabled) }

    override suspend fun rename(
        href: String,
        name: String,
    ) = withContext(io) { settings.rename(href, name) }

    override suspend fun recolor(
        href: String,
        color: String?,
    ) = withContext(io) { settings.recolor(href, color) }

    override suspend fun setTemplate(template: String) = withContext(io) { settings.setTemplate(template) }

    override suspend fun setTimeFormat(format: String) = withContext(io) { settings.setTimeFormat(format) }

    override suspend fun setDayFormat(format: String) = withContext(io) { settings.setDayFormat(format) }

    override suspend fun setDays(days: Int) = withContext(io) { settings.setDays(days) }

    override suspend fun setPast(mode: PastMode) = withContext(io) { settings.setPast(mode) }

    override suspend fun setShowAllDay(show: Boolean) = withContext(io) { settings.setShowAllDay(show) }

    override suspend fun setHideCancelled(hide: Boolean) = withContext(io) { settings.setHideCancelled(hide) }
}

/** Where Discover calendars is. */
sealed interface DiscoverState {
    data object Idle : DiscoverState

    data object Running : DiscoverState

    data class Found(
        val count: Int,
    ) : DiscoverState

    data class Failed(
        val error: ApiError,
    ) : DiscoverState
}

/** Settings › Agenda. */
data class AgendaUiState(
    val config: AgendaConfig = AgendaConfig(),
    val discover: DiscoverState = DiscoverState.Idle,
    /** Now in the saved time and day formats, for the examples under the fields. */
    val timeExample: String = "",
    val dayExample: String = "",
)

/** The state holder of Settings › Agenda; [now] and [locale] give the examples. */
class AgendaSettingsController(
    private val scope: CoroutineScope,
    private val store: AgendaSettingsStore,
    private val now: () -> LocalDateTime,
    private val locale: () -> DateLocale,
) {
    private val discover = MutableStateFlow<DiscoverState>(DiscoverState.Idle)

    val state: StateFlow<AgendaUiState> =
        combine(store.config(), discover) { config, d ->
            val at = now()
            AgendaUiState(
                config = config,
                discover = d,
                timeExample = MomentFormat.format(at.date, at.time, config.display.timeFormat, locale()),
                dayExample = MomentFormat.format(at.date, at.time, config.display.dayFormat, locale()),
            )
        }.stateIn(scope, SharingStarted.Eagerly, AgendaUiState())

    fun onDiscover() {
        if (discover.value == DiscoverState.Running) return
        discover.value = DiscoverState.Running
        scope.launch {
            discover.update {
                when (val result = store.discover()) {
                    is ApiResult.Success -> DiscoverState.Found(result.value)
                    is ApiResult.Failure -> DiscoverState.Failed(result.error)
                }
            }
        }
    }

    fun onEnabled(
        href: String,
        enabled: Boolean,
    ) = launch { store.setEnabled(href, enabled) }

    fun onRename(
        href: String,
        name: String,
    ) = launch { store.rename(href, name) }

    fun onRecolor(
        href: String,
        color: String?,
    ) = launch { store.recolor(href, color) }

    fun onTemplate(template: String) = launch { store.setTemplate(template) }

    fun onTimeFormat(format: String) = launch { store.setTimeFormat(format) }

    fun onDayFormat(format: String) = launch { store.setDayFormat(format) }

    fun onDays(days: Int) = launch { store.setDays(days) }

    fun onPast(mode: PastMode) = launch { store.setPast(mode) }

    fun onShowAllDay(show: Boolean) = launch { store.setShowAllDay(show) }

    fun onHideCancelled(hide: Boolean) = launch { store.setHideCancelled(hide) }

    // Writes run one at a time, in the order they were asked for: the last value typed is the one kept.
    private val writes = Mutex()

    private fun launch(block: suspend () -> Unit) {
        scope.launch { writes.withLock { block() } }
    }
}
