package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.citti.ui.TestTags
import app.citti.ui.resources.Res
import app.citti.ui.resources.menu_delete
import app.citti.ui.resources.menu_favorite_add
import app.citti.ui.resources.menu_favorite_remove
import app.citti.ui.resources.menu_insert_image
import app.citti.ui.resources.menu_move
import app.citti.ui.resources.menu_note_info
import app.citti.ui.resources.menu_rename
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The items of the Note ⋮ menu, in their order (`NoteMenu.md`). Delete is last, under a divider. */
enum class MenuItem { Favorite, Move, Rename, InsertImage, Info, Delete }

/**
 * The Note ⋮ menu (`NoteMenu.md`): anchored to the top-end corner of its parent (the ⋮ button sits there), on
 * `surfaceRaised` with the `line` outline of `shadow-overlay`, 44 dp items with a 20 dp icon. Favorite shows a filled
 * star when on; Delete is last, under a `line` divider, in `danger`. A tap outside dismisses it.
 */
@Composable
fun NoteMenu(
    items: List<MenuItem>,
    favorite: Boolean,
    onItem: (MenuItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier
                .padding(top = NotesSize.barHeight, end = NotesSpacing.space2)
                .width(IntrinsicSize.Max)
                .background(colors.surfaceRaised, shape)
                .border(Dp.Hairline, colors.line, shape)
                .padding(vertical = NotesSpacing.space1)
                .testTag(TestTags.NOTE_MENU),
        ) {
            for (item in items) {
                if (item == MenuItem.Delete && items.size > 1) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = NotesSpacing.space1)
                            .height(Dp.Hairline)
                            .background(colors.line),
                    )
                }
                MenuRow(item, favorite) { onItem(item) }
            }
        }
    }
}

@Composable
private fun MenuRow(
    item: MenuItem,
    favorite: Boolean,
    onClick: () -> Unit,
) {
    val colors = CittiTheme.colors
    val (label, icon) =
        when (item) {
            MenuItem.Favorite -> {
                if (favorite) {
                    stringResource(Res.string.menu_favorite_remove) to CittiIcon.StarFilled
                } else {
                    stringResource(Res.string.menu_favorite_add) to CittiIcon.Star
                }
            }

            MenuItem.Move -> {
                stringResource(Res.string.menu_move) to CittiIcon.Folder
            }

            MenuItem.Rename -> {
                stringResource(Res.string.menu_rename) to CittiIcon.Pencil
            }

            MenuItem.InsertImage -> {
                stringResource(Res.string.menu_insert_image) to CittiIcon.Image
            }

            MenuItem.Info -> {
                stringResource(Res.string.menu_note_info) to CittiIcon.Info
            }

            MenuItem.Delete -> {
                stringResource(Res.string.menu_delete) to CittiIcon.Trash
            }
        }
    val color = if (item == MenuItem.Delete) colors.danger else colors.ink
    val iconColor = if (item == MenuItem.Favorite && favorite) colors.favorite else color
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = NotesSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Icon(icon, Modifier.size(NotesSpacing.space5), color = iconColor)
        BasicText(label, style = CittiTheme.typography.ui.copy(color = color))
    }
}
