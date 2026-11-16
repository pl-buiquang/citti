package app.citti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import app.citti.core.agenda.AgendaTemplate
import app.citti.core.agenda.PastMode
import app.citti.core.api.ApiError
import app.citti.core.settings.EventCalendar
import app.citti.ui.TestTags
import app.citti.ui.appearance.SwitchRow
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.SegmentedControl
import app.citti.ui.components.colored
import app.citti.ui.markdown.calendarColor
import app.citti.ui.resources.Res
import app.citti.ui.resources.settings_agenda_allday
import app.citti.ui.resources.settings_agenda_calendar_color
import app.citti.ui.resources.settings_agenda_calendar_name
import app.citti.ui.resources.settings_agenda_calendars
import app.citti.ui.resources.settings_agenda_day_format
import app.citti.ui.resources.settings_agenda_days
import app.citti.ui.resources.settings_agenda_discover
import app.citti.ui.resources.settings_agenda_discover_auth
import app.citti.ui.resources.settings_agenda_discover_failed
import app.citti.ui.resources.settings_agenda_discover_found
import app.citti.ui.resources.settings_agenda_discover_not_ready
import app.citti.ui.resources.settings_agenda_discover_offline
import app.citti.ui.resources.settings_agenda_discovering
import app.citti.ui.resources.settings_agenda_example
import app.citti.ui.resources.settings_agenda_help
import app.citti.ui.resources.settings_agenda_hide_cancelled
import app.citti.ui.resources.settings_agenda_none
import app.citti.ui.resources.settings_agenda_past
import app.citti.ui.resources.settings_agenda_past_dim
import app.citti.ui.resources.settings_agenda_past_hide
import app.citti.ui.resources.settings_agenda_past_show
import app.citti.ui.resources.settings_agenda_template
import app.citti.ui.resources.settings_agenda_template_help
import app.citti.ui.resources.settings_agenda_time_format
import app.citti.ui.resources.theme_editor_off
import app.citti.ui.resources.theme_editor_on
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Agenda: the Event calendars of the Account (found with Discover calendars, never automatically), and the
 * defaults of every Agenda block. The calendars come from the signed-in Account: there is no server or password here.
 */
@Composable
internal fun ColumnScope.AgendaSection(
    state: AgendaUiState,
    controller: AgendaSettingsController,
) {
    val display = state.config.display
    Help(Res.string.settings_agenda_help)

    BasicText(stringResource(Res.string.settings_agenda_calendars), style = CittiTheme.typography.rowTitle.colored())
    CittiButton(
        stringResource(
            if (state.discover ==
                DiscoverState.Running
            ) {
                Res.string.settings_agenda_discovering
            } else {
                Res.string.settings_agenda_discover
            },
        ),
        controller::onDiscover,
        modifier = Modifier.testTag(TestTags.SETTINGS_AGENDA_DISCOVER),
        kind = ButtonKind.Quiet,
    )
    discoverText(state.discover)?.let { (text, failed) ->
        BasicText(
            text,
            style = CittiTheme.typography.meta.copy(color = if (failed) CittiTheme.colors.danger else CittiTheme.colors.inkMuted),
        )
    }
    if (state.config.calendars.isEmpty()) Help(Res.string.settings_agenda_none)
    for (calendar in state.config.calendars) CalendarRow(calendar, controller)

    TextSetting(
        display.template,
        stringResource(Res.string.settings_agenda_template),
        TestTags.SETTINGS_AGENDA_TEMPLATE,
        onSave = controller::onTemplate,
    )
    BasicText(
        stringResource(Res.string.settings_agenda_template_help, AgendaTemplate.FIELDS.joinToString(", ") { "{{$it}}" }),
        style = CittiTheme.typography.meta.colored(muted = true),
    )
    TextSetting(
        display.timeFormat,
        stringResource(Res.string.settings_agenda_time_format),
        TestTags.SETTINGS_AGENDA_TIME_FORMAT,
        onSave = controller::onTimeFormat,
    )
    Example(state.timeExample)
    TextSetting(
        display.dayFormat,
        stringResource(Res.string.settings_agenda_day_format),
        TestTags.SETTINGS_AGENDA_DAY_FORMAT,
        onSave = controller::onDayFormat,
    )
    Example(state.dayExample)
    TextSetting(
        display.days.toString(),
        stringResource(Res.string.settings_agenda_days),
        TestTags.SETTINGS_AGENDA_DAYS,
        keyboardType = KeyboardType.Number,
    ) { typed -> typed.toIntOrNull()?.let(controller::onDays) }

    BasicText(stringResource(Res.string.settings_agenda_past), style = CittiTheme.typography.meta.colored(muted = true))
    SegmentedControl(
        listOf(
            stringResource(Res.string.settings_agenda_past_show) to PastMode.SHOW,
            stringResource(Res.string.settings_agenda_past_dim) to PastMode.DIM,
            stringResource(Res.string.settings_agenda_past_hide) to PastMode.HIDE,
        ),
        display.past,
        controller::onPast,
    )
    ToggleRow(Res.string.settings_agenda_allday, display.showAllDay, TestTags.SETTINGS_AGENDA_ALLDAY, controller::onShowAllDay)
    ToggleRow(
        Res.string.settings_agenda_hide_cancelled,
        display.hideCancelled,
        TestTags.SETTINGS_AGENDA_HIDE_CANCELLED,
        controller::onHideCancelled,
    )
}

