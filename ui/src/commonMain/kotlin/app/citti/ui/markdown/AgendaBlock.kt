package app.citti.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.citti.core.agenda.AgendaLayout
import app.citti.core.agenda.AgendaRequest
import app.citti.core.agenda.AgendaTexts
import app.citti.core.agenda.CalendarEvent
import app.citti.core.agenda.OptionError
import app.citti.core.api.ApiError
import app.citti.core.daily.MomentFormat
import app.citti.core.markdown.Block
import app.citti.core.markdown.ObsidianMarkdown
import app.citti.ui.TestTags
import app.citti.ui.components.colored
import app.citti.ui.note.AgendaBlockState
import app.citti.ui.note.AgendaProblem
import app.citti.ui.resources.Res
import app.citti.ui.resources.agenda_all_day
import app.citti.ui.resources.agenda_auth
import app.citti.ui.resources.agenda_calendar_failed
import app.citti.ui.resources.agenda_calendar_not_found
import app.citti.ui.resources.agenda_cant_load
import app.citti.ui.resources.agenda_empty
import app.citti.ui.resources.agenda_invalid_date
import app.citti.ui.resources.agenda_loading
import app.citti.ui.resources.agenda_no_calendar
import app.citti.ui.resources.agenda_no_match
import app.citti.ui.resources.agenda_no_title
import app.citti.ui.resources.agenda_not_ready
import app.citti.ui.resources.agenda_offline_updated
import app.citti.ui.resources.agenda_option_bad_days
import app.citti.ui.resources.agenda_option_bad_past
import app.citti.ui.resources.agenda_option_no_colon
import app.citti.ui.resources.agenda_option_unknown
import app.citti.ui.resources.agenda_past
import app.citti.ui.resources.agenda_refresh
import app.citti.ui.resources.agenda_updated
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * An Agenda block in Preview mode: the events of its days, each drawn from the Event template's Markdown with the
 * Preview's own block renderers (read-only: a task in a template is inert), a dot in the Event calendar's color, and
 * a status line with Refresh. The block's text is never changed. The calendar color is the user's data (ADR 0012);
 * everything else comes from the theme.
 */
@Composable
internal fun PreviewScope.AgendaBlock(
    block: Block.Code,
    state: AgendaBlockState,
) {
    val colors = CittiTheme.colors
    val meta = CittiTheme.typography.meta
    val texts =
        AgendaTexts(
            allDay = stringResource(Res.string.agenda_all_day),
            noTitle = stringResource(Res.string.agenda_no_title),
            empty = stringResource(Res.string.agenda_empty),
        )
    val request = state.request
    val view =
        remember(state, texts) {
            if (request == null) return@remember null
            val byHref = state.calendars.associateBy { it.href }
            val events =
                state.snapshot
                    ?.calendars
                    ?.flatMap { (href, known) ->
                        val calendar = byHref[href] ?: return@flatMap emptyList()
                        known.events.orEmpty().map { CalendarEvent(it, calendar.name, calendar.color) }
                    }.orEmpty()
            AgendaLayout.layout(events, state.options.options, state.display, request.firstDay, state.now, state.zone, state.locale, texts)
        }
    val itemBlocks = remember(view) { view?.days?.map { day -> day.items.map { ObsidianMarkdown.parse(it.markdown).blocks } } }
    val inert = PreviewScope(styles, readonly = true, onTask = {}, images = images)

    Indented(block.depth) {
        Column(Modifier.fillMaxWidth().padding(vertical = NotesSpacing.space2).testTag(TestTags.AGENDA_BLOCK)) {
            optionMessages(state).forEach { Message(it, colors.danger) }
            state.problem?.let { Message(problemMessage(it), colors.danger) }
            calendarMessages(state).forEach { Message(it, colors.danger) }
            if (view != null && itemBlocks != null && hasEvents(state)) {
                view.days.forEachIndexed { d, day ->
                    day.header?.let {
                        BasicText(
                            it,
                            style = meta.colored(muted = true),
                            modifier = Modifier.padding(top = NotesSpacing.space2).semantics { heading() },
                        )
                    }
                    day.items.forEachIndexed { i, item ->
                        val pastLabel = stringResource(Res.string.agenda_past)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.AGENDA_ITEM)
                                .semantics { if (item.dimmed) stateDescription = pastLabel },
                            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(NotesSpacing.space2).background(calendarColor(item.color) ?: colors.accent, CircleShape))
                            Column(Modifier.weight(1f)) { itemBlocks[d][i].forEach { inert.Block(it, muted = item.dimmed) } }
                        }
                    }
                    day.empty?.let { BasicText(it, style = CittiTheme.typography.body.colored(muted = true)) }
                }
                view.empty?.let { BasicText(it, style = CittiTheme.typography.body.colored(muted = true)) }
            }
            if (request != null) StatusLine(state, request)
        }
    }
}

