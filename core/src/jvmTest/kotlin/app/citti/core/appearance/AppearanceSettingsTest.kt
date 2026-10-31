package app.citti.core.appearance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppearanceSettingsTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val settings = AppearanceSettings(db, Dispatchers.Unconfined)

    @Test
    fun defaultsOnEmptyDb() = assertEquals(Appearance(), settings.current())

    @Test
    fun settersRoundTrip() {
        settings.setTheme(ThemeChoice.PAPER)
        settings.setMode(ModeChoice.DARK)
        settings.setAccent(AccentSource.DYNAMIC)
        val custom = CustomTheme(BaseTheme.INDEX, light = mapOf(ColorToken.BG to 0xFF101010L))
        settings.setCustom(custom)
        assertEquals(Appearance(ThemeChoice.PAPER, ModeChoice.DARK, AccentSource.DYNAMIC, custom), settings.current())
        assertEquals("PAPER", db.settingQueries.selectSetting(KEY_APPEARANCE_THEME).executeAsOne())
        settings.setCustom(null)
        assertNull(settings.current().custom)
    }

    @Test
    fun unknownStoredValueFallsBack() {
        db.settingQueries.upsertSetting(KEY_APPEARANCE_THEME, "NEON")
        db.settingQueries.upsertSetting(KEY_APPEARANCE_MODE, "")
        db.settingQueries.upsertSetting(KEY_APPEARANCE_ACCENT, "x")
        assertEquals(Appearance(), settings.current())
    }

    @Test
    fun corruptCustomReadsAsEmptySlot() {
        db.settingQueries.upsertSetting(KEY_APPEARANCE_CUSTOM, "{not json")
        assertNull(settings.current().custom)
    }

    @Test
    fun updateCustomKeepsBothSequentialUpdates() {
        settings.setCustom(CustomTheme(BaseTheme.PAPER))
        settings.updateCustom { it.copy(light = it.light + (ColorToken.BG to 0xFF111111L)) }
        settings.updateCustom { it.copy(roles = it.roles.copy(textScale = 1.2)) }
        val c = settings.current().custom!!
        assertEquals(0xFF111111L, c.light[ColorToken.BG])
        assertEquals(1.2, c.roles.textScale)
        assertEquals(BaseTheme.PAPER, c.base)
    }

    @Test
    fun selectCustomOrSeedSeedsFromThePreset() {
        val seeded = settings.selectCustomOrSeed(BaseTheme.INDEX)
        assertEquals(CustomTheme(BaseTheme.INDEX), seeded)
        assertEquals(ThemeChoice.CUSTOM, settings.current().theme)
        assertEquals(CustomTheme(BaseTheme.INDEX), settings.current().custom)
        // A filled slot is kept.
        settings.updateCustom { it.copy(base = BaseTheme.PAPER) }
        assertEquals(BaseTheme.PAPER, settings.selectCustomOrSeed(BaseTheme.SIGNAL).base)
    }

    @Test
    fun onChangedFiresPerWrite() {
        var n = 0
        settings.onChanged = { n++ }
        settings.setTheme(ThemeChoice.INDEX)
        settings.setMode(ModeChoice.LIGHT)
        settings.updateCustom { it }
        assertEquals(3, n)
    }

    @Test
    fun flowEmitsOnChangeNotOnUnrelatedRow() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<Appearance>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { settings.flow().toList(seen) }
            settings.setMode(ModeChoice.DARK)
            db.settingQueries.upsertSetting("ui.main-mode", "TREE")
            settings.setTheme(ThemeChoice.PAPER)
            job.cancel()
            assertEquals(listOf(Appearance(), Appearance(mode = ModeChoice.DARK), Appearance(ThemeChoice.PAPER, ModeChoice.DARK)), seen)
        }

    @Test
    fun signOutKeepsAppearanceAndWipesTheRest() {
        settings.setTheme(ThemeChoice.INDEX)
        settings.setCustom(CustomTheme(BaseTheme.PAPER))
        db.settingQueries.upsertSetting("ui.main-mode", "TREE")
        db.settingQueries.upsertSetting("daily.folder", "Journal")
        db.accountQueries.deleteAllSettings()
        assertEquals(ThemeChoice.INDEX, settings.current().theme)
        assertEquals(CustomTheme(BaseTheme.PAPER), settings.current().custom)
        assertNull(db.settingQueries.selectSetting("ui.main-mode").executeAsOneOrNull())
        assertNull(db.settingQueries.selectSetting("daily.folder").executeAsOneOrNull())
    }
}
