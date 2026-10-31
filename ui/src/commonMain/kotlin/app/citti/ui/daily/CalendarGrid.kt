package app.citti.ui.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import app.citti.ui.TestTags
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.IconButton
import app.citti.ui.components.ProgressRing
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.calendar_day_none
import app.citti.ui.resources.calendar_day_note
import app.citti.ui.resources.calendar_next_month
import app.citti.ui.resources.calendar_prev_month
import app.citti.ui.resources.calendar_today
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesRadius
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import org.jetbrains.compose.resources.stringResource

/** The dot under a day with a Daily note is 6 dp (spec §7); the scale has no size token for it. */
private val NoteDot = NotesRadius.radiusSm

/**
 * The Calendar header ([design-brief] §17): ← month and year (phone locale) →, then the "Today" chip back to the
 * current month, hidden when [month] is the current month.
 */
@Composable
fun CalendarHeader(
    month: YearMonth,
    currentMonth: YearMonth,
    labels: PhoneCalendarLabels,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        BasicText(
            labels.monthYear(month),
            Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style =
                CittiTheme.typography.rowTitle
                    .colored()
                    .copy(textAlign = TextAlign.Center),
        )
        IconButton(CittiIcon.Chevron, stringResource(Res.string.calendar_next_month), { onMonthChange(month.plusMonth()) })
        if (month != currentMonth) TodayChip { onMonthChange(currentMonth) }
    }
}

@Composable
private fun TodayChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        Modifier
            .testTag(TestTags.CALENDAR_TODAY)
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = NotesSpacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            stringResource(Res.string.calendar_today),
            Modifier
                .border(Dp.Hairline, CittiTheme.colors.line, shape)
                .padding(horizontal = NotesSpacing.space3, vertical = NotesSpacing.space1),
            style = CittiTheme.typography.ui.colored(),
        )
    }
}

/**
 * The month grid ([design-brief] §17, spec §7): weekday initials from [weekStart], then a [HorizontalPager] of
 * months, [CALENDAR_YEARS] years either side of [today]. Swiping reports the settled month through [onMonthChange];
 * a new [month] (the header's arrows, the Today chip) scrolls the pager to it. A day of the month shows its number,
 * a dot when it is in [noteDays], an outline when it is [today], and a progress ring while it is [busy]; the days of
 * the months around it are faint and not tappable.
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
            MonthPage(pages.monthAt(page), today, weekStart, noteDays, busy, labels, onDay)
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
) {
    val weeks = remember(month, weekStart) { monthWeeks(month, weekStart) }
    Column(Modifier.fillMaxWidth()) {
        for (week in weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (day in week) {
                    DayCell(day, day.date == today, day.date in noteDays, day.date == busy, labels, onDay, Modifier.weight(1f))
                }
            }
        }
        // Every month takes six rows, so the pager keeps its height from one month to the next.
        repeat(MAX_WEEKS - weeks.size) { Spacer(Modifier.height(NotesSize.touchMin)) }
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
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        modifier
            .height(NotesSize.touchMin)
            .clickable(role = Role.Button) { onDay(day.date) }
            .semantics { contentDescription = description }
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
