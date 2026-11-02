package app.citti.core.daily

import app.citti.core.db.CittiDatabase
import app.citti.core.note.NameError
import app.citti.core.note.NameRules
import app.citti.core.note.NoteCreator
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What [DailyNotes.openOrCreate] did. */
sealed interface DailyResult {
    /** The Daily note was there (locally, or on the server after the Folder refresh). */
    data class Opened(
        val localId: Long,
    ) : DailyResult

    /** A new Daily note; [templateMissing] is the Template path when it was found nowhere (the note is empty). */
    data class Created(
        val localId: Long,
        val templateMissing: String?,
    ) : DailyResult

    /** The Journal folder lies under an Unsynced folder (a state saved before M6): nothing was created. */
    data object JournalUnsynced : DailyResult

    /**
     * The Date format writes [title], which isn't a valid Note title ([error]); nothing was created. [DailySettings]
     * refuses such a format, so only a value stored another way gets here.
     */
    data class InvalidTitle(
        val title: String,
        val error: NameError,
    ) : DailyResult
}

/**
 * Opens or creates the Daily note of a date (ADR 0006, through [NoteCreator]): a local hit opens at once, a miss
 * waits for the Folder refresh (capped), then the Template is rendered and the note created with `created_from` =
 * the rendered text, so an untouched offline duplicate is dropped at Sync.
 *
 * "Today" is the date of [clock] in [zone], read at each call (the app passes `AppConfig`'s clock and time zone).
 * Blocking database reads: call it off the main thread.
 */
class DailyNotes(
    db: CittiDatabase,
    private val settings: DailySettings,
    private val creator: NoteCreator,
    private val templates: TemplateSource,
    private val locales: LocaleDetector,
    /** `SyncEngine.fetchContent`: downloads the text of a SYNCED Note that isn't local yet. */
    private val fetchContent: suspend (Long) -> Boolean,
    exclusions: ExclusionRepository,
    private val appScope: CoroutineScope,
    private val clock: () -> Instant,
    private val zone: () -> TimeZone,
    /** Called after a Daily note was created (also offline), so the widgets redraw. */
    private val onCreated: () -> Unit = {},
) {
    private val notes = db.noteQueries
    private val guard = DailyGuard(settings, exclusions)

    /** The device's local date now. */
    fun today(): LocalDate = now().date

    /**
     * The title of [date]'s Daily note. The Date locale only matters when the format writes names or locale weeks;
     * then it is the rendering one (detection may run).
     */
    fun titleFor(
        date: LocalDate,
        config: DailyConfig = settings.config(),
    ): String = titleFor(Period.of(PeriodKind.DAY, date), config)

    /** The title of [period]'s note, in its kind's format. */
    fun titleFor(
        period: Period,
        config: DailyConfig = settings.config(),
    ): String = titleAt(period, now().time, config)

    private fun titleAt(
        period: Period,
        time: LocalTime,
        config: DailyConfig,
    ): String {
        val format = config.formatOf(period.kind)
        val locale = if (MomentFormat.usesLocale(format)) locales.localeForRendering(config) else DateLocales.forCode(null)
        return MomentFormat.format(period.start, time, format, locale)
    }

    /**
     * The date of the Daily note [title] of [folder], or null when it isn't one (another folder, or a title that
     * isn't the Date format exactly). For the Dropped wording and the widgets; runs no detection.
     */
    fun dateOf(
        folder: String,
        title: String,
        config: DailyConfig = settings.config(),
    ): LocalDate? = if (folder != config.folder) null else dailyDate(title, config.format, locales.readingLocales(config))

    /**
     * The Daily or Periodic note [title] of [folder] is, or null when it isn't one (another folder, or a title that
     * isn't one of the formats exactly). The Daily format is tried first. Runs no detection.
     */
    fun periodOf(
        folder: String,
        title: String,
        config: DailyConfig = settings.config(),
    ): Period? {
        if (folder != config.folder) return null
        val reading = locales.readingLocales(config)
        dailyDate(title, config.format, reading)?.let { return Period.of(PeriodKind.DAY, it) }
        return PeriodTitles.match(title, config, reading)
    }

    /** The local Daily note of [date], or null; no network, no detection. */
    fun localNote(
        date: LocalDate,
        config: DailyConfig = settings.config(),
    ): Long? = localNote(Period.of(PeriodKind.DAY, date), config)

    /** The local note of [period], or null; no network, no detection. */
    fun localNote(
        period: Period,
        config: DailyConfig = settings.config(),
    ): Long? {
        val time = now().time
        val format = config.formatOf(period.kind)
        return locales
            .readingLocales(config)
            .map { MomentFormat.format(period.start, time, format, it) }
            .distinct()
            .firstNotNullOfOrNull { notes.selectExactByFolderAndTitle(config.folder, it).executeAsOneOrNull() }
    }

    /** Opens [date]'s Daily note, or renders the Template and creates it. */
    suspend fun openOrCreate(date: LocalDate): DailyResult = openOrCreate(Period.of(PeriodKind.DAY, date))

    /** Opens [period]'s note (a Daily note or a Periodic note), or renders its Template and creates it. */
    suspend fun openOrCreate(period: Period): DailyResult {
        if (guard.current() != null) return DailyResult.JournalUnsynced
        val config = settings.config()
        val folder = config.folder
        val time = now().time
        val title = titleAt(period, time, config)
        NameRules.validateTitle(title)?.let { return DailyResult.InvalidTitle(title, it) }
        when (val check = creator.check(folder, title)) {
            is NoteCreator.Check.Exists -> {
                if (notes.selectByLocalId(check.localId).executeAsOneOrNull()?.content == null) {
                    appScope.launch { fetchContent(check.localId) }
                }
                return DailyResult.Opened(check.localId)
            }

            NoteCreator.Check.Free, NoteCreator.Check.Unchecked -> {
                Unit
            }
        }
        val template = templates.load(config.templateOf(period.kind))
        val locale = locales.localeForRendering(settings.config())
        val rendered =
            TemplateRenderer.render(
                (template as? TemplateText.Found)?.text.orEmpty(),
                period.start,
                time,
                config.formatOf(period.kind),
                locale,
            )
        val localId = creator.create(folder, title, rendered)
        onCreated()
        return DailyResult.Created(localId, (template as? TemplateText.Missing)?.path)
    }

    private fun now(): LocalDateTime = clock().toLocalDateTime(zone())
}
