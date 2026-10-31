package app.citti.core.daily

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.FolderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocaleDetectorTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val settings = DailySettings(db, ExclusionRepository(db, Dispatchers.Unconfined), Dispatchers.Unconfined)
    private var phone = "en-US"
    private val detector = LocaleDetector(db, settings) { phone }
    private var nextRemoteId = 1L

    /** A Daily note of the Journal folder whose content starts with [firstLine]. */
    private fun note(
        date: LocalDate,
        firstLine: String,
        folder: String = "journal",
    ) {
        db.noteQueries.insertNew(date.toString(), folder, "$firstLine\n\nWeek 44\n", null, 0, null, null)
    }

    /** A Daily note whose content isn't downloaded yet. */
    private fun noteWithoutContent(date: LocalDate) {
        db.noteQueries.upsertFromServer(nextRemoteId++, "e", date.toString(), "journal", false, 0, false, false)
    }

    private fun frenchTitleLine(date: LocalDate) = "# " + MomentFormat.format(date, LocalTime(0, 0), "dddd D MMMM YYYY", DateLocales.fr)

    private fun detected() = settings.config().localeDetected

    @Test
    fun frenchNotesAcrossMonthsDecideFrench() {
        (1..10).forEach { month -> LocalDate(2026, month, 5).let { note(it, frenchTitleLine(it)) } }
        assertEquals(DateLocales.fr, detector.localeForRendering(settings.config()))
        assertEquals("fr", detected())
    }

    @Test
    fun twoNotesDecideNothingAndTheRenderingFallsBackToThePhone() {
        listOf(LocalDate(2026, 9, 5), LocalDate(2026, 10, 5)).forEach { note(it, frenchTitleLine(it)) }
        phone = "de-DE"
        assertNull(detector.detect(settings.config()))
        assertEquals(DateLocales.de, detector.localeForRendering(settings.config()))
        assertNull(detected())
        phone = "ja"
        assertEquals(DateLocales.en, detector.localeForRendering(settings.config()))
        assertNull(detected())
    }

    @Test
    fun aTieGoesToThePhoneLanguageWhenItIsAmongThem() {
        (2..4).forEach { day -> note(LocalDate(2026, 11, day), "# $day November 2026") }
        phone = "nl-NL"
        assertEquals("nl", detector.detect(settings.config()))
        assertEquals("nl", detected())
    }

    @Test
    fun aTieWithoutThePhoneLanguageDecidesNothing() {
        (2..4).forEach { day -> note(LocalDate(2026, 11, day), "# $day November 2026") }
        phone = "fr-FR"
        assertNull(detector.detect(settings.config()))
        assertNull(detected())
    }

    @Test
    fun monthNamesMatchWholeWordsOnly() {
        (1..3).forEach { day -> note(LocalDate(2026, 5, day), "# Visite de la maison") }
        (1..3).forEach { day -> note(LocalDate(2026, 3, day), "# Le marsupial") }
        assertNull(detector.detect(settings.config()))
    }

    @Test
    fun monthNamesAreFolded() {
        note(LocalDate(2026, 8, 1), "# 1 aout 2026")
        note(LocalDate(2026, 8, 2), "# 2 AOÛT 2026")
        note(LocalDate(2026, 8, 3), "# dimanche 3 août 2026")
        assertEquals("fr", detector.detect(settings.config()))
    }

    @Test
    fun picksTheNewestNoteOfEachMonthBeforeFillingIn() {
        // Ten newest notes say nothing; only one note per month reaches the older French ones.
        (11..20).forEach { day -> note(LocalDate(2026, 10, day), "# Daily") }
        listOf(LocalDate(2026, 9, 5), LocalDate(2026, 8, 5), LocalDate(2026, 7, 5)).forEach {
            note(it, frenchTitleLine(it))
        }
        assertEquals("fr", detector.detect(settings.config()))
    }

    @Test
    fun atMostTenNotesAreRead() {
        // Ten newer months say nothing, so the three older French notes are past the cap.
        (3..12).forEach { month -> note(LocalDate(2026, month, 5), "# Daily") }
        listOf(LocalDate(2026, 2, 5), LocalDate(2026, 1, 5), LocalDate(2025, 12, 5)).forEach {
            note(it, frenchTitleLine(it))
        }
        assertNull(detector.detect(settings.config()))
    }

    @Test
    fun notesWithoutContentAreSkipped() {
        // Ten newer months have no content; were they picked, the three older French notes would be past the cap.
        (3..12).forEach { month -> noteWithoutContent(LocalDate(2026, month, 5)) }
        listOf(LocalDate(2026, 2, 5), LocalDate(2026, 1, 5), LocalDate(2025, 12, 5)).forEach {
            note(it, frenchTitleLine(it))
        }
        assertEquals("fr", detector.detect(settings.config()))
    }

    @Test
    fun otherFoldersAndOtherTitlesDontCount() {
        (1..3).forEach { month -> LocalDate(2026, month, 5).let { note(it, frenchTitleLine(it), folder = "notes") } }
        db.noteQueries.insertNew("Courses", "journal", "# janvier février mars", null, 0, null, null)
        db.noteQueries.insertNew("2026-W44", "journal", "# octobre", null, 0, null, null)
        assertNull(detector.detect(settings.config()))
    }

    @Test
    fun hiddenNotesCount() {
        (1..3).forEach { month -> LocalDate(2026, month, 5).let { note(it, frenchTitleLine(it)) } }
        driver.execute(null, "UPDATE note SET hidden = 1", 0)
        assertEquals("fr", detector.detect(settings.config()))
    }

    @Test
    fun aFormatWithNamesIsParsedWithEachLocale() {
        settings.setFormat("D MMMM YYYY")
        (1..3).forEach { month ->
            val date = LocalDate(2026, month, 5)
            val title = MomentFormat.format(date, LocalTime(0, 0), "D MMMM YYYY", DateLocales.fr)
            db.noteQueries.insertNew(title, "journal", "# $title", null, 0, null, null)
        }
        assertEquals("fr", detector.detect(settings.config()))
    }

    @Test
    fun aManualOverrideShortCircuits() {
        (1..10).forEach { month -> LocalDate(2026, month, 5).let { note(it, frenchTitleLine(it)) } }
        settings.setLocale("de")
        assertEquals(DateLocales.de, detector.localeForRendering(settings.config()))
        assertEquals("de", detected())
    }

    @Test
    fun aDetectedLocaleIsKeptUntilTheJournalFolderChanges() {
        (1..3).forEach { month -> LocalDate(2026, month, 5).let { note(it, frenchTitleLine(it)) } }
        assertEquals(DateLocales.fr, detector.localeForRendering(settings.config()))
        (4..9).forEach { month -> LocalDate(2026, month, 5).let { note(it, "# ${DateLocales.de.months[month - 1]}") } }
        assertEquals(DateLocales.fr, detector.localeForRendering(settings.config()))
        assertEquals(FolderResult.Saved("daily"), settings.setFolder("daily"))
        assertNull(detected())
        phone = "es"
        assertEquals(DateLocales.es, detector.localeForRendering(settings.config()))
        assertNull(detected())
    }
}
