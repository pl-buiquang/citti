package app.citti.ui.daily

import app.citti.core.daily.DailyNotes
import app.citti.core.daily.DailyResult
import app.citti.core.daily.Period
import app.citti.core.daily.PeriodKind
import app.citti.ui.components.DroppedDaily
import app.citti.ui.components.Snack
import app.citti.ui.nav.NoteNotice
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** The Daily notes side of [TodayAction] (the app's [CoreDailyGateway]; fakes in tests). */
interface DailyGateway {
    /** The device's local date now, read at each call. */
    fun today(): LocalDate

    /** The local note of [period] (a Daily note or a Periodic note), or null; no network. */
    suspend fun localNote(period: Period): Long?

    /** Opens or creates the note of [period] ([DailyNotes.openOrCreate]); null while signed out. */
    suspend fun openOrCreate(period: Period): DailyResult?

    /** The Reading position of note [localId] ([Route.Note.startLine]), or null when it has none. */
    suspend fun viewLine(localId: Long): Int?

    /** The date of the Daily note [title] of [folder], or null when it isn't one. */
    suspend fun dateOf(
        folder: String,
        title: String,
    ): LocalDate?
}

/** [DailyGateway] over the signed-in account's [DailyNotes] ([notes] is null while signed out), blocking work on [io]. */
class CoreDailyGateway(
    private val notes: () -> DailyNotes?,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val viewLine: suspend (localId: Long) -> Int? = { null },
) : DailyGateway {
    override fun today(): LocalDate = today.invoke()

    override suspend fun localNote(period: Period): Long? = withContext(io) { notes()?.localNote(period) }

    override suspend fun openOrCreate(period: Period): DailyResult? = withContext(io) { notes()?.openOrCreate(period) }

    override suspend fun viewLine(localId: Long): Int? = viewLine.invoke(localId)

    override suspend fun dateOf(
        folder: String,
        title: String,
    ): LocalDate? = withContext(io) { notes()?.dateOf(folder, title) }
}

/**
 * Opens or creates a Daily or Periodic note from the Today button, the Calendar and (later) the `citti://daily` deep
 * link: it lives with the signed-in back stack, not with a screen. [busy] is the period being opened (the tapped
 * button shows inline progress); a tap while it is set does nothing. An opened note is pushed as is (caret at the end, no
 * keyboard); a created one opens focused, with the Template missing banner when its Template was found nowhere. While
 * the Journal folder is an Unsynced folder, nothing is created and a snackbar leads to Settings.
 */
class TodayAction(
    private val scope: CoroutineScope,
    private val gateway: DailyGateway,
    private val push: (Route) -> Unit,
    private val notify: (Snack) -> Unit,
    /** Makes the route the whole back stack (a widget entry: Back then leaves the app). */
    private val reset: (Route) -> Unit = push,
) {
    private val busyPeriod = MutableStateFlow<Period?>(null)
    private val confirmPeriod = MutableStateFlow<Period?>(null)

    /** The period being opened or created, or null. */
    val busy: StateFlow<Period?> = busyPeriod.asStateFlow()

    /** A past period without a note that waits for "Create daily note for 12 Oct?" ([confirmCreate]), or null. */
    val confirm: StateFlow<Period?> = confirmPeriod.asStateFlow()

    /** The device's local date now. */
    fun today(): LocalDate = gateway.today()

    /**
     * The Today button: today's Daily note. With [resume] (the Daily note widget), an existing note opens at its Reading
     * position; a created one still opens focused. With [entry], the note becomes the whole back stack.
     */
    fun openToday(
        resume: Boolean = false,
        entry: Boolean = false,
    ) = open(Period.of(PeriodKind.DAY, gateway.today()), resume = resume, entry = entry)

    /**
     * Opens or creates [date]'s Daily note. With [confirmPast], a past day without a local Daily note is not created
     * yet: it goes to [confirm] (the Calendar's tap rule).
     */
    fun open(
        date: LocalDate,
        confirmPast: Boolean = false,
        resume: Boolean = false,
    ) = open(Period.of(PeriodKind.DAY, date), confirmPast, resume)

    /**
     * Opens or creates the note of [period]. With [confirmPast], a period that is over and has no local note is not
     * created yet: it goes to [confirm] (the Calendar's tap rule, for days and Periodic notes alike).
     */
    fun open(
        period: Period,
        confirmPast: Boolean = false,
        resume: Boolean = false,
        entry: Boolean = false,
    ) {
        val show = if (entry) reset else push
        if (!busyPeriod.compareAndSet(null, period)) return
        scope.launch {
            try {
                if (confirmPast && period.isOver(gateway.today()) && gateway.localNote(period) == null) {
                    confirmPeriod.value = period
                    return@launch
                }
                confirmPeriod.value = null
                when (val result = gateway.openOrCreate(period)) {
                    is DailyResult.Opened -> {
                        val line = if (resume) gateway.viewLine(result.localId) else null
                        show(Route.Note(result.localId, startLine = line))
                    }

                    is DailyResult.Created -> {
                        val notice = result.templateMissing?.let { NoteNotice.TemplateMissing(it) }
                        show(Route.Note(result.localId, preview = false, focus = true, notice = notice))
                    }

                    DailyResult.JournalUnsynced -> {
                        notify(Snack.JournalUnsynced)
                    }

                    // Signed out, or a Date format stored around DailySettings' refusal: nothing to open.
                    is DailyResult.InvalidTitle, null -> {
                        Unit
                    }
                }
            } finally {
                busyPeriod.value = null
            }
        }
    }

    /** "Create" in the past day dialog. */
    fun confirmCreate() {
        val period = confirmPeriod.value ?: return
        if (busyPeriod.value != null) return
        confirmPeriod.value = null
        open(period)
    }

    /** "Cancel" in the past day dialog. */
    fun cancelCreate() {
        confirmPeriod.value = null
    }
}

/**
 * The Daily wording of the Dropped snackbar for the dropped [title] of [folder]: [DroppedDaily.Today] for today's
 * Daily note, [DroppedDaily.Day] ("12 Oct", from [labels]) for another day's, null when it isn't a Daily note.
 */
suspend fun DailyGateway.droppedDaily(
    folder: String,
    title: String,
    labels: PhoneCalendarLabels,
): DroppedDaily? {
    val date = dateOf(folder, title) ?: return null
    return if (date == today()) DroppedDaily.Today else DroppedDaily.Day(labels.dayMonth(date))
}
