package app.citti.ui.settings

import app.citti.core.agenda.AgendaDisplay
import app.citti.core.agenda.PastMode
import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.daily.DateLocales
import app.citti.core.settings.AgendaConfig
import app.citti.core.settings.EventCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaSettingsControllerTest {
    private class FakeStore : AgendaSettingsStore {
        val config = MutableStateFlow(AgendaConfig())
        var result: CompletableDeferred<ApiResult<Int>> = CompletableDeferred()
        var discoveries = 0
        val log = mutableListOf<String>()

        override fun config(): Flow<AgendaConfig> = config

        override suspend fun discover(): ApiResult<Int> {
            discoveries++
            return result.await()
        }

        override suspend fun setEnabled(
            href: String,
            enabled: Boolean,
        ) {
            log += "enabled $href $enabled"
        }

        override suspend fun rename(
            href: String,
            name: String,
        ) {
            log += "rename $href $name"
        }

        override suspend fun recolor(
            href: String,
            color: String?,
        ) {
            log += "recolor $href $color"
        }

        override suspend fun setTemplate(template: String) {
            log += "template $template"
        }

        override suspend fun setTimeFormat(format: String) {
            config.update { it.copy(display = it.display.copy(timeFormat = format)) }
        }

        override suspend fun setDayFormat(format: String) {
            log += "day $format"
        }

        override suspend fun setDays(days: Int) {
            log += "days $days"
        }

        override suspend fun setPast(mode: PastMode) {
            log += "past $mode"
        }

        override suspend fun setShowAllDay(show: Boolean) {
            log += "allday $show"
        }

        override suspend fun setHideCancelled(hide: Boolean) {
            log += "cancelled $hide"
        }
    }

    private fun kotlinx.coroutines.test.TestScope.controller(store: FakeStore) =
        AgendaSettingsController(backgroundScope, store, { LocalDateTime(2026, 11, 16, 14, 5) }, { DateLocales.en })
            .also { runCurrent() }

    @Test
    fun discoverRunsOnceAtATimeAndReportsTheOutcome() =
        runTest {
            val store = FakeStore()
            val c = controller(store)
            c.onDiscover()
            c.onDiscover()
            runCurrent()
            assertEquals(DiscoverState.Running, c.state.value.discover)
            assertEquals(1, store.discoveries)
            store.result.complete(ApiResult.Success(2))
            runCurrent()
            assertEquals(DiscoverState.Found(2), c.state.value.discover)

            store.result = CompletableDeferred(ApiResult.Failure(ApiError.Auth(401)))
            c.onDiscover()
            runCurrent()
            assertEquals(DiscoverState.Failed(ApiError.Auth(401)), c.state.value.discover)
        }

    @Test
    fun examplesFollowTheSavedFormats() =
        runTest {
            val store = FakeStore()
            val c = controller(store)
            assertEquals("14:05", c.state.value.timeExample)
            assertEquals("Monday 16 November", c.state.value.dayExample)
            c.onTimeFormat("h:mm A")
            runCurrent()
            assertEquals("2:05 PM", c.state.value.timeExample)
        }

    @Test
    fun writesRunInOrder() =
        runTest {
            val store = FakeStore()
            store.config.value = AgendaConfig(listOf(EventCalendar("/c/p/", "Personal")), AgendaDisplay())
            val c = controller(store)
            c.onEnabled("/c/p/", false)
            c.onRename("/c/p/", "Me")
            c.onRecolor("/c/p/", null)
            c.onTemplate("{{summary}}")
            c.onDays(3)
            c.onPast(PastMode.HIDE)
            c.onShowAllDay(false)
            c.onHideCancelled(false)
            runCurrent()
            assertEquals(
                listOf(
                    "enabled /c/p/ false",
                    "rename /c/p/ Me",
                    "recolor /c/p/ null",
                    "template {{summary}}",
                    "days 3",
                    "past HIDE",
                    "allday false",
                    "cancelled false",
                ),
                store.log,
            )
        }
}