/** One Event calendar: enabled, its name and its color (blank fields go back to the server's). */
@Composable
private fun CalendarRow(
    calendar: EventCalendar,
    controller: AgendaSettingsController,
) {
    Column(
        Modifier.fillMaxWidth().testTag(TestTags.settingsAgendaCalendar(calendar.href)),
        verticalArrangement = Arrangement.spacedBy(NotesSpacing.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            // The calendar's own color: the user's data, not a UI color (ADR 0012).
            Box(Modifier.size(NotesSpacing.space3).background(calendarColor(calendar.color) ?: CittiTheme.colors.accent, CircleShape))
            Box(Modifier.weight(1f)) {
                ToggleRowText(calendar.name, calendar.enabled) { controller.onEnabled(calendar.href, it) }
            }
        }
        if (calendar.enabled) {
            TextSetting(
                calendar.rename.orEmpty(),
                stringResource(Res.string.settings_agenda_calendar_name),
                TestTags.settingsAgendaCalendarName(calendar.href),
                placeholder = calendar.serverName,
            ) { controller.onRename(calendar.href, it) }
            TextSetting(
                calendar.recolor.orEmpty(),
                stringResource(Res.string.settings_agenda_calendar_color),
                TestTags.settingsAgendaCalendarColor(calendar.href),
                placeholder = calendar.serverColor,
                keyboardType = KeyboardType.Ascii,
            ) { typed -> controller.onRecolor(calendar.href, typed.trim().ifEmpty { null }) }
        }
    }
}

@Composable
private fun ToggleRowText(
    name: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        name = name,
        checked = checked,
        onChange = onChange,
        overridden = false,
        onReset = {},
        stateText = stringResource(if (checked) Res.string.theme_editor_on else Res.string.theme_editor_off),
    )
}

/** A field that keeps what is typed (a blank field saves the default) and saves each change. */
@Composable
private fun TextSetting(
    saved: String,
    label: String,
    tag: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    var typed by remember(tag) { mutableStateOf(saved) }
    CittiTextField(
        value = typed,
        onValueChange = {
            typed = it
            onSave(it)
        },
        label = label,
        modifier = Modifier.testTag(tag),
        placeholder = placeholder,
        keyboardType = keyboardType,
    )
}

@Composable
private fun Example(text: String) {
    BasicText(stringResource(Res.string.settings_agenda_example, text), style = CittiTheme.typography.meta.colored(muted = true))
}

@Composable
private fun discoverText(state: DiscoverState): Pair<String, Boolean>? =
    when (state) {
        DiscoverState.Idle, DiscoverState.Running -> {
            null
        }

        is DiscoverState.Found -> {
            stringResource(Res.string.settings_agenda_discover_found, state.count) to false
        }

        is DiscoverState.Failed -> {
            when (state.error) {
                is ApiError.Network -> stringResource(Res.string.settings_agenda_discover_offline)
                is ApiError.Auth -> stringResource(Res.string.settings_agenda_discover_auth)
                ApiError.DavNotReady -> stringResource(Res.string.settings_agenda_discover_not_ready)
                else -> stringResource(Res.string.settings_agenda_discover_failed)
            } to true
        }
    }
