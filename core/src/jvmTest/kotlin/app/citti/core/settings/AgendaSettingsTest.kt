package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.agenda.AgendaDisplay
import app.citti.core.agenda.PastMode
import app.citti.core.auth.CredentialStoreFactory
import app.citti.core.auth.signOut
import app.citti.core.dav.DiscoveredCalendar
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgendaSettingsTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val dropped = mutableListOf<Set<String>>()
    private val settings = AgendaSettings(db, Dispatchers.Unconfined) { dropped += it }

    private val personal = DiscoveredCalendar("/remote.php/dav/calendars/alice/personal/", "Personal", "#0082c9")
    private val work = DiscoveredCalendar("/remote.php/dav/calendars/alice/work/", "Work", "#ff0000")

    @Test
    fun defaults() =
        runTest {
            assertEquals(AgendaConfig(), settings.config())
            assertEquals(AgendaConfig(), settings.configFlow().first())
            assertEquals("{{when}} · **{{summary}}**{{#location}} — {{location}}{{/location}}", settings.config().display.template)
        }

    @Test
    fun aNewDiscoveryKeepsTheUsersChoices() {
        settings.mergeDiscovered(listOf(personal, work))
        assertEquals(listOf(true, true), settings.config().calendars.map { it.enabled })
        settings.setEnabled(work.href, false)
        settings.rename(personal.href, "Me")
        settings.recolor(personal.href, "#123456")
        settings.recolor(personal.href, "red")

        settings.mergeDiscovered(
            listOf(personal.copy(name = "Personal 2", color = "#000000"), work, work.copy(href = "/x/new/", name = "New")),
        )
        val calendars = settings.config().calendars
        assertEquals(listOf("Me", "Work", "New"), calendars.map { it.name })
        assertEquals(listOf("#123456", "#ff0000", "#ff0000"), calendars.map { it.color })
        assertEquals(listOf(true, false, true), calendars.map { it.enabled })
        assertEquals("Personal 2", calendars[0].serverName)
        assertTrue(calendars[0].matches("personal 2"))
        assertTrue(calendars[0].matches("ME"))

        settings.rename(personal.href, " ")
        settings.recolor(personal.href, null)
        assertEquals("Personal 2" to "#000000", settings.config().calendars[0].let { it.name to it.color })
    }

    @Test
    fun disablingOrLosingACalendarReportsIt() {
        settings.mergeDiscovered(listOf(personal, work))
        settings.setEnabled(work.href, false)
        settings.setEnabled(work.href, false)
        settings.mergeDiscovered(listOf(work))
        assertEquals(listOf(setOf(work.href), setOf(personal.href)), dropped)
    }

    @Test
    fun displaySettings() {
        settings.setTemplate("  {{summary}} ")
        settings.setTimeFormat("h:mm A")
        settings.setDayFormat("")
        settings.setDays(400)
        settings.setPast(PastMode.HIDE)
        settings.setShowAllDay(false)
        settings.setHideCancelled(false)
        assertEquals(
            AgendaDisplay("{{summary}}", "h:mm A", "dddd D MMMM", 366, PastMode.HIDE, showAllDay = false, hideCancelled = false),
            settings.config().display,
        )
        settings.setTemplate("")
        assertEquals(AgendaDisplay.DEFAULT_TEMPLATE, settings.config().display.template)
    }

    @Test
    fun signOutWipesTheAgendaSettings() =
        runTest {
            settings.mergeDiscovered(listOf(personal))
            settings.setDays(3)
            signOut(CredentialStoreFactory().create(), db)
            assertEquals(AgendaConfig(), settings.config())
        }
}
