package app.citti.ui.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.citti.core.daily.Period
import app.citti.core.daily.PeriodKind
import app.citti.ui.TestTags
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.IconButton
import app.citti.ui.components.ProgressRing
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.calendar_day_none
import app.citti.ui.resources.calendar_day_note
import app.citti.ui.resources.calendar_month_note
import app.citti.ui.resources.calendar_next_month
import app.citti.ui.resources.calendar_open_week_note
import app.citti.ui.resources.calendar_prev_month
import app.citti.ui.resources.calendar_quarter_note
import app.citti.ui.resources.calendar_today
import app.citti.ui.resources.calendar_week_note
import app.citti.ui.resources.calendar_year_note
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesRadius
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.number
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import org.jetbrains.compose.resources.stringResource

/** The dot under a day with a Daily note is 6 dp (spec §7); the scale has no size token for it. */
private val NoteDot = NotesRadius.radiusSm

/**
 * The Calendar header ([design-brief] §17): ← month, year (phone locale order) and, with [showQuarter], the quarter
 * chip →, then the "Today" chip back to the current month, hidden when [month] is the current month. The month, the
 * year and the quarter are buttons: each opens (or creates) its note through [onPeriod]; [busy] is the period being
 * opened, which shows a progress ring in its place.
 */
@Composable
fun CalendarHeader(
    month: YearMonth,
    currentMonth: YearMonth,
    labels: PhoneCalendarLabels,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    showQuarter: Boolean = false,
    busy: Period? = null,
    onPeriod: (Period) -> Unit = {},
) {
    val monthPeriod = Period.of(PeriodKind.MONTH, month.firstDay)
    val yearPeriod = Period.of(PeriodKind.YEAR, month.firstDay)
    val quarterPeriod = Period.of(PeriodKind.QUARTER, month.firstDay)
    val monthTitle = labels.monthTitle(month)
    val yearTitle = labels.year(month)
    val quarterTitle = "Q${(month.month.number - 1) / MONTHS_IN_QUARTER + 1}"
    val monthTarget: @Composable (Modifier) -> Unit = { target ->
        PeriodTarget(
            monthTitle,
            stringResource(Res.string.calendar_month_note, labels.monthYear(month)),
            busy == monthPeriod,
            TestTags.CALENDAR_MONTH_NOTE,
            target,
        ) { onPeriod(monthPeriod) }
    }
    val yearTarget: @Composable (Modifier) -> Unit = { target ->
        PeriodTarget(
            yearTitle,
            stringResource(Res.string.calendar_year_note, yearTitle),
            busy == yearPeriod,
            TestTags.CALENDAR_YEAR_NOTE,
            target,
        ) { onPeriod(yearPeriod) }
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron points to the end; turned around it points to the start.
        IconButton(
            CittiIcon.Chevron,
            stringResource(Res.string.calendar_prev_month),
            { onMonthChange(month.minusMonth()) },
            Modifier.rotate(180f),
        )
        Row(
            Modifier.weight(1f).semantics { heading() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The month gives way first when the phone is narrow.
            if (labels.monthFirst) {
                monthTarget(Modifier.weight(1f, fill = false))
                yearTarget(Modifier)
            } else {
                yearTarget(Modifier)
                monthTarget(Modifier.weight(1f, fill = false))
            }
            if (showQuarter) {
                HeaderChip(
                    quarterTitle,
                    TestTags.CALENDAR_QUARTER_NOTE,
                    stringResource(Res.string.calendar_quarter_note, quarterTitle),
                    busy == quarterPeriod,
                ) { onPeriod(quarterPeriod) }
            }
        }
        IconButton(CittiIcon.Chevron, stringResource(Res.string.calendar_next_month), { onMonthChange(month.plusMonth()) })
        if (month != currentMonth) {
            HeaderChip(stringResource(Res.string.calendar_today), TestTags.CALENDAR_TODAY, null, false) { onMonthChange(currentMonth) }
        }
    }
}

private const val MONTHS_IN_QUARTER = 3

/** The month or the year of the header: plain title text that opens its note. */
@Composable
private fun PeriodTarget(
    text: String,
    description: String,
    busy: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .testTag(tag)
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = NotesSpacing.space1),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            ProgressRing()
        } else {
            BasicText(
                text,
                Modifier.clearAndSetSemantics {},
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style =
                    CittiTheme.typography.rowTitle
                        .colored()
                        .copy(textAlign = TextAlign.Center),
            )
        }
    }
}

/** A bordered chip of the header: Today, and the quarter. [description] is what TalkBack reads, [text] when null. */
@Composable
private fun HeaderChip(
    text: String,
    tag: String,
    description: String?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        Modifier
            .testTag(tag)
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick)
            .let { if (description != null) it.semantics { contentDescription = description } else it }
            .padding(start = NotesSpacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            ProgressRing()
        } else {
            BasicText(
                text,
                Modifier
                    .border(Dp.Hairline, CittiTheme.colors.line, shape)
                    .padding(horizontal = NotesSpacing.space3, vertical = NotesSpacing.space1)
                    .let { if (description != null) it.clearAndSetSemantics {} else it },
                style = CittiTheme.typography.ui.colored(),
            )
        }
    }
}

/**
 * The month grid ([design-brief] §17, spec §7): weekday initials from [weekStart], then a [HorizontalPager] of
 * months, [CALENDAR_YEARS] years either side of [today]. Swiping reports the settled month through [onMonthChange];
 * a new [month] (the header's arrows, the Today chip) scrolls the pager to it. A day of the month shows its number,
 * a dot when it is in [noteDays], an outline when it is [today], and a progress ring while it is [busy]; the days of
 * the months around it are faint and not tappable. A long press on a day opens the note of its week, and with
 * [showWeeks] a column of week numbers at the start of the rows does too: both go through [onPeriod], and a week
 * being opened ([busyPeriod]) shows a progress ring in its number.
 */
