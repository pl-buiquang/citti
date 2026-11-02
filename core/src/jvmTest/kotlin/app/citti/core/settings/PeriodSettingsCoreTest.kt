package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.daily.PeriodKind
import app.citti.core.db.CittiDatabase
import app.citti.core.note.NameError
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodSettingsCoreTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val settings = DailySettings(db, ExclusionRepository(db, Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun theDefaultsAreTheTitlesOfTheVault() {
        val config = settings.config()
        assertEquals("GGGG-[W]WW", config.formatOf(PeriodKind.WEEK))
        assertEquals("YYYY-MMMM", config.formatOf(PeriodKind.MONTH))
        assertEquals("YYYY-[Q]Q", config.formatOf(PeriodKind.QUARTER))
        assertEquals("YYYY", config.formatOf(PeriodKind.YEAR))
        assertEquals("YYYY-MM-DD", config.formatOf(PeriodKind.DAY))
        assertEquals("", config.templateOf(PeriodKind.MONTH))
        assertEquals("templates/daily.md", config.templateOf(PeriodKind.DAY))
        assertFalse(config.showWeeks)
        assertFalse(config.showQuarter)
    }

    @Test
    fun aSavedFormatIsRead() {
        assertEquals(PeriodFormatResult.Saved("MMMM YYYY"), settings.setPeriodFormat(PeriodKind.MONTH, "MMMM YYYY"))
        assertEquals("MMMM YYYY", settings.config().formatOf(PeriodKind.MONTH))
        assertEquals("YYYY-MM-DD", settings.config().formatOf(PeriodKind.DAY))
    }

    @Test
    fun aBlankFormatResetsToTheDefault() {
        settings.setPeriodFormat(PeriodKind.MONTH, "MMMM YYYY")
        assertEquals(PeriodFormatResult.Saved("YYYY-MMMM"), settings.setPeriodFormat(PeriodKind.MONTH, "  "))
        assertEquals("YYYY-MMMM", settings.config().formatOf(PeriodKind.MONTH))
        assertEquals(null, db.settingQueries.selectSetting(periodFormatKey(PeriodKind.MONTH)).executeAsOneOrNull())
    }

    @Test
    fun aFormatWithASlashOrAColonIsRefused() {
        assertEquals(PeriodFormatResult.Slash, settings.setPeriodFormat(PeriodKind.MONTH, "YYYY/MMMM"))
        assertEquals(
            PeriodFormatResult.InvalidTitle(NameError.UnsafeCharacter),
            settings.setPeriodFormat(PeriodKind.QUARTER, "YYYY: [Q]Q"),
        )
        assertEquals("YYYY-MMMM", settings.config().formatOf(PeriodKind.MONTH))
    }

    @Test
    fun aFormatThatMissesADatePartIsAmbiguous() {
        // Every month of a year would be one note, and every quarter 1 of every year.
        assertEquals(PeriodFormatResult.Ambiguous, settings.setPeriodFormat(PeriodKind.MONTH, "YYYY"))
        assertEquals(PeriodFormatResult.Ambiguous, settings.setPeriodFormat(PeriodKind.QUARTER, "[Q]Q"))
        assertEquals(PeriodFormatResult.Ambiguous, settings.setPeriodFormat(PeriodKind.WEEK, "[Week]"))
        assertEquals(PeriodFormatResult.Ambiguous, settings.setPeriodFormat(PeriodKind.WEEK, "[W]WW"))
    }

    @Test
    fun aFormatThatWritesAnotherKindsTitleIsACollision() {
        assertEquals(PeriodFormatResult.Collision(PeriodKind.MONTH), settings.setPeriodFormat(PeriodKind.YEAR, "YYYY-MMMM"))
        assertEquals(PeriodFormatResult.Collision(PeriodKind.DAY), settings.setPeriodFormat(PeriodKind.WEEK, "YYYY-MM-DD"))
    }

    @Test
    fun theWeekYearKeepsTheLastWeekOfDecemberApart() {
        // 2027-01-01 is 2026-W53: with the calendar year (YYYY) it would be 2027-W53.
        assertEquals(PeriodFormatResult.Saved("GGGG-[W]WW"), settings.setPeriodFormat(PeriodKind.WEEK, "GGGG-[W]WW"))
    }

    @Test
    fun aTemplateIsNormalisedAndBlankMeansNone() {
        settings.setPeriodTemplate(PeriodKind.MONTH, "/templates/month/")
        assertEquals("templates/month.md", settings.config().templateOf(PeriodKind.MONTH))
        settings.setPeriodTemplate(PeriodKind.MONTH, "")
        assertEquals("", settings.config().templateOf(PeriodKind.MONTH))
    }

    @Test
    fun theTogglesAreStored() {
        settings.setShowWeeks(true)
        settings.setShowQuarter(true)
        assertTrue(settings.config().showWeeks)
        assertTrue(settings.config().showQuarter)
        settings.setShowWeeks(false)
        assertFalse(settings.config().showWeeks)
        assertTrue(settings.config().showQuarter)
    }
}
