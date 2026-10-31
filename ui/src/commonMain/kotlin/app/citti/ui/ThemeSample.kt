package app.citti.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import app.citti.ui.theme.ThemeFamily
import app.citti.ui.theme.ThemeMode

/** M1 proof that tokens drive the look: pick a theme and mode, see a row and a note snippet. */
@Composable
fun ThemeSampleScreen() {
    var family by rememberSaveable { mutableStateOf(ThemeFamily.Signal) }
    var mode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    CittiTheme(family, mode) {
        Column(
            Modifier
                .fillMaxSize()
                .background(CittiTheme.colors.bg)
                .safeDrawingPadding()
                .padding(vertical = NotesSpacing.space6),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space4),
        ) {
            Gutter { BasicText("Themes", style = CittiTheme.typography.title.colored()) }
            Gutter { Picker(ThemeFamily.entries, family) { family = it } }
            Gutter { Picker(ThemeMode.entries, mode) { mode = it } }
            NoteRow(
                title = "Weekly planning",
                folder = "Projects/citti",
                date = "2h",
                favorite = true,
                conflict = false,
            )
            NoteRow(
                title = "Meeting notes (conflict 2026-10-31)",
                folder = "Work/notes",
                date = "1d",
                favorite = false,
                conflict = true,
            )
            Gutter { NoteSnippet() }
        }
    }
}

@Composable
private fun TextStyle.colored(muted: Boolean = false): TextStyle =
    copy(color = if (muted) CittiTheme.colors.inkMuted else CittiTheme.colors.ink)

@Composable
private fun Gutter(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter)) { content() }
}

@Composable
private fun <T : Enum<T>> Picker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
        options.forEach { option ->
            val active = option == selected
            Column(
                Modifier
                    .heightIn(min = NotesSize.touchMin)
                    .background(
                        if (active) CittiTheme.colors.accent else CittiTheme.colors.surface,
                        RoundedCornerShape(CittiTheme.shape.control),
                    ).clickable { onSelect(option) }
                    .padding(horizontal = NotesSpacing.space3),
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    option.name,
                    style =
                        CittiTheme.typography.ui.copy(
                            color = if (active) CittiTheme.colors.onAccent else CittiTheme.colors.ink,
                        ),
                )
            }
        }
    }
}

@Composable
private fun NoteRow(
    title: String,
    folder: String,
    date: String,
    favorite: Boolean,
    conflict: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CittiTheme.shape.rowMin)
            .padding(horizontal = CittiTheme.shape.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.rowTitle.colored())
            BasicText(folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.meta.colored(muted = true))
        }
        if (favorite) StateLabel("Favorite", CittiTheme.colors.favorite)
        if (conflict) StateLabel("Conflict", CittiTheme.colors.conflict)
        BasicText(date, style = CittiTheme.typography.meta.colored(muted = true))
    }
}

/** State is words, not only color. */
@Composable
private fun StateLabel(
    text: String,
    color: Color,
) {
    BasicText(
        if (CittiTheme.shape.labelsUppercase) text.uppercase() else text,
        style = CittiTheme.typography.label.copy(color = color),
    )
}

@Composable
private fun NoteSnippet() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CittiTheme.colors.surface, RoundedCornerShape(CittiTheme.shape.media))
            .padding(NotesSpacing.space4),
    ) {
        BasicText(
            "## Plan",
            style = CittiTheme.typography.editor.copy(color = CittiTheme.colors.inkFaint),
        )
        BasicText(
            "Ship the scaffold, then sync. The note is the hero.",
            style = CittiTheme.typography.body.colored(),
        )
    }
}
