package app.citti.ui.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.TestTags
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.calendar_empty
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource

/** A Daily note of the month shown, as a row under the Calendar grid. */
data class DailyRow(
    val date: LocalDate,
    val localId: Long,
    val excerpt: String,
)

/**
 * The Daily notes of [month] under the grid, in the order given (newest first): the date in `meta` ("Sat 31 Oct",
 * phone locale) and the Excerpt in `rowTitle`. A plain column (a month has at most 31 rows), so it scrolls with the
 * grid. No rows: "No daily notes in October".
 */
@Composable
fun DailyList(
    month: YearMonth,
    rows: List<DailyRow>,
    labels: PhoneCalendarLabels,
    onOpen: (DailyRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        BasicText(
            stringResource(Res.string.calendar_empty, labels.monthName(month)),
            modifier
                .fillMaxWidth()
                .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space8),
            style =
                CittiTheme.typography.body
                    .colored(muted = true)
                    .copy(textAlign = TextAlign.Center),
        )
        return
    }
    Column(modifier.fillMaxWidth().testTag(TestTags.CALENDAR_ENTRIES)) {
        for (row in rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CittiTheme.shape.rowMin)
                    .clickable(role = Role.Button) { onOpen(row) }
                    .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
            ) {
                BasicText(labels.shortDate(row.date), maxLines = 1, style = CittiTheme.typography.meta.colored(muted = true))
                BasicText(
                    row.excerpt,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = CittiTheme.typography.rowTitle.colored(),
                )
            }
        }
    }
}
