package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.auth.CredentialStoreFactory
import app.citti.core.auth.signOut
import app.citti.core.db.CittiDatabase
import app.citti.core.note.NameError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailySettingsCoreTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)

    private fun stored(key: String) = db.settingQueries.selectSetting(key).executeAsOneOrNull()

    @Test
    fun defaults() =
        runTest {
            val expected = DailyConfig("journal", "templates/daily.md", "YYYY-MM-DD", null, null, null)
            assertEquals(expected, settings.config())
            assertEquals(expected, settings.configFlow().first())
            assertNull(settings.templateCache())
            assertFalse(settings.importDone())
        }

    @Test
    fun configFlowFollowsChanges() =
        runTest {
            settings.setFolder("daily")
            settings.setWeekStart(DayOfWeek.SUNDAY)
            val config = settings.configFlow().first()
            assertEquals("daily", config.folder)
            assertEquals(DayOfWeek.SUNDAY, config.weekStart)
            assertEquals("daily", settings.folderFlow().first())
        }

    @Test
    fun formatRefusals() {
        assertEquals(FormatResult.Slash, settings.setFormat("YYYY/MM/DD"))
        assertEquals(FormatResult.Slash, settings.setFormat("YYYY\\MM\\DD"))
        assertEquals(FormatResult.RoundTrip, settings.setFormat("YYYY-MM"))
        assertEquals(FormatResult.RoundTrip, settings.setFormat("YYYY-[W]WW"))
        assertEquals(FormatResult.RoundTrip, settings.setFormat("MMMM"))
        assertEquals(FormatResult.InvalidTitle(NameError.UnsafeCharacter), settings.setFormat("YYYY-MM-DD HH:mm"))
        assertEquals(FormatResult.InvalidTitle(NameError.UnsafeCharacter), settings.setFormat("[Daily:] YYYY-MM-DD"))
        assertEquals(FormatResult.InvalidTitle(NameError.LeadingDot), settings.setFormat("[.]YYYY-MM-DD"))
        assertNull(stored(KEY_DAILY_FORMAT))
        assertEquals(FormatResult.Saved("DD.MM.YYYY"), settings.setFormat("DD.MM.YYYY"))
        assertEquals(FormatResult.Saved("D MMMM YYYY, dddd"), settings.setFormat("D MMMM YYYY, dddd"))
        assertEquals("D MMMM YYYY, dddd", settings.config().format)
        assertEquals(FormatResult.Saved("YYYY-MM-DD"), settings.setFormat("  "))
        assertNull(stored(KEY_DAILY_FORMAT))
    }

    @Test
    fun formatRoundTripUsesTheSelectedLocale() {
        settings.setLocale("fr")
        assertEquals(FormatResult.Saved("D MMMM YYYY"), settings.setFormat("D MMMM YYYY"))
        settings.setLocale("es")
        assertEquals(FormatResult.Saved("DD-MMM-YYYY"), settings.setFormat("DD-MMM-YYYY"))
    }

    @Test
    fun folderIsNormalisedAndAnUnsyncedOneRefused() {
        assertEquals(FolderResult.Saved("notes/daily"), settings.setFolder(" /notes//daily/ "))
        assertEquals("notes/daily", settings.folder())
        assertEquals(AddResult.Added("archive"), exclusions.add(ExclusionKind.UNSYNCED, "archive"))
        assertEquals(FolderResult.Refused(NameError.Unsynced), settings.setFolder("archive"))
        assertEquals(FolderResult.Refused(NameError.Unsynced), settings.setFolder("archive/journal"))
        assertEquals(FolderResult.Refused(NameError.DotSegment), settings.setFolder("a/../b"))
        assertEquals("notes/daily", settings.folder())
        assertEquals(FolderResult.Saved("archived"), settings.setFolder("archived"))
        assertEquals(AddResult.Added("old"), exclusions.add(ExclusionKind.HIDDEN, "old"))
        assertEquals(FolderResult.Saved("old"), settings.setFolder("old"))
    }

    @Test
    fun aNewFolderResetsTheDetectedLocale() {
        settings.setLocaleDetected("fr")
        settings.setFolder("journal")
        assertEquals("fr", settings.config().localeDetected)
        settings.setFolder("Journal")
        assertNull(settings.config().localeDetected)
        settings.setLocaleDetected("de")
        settings.setFolder("other")
        assertNull(stored(KEY_DAILY_LOCALE_DETECTED))
    }

    @Test
    fun localeOverrideSetsBothKeys() {
        settings.setLocale("fr")
        assertEquals("fr", stored(KEY_DAILY_LOCALE))
        assertEquals("fr", stored(KEY_DAILY_LOCALE_DETECTED))
        assertEquals("fr", settings.config().locale)
        settings.setLocale(null)
        assertNull(stored(KEY_DAILY_LOCALE))
        assertNull(stored(KEY_DAILY_LOCALE_DETECTED))
        assertFailsWith<IllegalArgumentException> { settings.setLocale("ru") }
    }

    @Test
    fun templateChangeClearsTheCache() {
        val cache = TemplateCache("templates/daily.md", "\"e1\"", "# {{title}}\n")
        settings.setTemplateCache(cache)
        assertEquals(cache, settings.templateCache())
        settings.setTemplate("/templates/daily.md")
        assertEquals(cache, settings.templateCache())
        settings.setTemplate("templates/journal")
        assertEquals("templates/journal.md", settings.config().template)
        assertNull(settings.templateCache())
        assertNull(stored(KEY_DAILY_TEMPLATE_CACHE))
        settings.setTemplate("")
        assertEquals("", settings.config().template)
    }

    @Test
    fun weekStartAndGarbageValues() {
        settings.setWeekStart(DayOfWeek.SATURDAY)
        assertEquals("SATURDAY", stored(KEY_DAILY_WEEK_START))
        assertFailsWith<IllegalArgumentException> { settings.setWeekStart(DayOfWeek.TUESDAY) }
        settings.setWeekStart(null)
        assertNull(settings.config().weekStart)
        db.settingQueries.upsertSetting(KEY_DAILY_WEEK_START, "TUESDAY")
        db.settingQueries.upsertSetting(KEY_DAILY_LOCALE, "xx")
        db.settingQueries.upsertSetting(KEY_DAILY_TEMPLATE_CACHE, "not json")
        assertNull(settings.config().weekStart)
        assertNull(settings.config().locale)
        assertNull(settings.templateCache())
    }

    @Test
    fun signOutWipesDailySettings() =
        runTest {
            settings.setFolder("daily")
            settings.setTemplate("t/day")
            settings.setFormat("DD.MM.YYYY")
            settings.setLocale("fr")
            settings.setWeekStart(DayOfWeek.MONDAY)
            settings.setTemplateCache(TemplateCache("t/day.md", null, "x"))
            settings.setImportDone()
            assertTrue(settings.importDone())

            signOut(CredentialStoreFactory().create(), db)

            assertEquals(emptyList(), db.settingQueries.selectSettingsWithPrefix(DAILY_PREFIX).executeAsList())
            assertEquals(DailyConfig(), settings.config())
            assertFalse(settings.importDone())
        }
}