@Composable
fun CalendarGrid(
    month: YearMonth,
    today: LocalDate,
    weekStart: DayOfWeek,
    noteDays: Set<LocalDate>,
    labels: PhoneCalendarLabels,
    onMonthChange: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    busy: LocalDate? = null,
    showWeeks: Boolean = false,
    busyPeriod: Period? = null,
    onPeriod: (Period) -> Unit = {},
) {
    // Fixed for the life of the grid: page numbers must not move when today crosses into another month.
    val pages = remember { CalendarPages(today.yearMonth) }
    val pager = rememberPagerState(initialPage = pages.pageOf(month)) { pages.count }
    val shown by rememberUpdatedState(month)
    val report by rememberUpdatedState(onMonthChange)
    LaunchedEffect(month) {
        val target = pages.pageOf(month)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val settled = pages.monthAt(page)
            if (settled != shown) report(settled)
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter)) {
        Row(Modifier.fillMaxWidth().padding(vertical = NotesSpacing.space1)) {
            if (showWeeks) Spacer(Modifier.weight(WEEK_COLUMN_WEIGHT))
            for (day in weekdays(weekStart)) {
                val name = labels.weekdayName(day)
                BasicText(
                    labels.weekdayInitial(day),
                    Modifier.weight(1f).clearAndSetSemantics { contentDescription = name },
                    maxLines = 1,
                    style =
                        CittiTheme.typography.meta
                            .colored(muted = true)
                            .copy(textAlign = TextAlign.Center),
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().testTag(TestTags.CALENDAR_GRID)) { page ->
            MonthPage(pages.monthAt(page), today, weekStart, noteDays, busy, labels, onDay, showWeeks, busyPeriod, onPeriod)
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    today: LocalDate,
    weekStart: DayOfWeek,
    noteDays: Set<LocalDate>,
    busy: LocalDate?,
    labels: PhoneCalendarLabels,
    onDay: (LocalDate) -> Unit,
    showWeeks: Boolean,
    busyPeriod: Period?,
    onPeriod: (Period) -> Unit,
) {
    val weeks = remember(month, weekStart) { monthWeeks(month, weekStart) }
    Column(Modifier.fillMaxWidth()) {
        for (week in weeks) {
            // The row belongs to the week of its first day (a Sunday start gives the ISO week that ends that day).
            val weekPeriod = Period.of(PeriodKind.WEEK, week.first().date)
            Row(Modifier.fillMaxWidth()) {
                if (showWeeks) WeekCell(weekPeriod, busyPeriod == weekPeriod, onPeriod, Modifier.weight(WEEK_COLUMN_WEIGHT))
                for (day in week) {
                    DayCell(
                        day,
                        day.date == today,
                        day.date in noteDays,
                        day.date == busy,
                        labels,
                        onDay,
                        { onPeriod(weekPeriod) },
                        Modifier.weight(1f),
                    )
                }
            }
        }
        // Every month takes six rows, so the pager keeps its height from one month to the next.
        repeat(MAX_WEEKS - weeks.size) { Spacer(Modifier.height(NotesSize.touchMin)) }
    }
}

/** The week number column is this wide next to a day's weight of 1. */
private const val WEEK_COLUMN_WEIGHT = 0.7f

/** The ISO week number at the start of a grid row: tapping it opens the Week note. */
@Composable
private fun WeekCell(
    week: Period,
    busy: Boolean,
    onPeriod: (Period) -> Unit,
    modifier: Modifier,
) {
    val number = isoWeekNumber(week.start).toString()
    val description = stringResource(Res.string.calendar_week_note, number)
    Box(
        modifier
            .testTag(TestTags.calendarWeek(week.start.toString()))
            .height(NotesSize.touchMin)
            .clickable(role = Role.Button) { onPeriod(week) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            ProgressRing()
        } else {
            BasicText(
                number,
                Modifier.clearAndSetSemantics {},
                maxLines = 1,
                style =
                    CittiTheme.typography.meta
                        .colored(muted = true)
                        .copy(textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun DayCell(
    day: GridDay,
    isToday: Boolean,
    hasNote: Boolean,
    busy: Boolean,
    labels: PhoneCalendarLabels,
    onDay: (LocalDate) -> Unit,
    onWeek: () -> Unit,
    modifier: Modifier,
) {
    val colors = CittiTheme.colors
    val number = day.date.day.toString()
    if (!day.inMonth) {
        Box(modifier.height(NotesSize.touchMin).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            BasicText(number, style = CittiTheme.typography.rowTitle.copy(color = colors.inkFaint))
        }
        return
    }
    val description =
        stringResource(if (hasNote) Res.string.calendar_day_note else Res.string.calendar_day_none, labels.dayMonthLong(day.date))
    val longClickLabel = stringResource(Res.string.calendar_open_week_note)
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        modifier
            .height(NotesSize.touchMin)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onWeek,
                onClick = { onDay(day.date) },
            ).semantics { contentDescription = description }
            .padding(NotesSpacing.space1)
            .let { if (isToday) it.border(Dp.Hairline, colors.accent, shape) else it },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space1 / 2, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (busy) {
                ProgressRing()
            } else {
                BasicText(number, maxLines = 1, style = CittiTheme.typography.rowTitle.colored())
            }
            Box(Modifier.size(NoteDot).let { if (hasNote) it.background(colors.accent, CircleShape) else it })
        }
    }
}
