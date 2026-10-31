package app.citti.ui.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiDialog
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_cancel
import app.citti.ui.resources.create
import app.citti.ui.resources.create_past_title
import app.citti.ui.resources.guard_banner
import app.citti.ui.resources.open_settings
import app.citti.ui.resources.template_missing
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** A past day without a Daily note was tapped: "Create daily note for 12 Oct?" with Cancel and Create. */
@Composable
fun CreatePastDayDialog(
    date: LocalDate,
    labels: PhoneCalendarLabels,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    CittiDialog(stringResource(Res.string.create_past_title, labels.dayMonth(date)), onDismiss = onCancel) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            CittiButton(stringResource(Res.string.action_cancel), onCancel, Modifier.weight(1f), kind = ButtonKind.Quiet)
            CittiButton(stringResource(Res.string.create), onCreate, Modifier.weight(1f))
        }
    }
}

/**
 * The Journal folder lies under an Unsynced prefix (a state saved before M6): "Daily notes need the Journal folder
 * “journal”, which is an Unsynced folder. Change one of them." with "Settings".
 */
@Composable
fun GuardBanner(
    journal: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        stringResource(Res.string.guard_banner, journal),
        BannerKind.Error,
        modifier,
        actionLabel = stringResource(Res.string.open_settings),
        onAction = onSettings,
    )
}

/** On a new Daily note whose Template was not found: "Template “templates/daily.md” not found" with "Settings". */
@Composable
fun TemplateMissingBanner(
    path: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        stringResource(Res.string.template_missing, path),
        BannerKind.Offline,
        modifier,
        actionLabel = stringResource(Res.string.open_settings),
        onAction = onSettings,
    )
}
