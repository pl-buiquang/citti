package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.markdown.ToolbarAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorSettingsTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }

    @Test
    fun theToolbarShowsEveryButtonByDefault() {
        val settings = EditorSettings(db)
        assertEquals(ToolbarAction.entries.toList(), settings.toolbar().visible)
    }

    @Test
    fun aLayoutRoundTripsWithOrderAndHiddenButtons() =
        runTest {
            val settings = EditorSettings(db)
            val layout = ToolbarLayout.DEFAULT.toggle(ToolbarAction.Checkbox).move(ToolbarAction.Strike, up = true)
            settings.setToolbar(layout)
            assertEquals(layout, settings.toolbar())
            assertEquals(layout, settings.toolbarFlow().first())
            assertEquals(
                listOf(
                    ToolbarAction.Bold,
                    ToolbarAction.Strike,
                    ToolbarAction.Italic,
                    ToolbarAction.Heading,
                    ToolbarAction.List,
                    ToolbarAction.Link,
                    ToolbarAction.Image,
                ),
                settings.toolbar().visible,
            )
        }

    @Test
    fun parsingDropsUnknownAndRepeatedNamesAndHidesMissingOnes() {
        val layout = ToolbarLayout.parse("Link,Nope,Bold,Link,-Italic")
        assertEquals(listOf(ToolbarAction.Link, ToolbarAction.Bold), layout.visible)
        assertEquals(ToolbarAction.entries.size, layout.order.size)
        assertEquals(ToolbarAction.entries.toSet() - setOf(ToolbarAction.Link, ToolbarAction.Bold), layout.hidden)
    }

    @Test
    fun anEmptyValueIsNoButtons() {
        assertEquals(emptyList(), ToolbarLayout.parse("").visible)
    }

    @Test
    fun movingStopsAtTheEnds() {
        val first = ToolbarLayout.DEFAULT
        assertEquals(first, first.move(ToolbarAction.entries.first(), up = true))
        assertEquals(first, first.move(ToolbarAction.entries.last(), up = false))
    }
}
