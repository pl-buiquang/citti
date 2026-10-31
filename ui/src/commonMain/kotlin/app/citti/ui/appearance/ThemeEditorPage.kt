package app.citti.ui.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.citti.ui.TestTags
import app.citti.ui.components.CittiTopBar
import app.citti.ui.resources.Res
import app.citti.ui.resources.settings_back
import app.citti.ui.resources.theme_editor_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun ThemeEditorPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(TestTags.THEME_EDITOR)) {
        CittiTopBar(
            title = stringResource(Res.string.theme_editor_title),
            actionLabel = stringResource(Res.string.settings_back),
            onAction = onBack,
        )
    }
}
