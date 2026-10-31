package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.resources.action_open_menu
import app.citti.ui.resources.search_clear
import app.citti.ui.resources.search_hint
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import org.jetbrains.compose.resources.stringResource

/** The leading button of the [SearchBar]: ≡ on List and Tree (opens the drawer), ← on Search. */
enum class SearchLeading { Menu, Back }

/**
 * The search field. With [onOpen] set (List and Tree) it is a fake field: tapping it calls [onOpen],
 * which pushes Search. Without it (Search) it takes focus, opens the keyboard, and shows ✕ while it
 * has text. The IME action is Search, which hides the keyboard.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    leading: SearchLeading,
    onLeading: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val colors = CittiTheme.colors
    val hint = stringResource(Res.string.search_hint)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CittiTheme.shape.gutter)
            .heightIn(min = NotesSize.iconButton)
            .background(colors.surface, RoundedCornerShape(CittiTheme.shape.control)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leading) {
            SearchLeading.Menu -> IconButton(CittiIcon.Menu, stringResource(Res.string.action_open_menu), onLeading)
            SearchLeading.Back -> IconButton(CittiIcon.Back, stringResource(Res.string.action_back), onLeading)
        }
        if (onOpen != null) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = NotesSize.iconButton)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = hint },
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    query.ifEmpty { hint },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = CittiTheme.typography.body.copy(color = if (query.isEmpty()) colors.inkFaint else colors.ink),
                )
            }
        } else {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
            // The String overload would restart the cursor at 0 when Search is rebuilt (Back from a Note).
            var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
            // The query changed from outside the field (✕): follow it, cursor at the end.
            if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != query) onQueryChange(it.text)
                },
                singleLine = true,
                textStyle = CittiTheme.typography.body.colored(),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = NotesSize.iconButton), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) BasicText(hint, maxLines = 1, style = CittiTheme.typography.body.copy(color = colors.inkFaint))
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(CittiIcon.Close, stringResource(Res.string.search_clear), { onQueryChange("") }, color = colors.inkMuted)
            }
        }
    }
}