/** True once at least one calendar of the block has events on the device (else only the status line shows). */
private fun hasEvents(state: AgendaBlockState): Boolean =
    state.snapshot
        ?.calendars
        ?.values
        ?.any { it.events != null } == true

@Composable
private fun PreviewScope.StatusLine(
    state: AgendaBlockState,
    request: AgendaRequest,
) {
    val known =
        state.snapshot
            ?.calendars
            ?.values
            .orEmpty()
    val oldest = known.mapNotNull { it.fetchedAt }.minOrNull()
    val offline = known.any { it.error is ApiError.Network }
    val text =
        when {
            oldest == null && state.snapshot?.refreshing != false && !offline -> stringResource(Res.string.agenda_loading)
            oldest == null && offline -> stringResource(Res.string.agenda_cant_load)
            oldest == null -> ""
            offline -> stringResource(Res.string.agenda_offline_updated, time(state, oldest))
            else -> stringResource(Res.string.agenda_updated, time(state, oldest))
        }
    Row(
        Modifier.fillMaxWidth().padding(top = NotesSpacing.space1).testTag(TestTags.AGENDA_STATUS),
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = CittiTheme.typography.meta.colored(muted = true), modifier = Modifier.weight(1f, fill = false))
        BasicText(
            stringResource(Res.string.agenda_refresh),
            style = CittiTheme.typography.meta.copy(color = CittiTheme.colors.accentInk),
            modifier =
                Modifier
                    .testTag(TestTags.AGENDA_REFRESH)
                    .clickable(role = Role.Button) { onAgendaRefresh(request) },
        )
    }
}

private fun time(
    state: AgendaBlockState,
    at: Instant,
): String {
    val local = at.toLocalDateTime(state.zone)
    return MomentFormat.format(local.date, local.time, state.display.timeFormat, state.locale)
}

@Composable
private fun Message(
    text: String,
    color: Color,
) {
    BasicText(text, style = CittiTheme.typography.meta.copy(color = color), modifier = Modifier.padding(vertical = NotesSpacing.space1))
}

@Composable
private fun optionMessages(state: AgendaBlockState): List<String> =
    state.options.errors.map { error ->
        when (error) {
            is OptionError.NoColon -> stringResource(Res.string.agenda_option_no_colon, error.line)
            is OptionError.BadDays -> stringResource(Res.string.agenda_option_bad_days, error.value)
            is OptionError.BadPast -> stringResource(Res.string.agenda_option_bad_past, error.value)
            is OptionError.UnknownKey -> stringResource(Res.string.agenda_option_unknown, error.key)
        }
    }

@Composable
private fun problemMessage(problem: AgendaProblem): String =
    when (problem) {
        is AgendaProblem.InvalidDate -> stringResource(Res.string.agenda_invalid_date, problem.value)
        AgendaProblem.NoCalendar -> stringResource(Res.string.agenda_no_calendar)
        is AgendaProblem.NoMatch -> stringResource(Res.string.agenda_no_match, problem.names.joinToString(", "))
    }

/** One line per failure that is not just being offline: the Account first, then each calendar. */
@Composable
private fun calendarMessages(state: AgendaBlockState): List<String> {
    val errors =
        state.snapshot
            ?.calendars
            .orEmpty()
            .mapNotNull { (href, known) -> known.error?.let { href to it } }
    if (errors.isEmpty()) return emptyList()
    val names = state.calendars.associate { it.href to it.name }
    val out = mutableListOf<String>()
    if (errors.any { it.second is ApiError.DavNotReady }) out += stringResource(Res.string.agenda_not_ready)
    if (errors.any { it.second is ApiError.Auth }) out += stringResource(Res.string.agenda_auth)
    for ((href, error) in errors) {
        val name = names[href] ?: href
        when (error) {
            is ApiError.Network, is ApiError.DavNotReady, is ApiError.Auth -> Unit
            ApiError.NotFound -> out += stringResource(Res.string.agenda_calendar_not_found, name)
            else -> out += stringResource(Res.string.agenda_calendar_failed, name)
        }
    }
    return out
}

/** `#rrggbb` as a color, or null. */
internal fun calendarColor(hex: String): Color? {
    if (hex.length != 7 || hex[0] != '#') return null
    val rgb = hex.substring(1).toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}
