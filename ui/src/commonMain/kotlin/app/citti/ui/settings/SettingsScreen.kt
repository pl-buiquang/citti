package app.citti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import app.citti.core.markdown.ToolbarAction
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.IMAGE_CACHE_CHOICES_MB
import app.citti.core.settings.RemoteImages
import app.citti.core.settings.SYNC_INTERVAL_CHOICES
import app.citti.core.settings.SyncNetwork
import app.citti.ui.TestTags
import app.citti.ui.appearance.AppearanceActions
import app.citti.ui.appearance.AppearanceSection
import app.citti.ui.appearance.StepButton
import app.citti.ui.appearance.ThemeEditorActions
import app.citti.ui.appearance.ThemeEditorPage
import app.citti.ui.appearance.ThemeEditorState
import app.citti.ui.browse.statusText
import app.citti.ui.browse.syncLine
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiTextButton
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.PickerFolders
import app.citti.ui.components.colored
import app.citti.ui.markdown.label
import app.citti.ui.markdown.mark
import app.citti.ui.resources.Res
import app.citti.ui.resources.exclusion_journal_refused
import app.citti.ui.resources.settings_account
import app.citti.ui.resources.settings_add_folder
import app.citti.ui.resources.settings_appearance
import app.citti.ui.resources.settings_back
import app.citti.ui.resources.settings_cancel
import app.citti.ui.resources.settings_confirm_delete
import app.citti.ui.resources.settings_confirm_unsynced_body
import app.citti.ui.resources.settings_confirm_unsynced_pending
import app.citti.ui.resources.settings_confirm_unsynced_title
import app.citti.ui.resources.settings_daily
import app.citti.ui.resources.settings_editor
import app.citti.ui.resources.settings_editor_toolbar
import app.citti.ui.resources.settings_editor_toolbar_help
import app.citti.ui.resources.settings_exclusions
import app.citti.ui.resources.settings_folder_add
import app.citti.ui.resources.settings_folder_blank
import app.citti.ui.resources.settings_folder_listed
import app.citti.ui.resources.settings_folder_path_hint
import app.citti.ui.resources.settings_folder_path_label
import app.citti.ui.resources.settings_hidden_help
import app.citti.ui.resources.settings_hidden_title
import app.citti.ui.resources.settings_image_cache_clear
import app.citti.ui.resources.settings_image_cache_clear_confirm
import app.citti.ui.resources.settings_image_cache_clear_title
import app.citti.ui.resources.settings_image_cache_size
import app.citti.ui.resources.settings_image_cache_used
import app.citti.ui.resources.settings_image_remote
import app.citti.ui.resources.settings_image_remote_always
import app.citti.ui.resources.settings_image_remote_ask
import app.citti.ui.resources.settings_image_remote_help
import app.citti.ui.resources.settings_images
import app.citti.ui.resources.settings_interval
import app.citti.ui.resources.settings_interval_hours
import app.citti.ui.resources.settings_interval_manual
import app.citti.ui.resources.settings_interval_minutes
import app.citti.ui.resources.settings_known_folders
import app.citti.ui.resources.settings_network
import app.citti.ui.resources.settings_network_any
import app.citti.ui.resources.settings_network_unmetered
import app.citti.ui.resources.settings_no_known_folders
import app.citti.ui.resources.settings_none
import app.citti.ui.resources.settings_pick_hidden_title
import app.citti.ui.resources.settings_pick_unsynced_title
import app.citti.ui.resources.settings_remove
import app.citti.ui.resources.settings_server
import app.citti.ui.resources.settings_sign_in_again
import app.citti.ui.resources.settings_sign_in_again_help
import app.citti.ui.resources.settings_sign_out
import app.citti.ui.resources.settings_sign_out_body
import app.citti.ui.resources.settings_sign_out_title
import app.citti.ui.resources.settings_sync
import app.citti.ui.resources.settings_sync_now
import app.citti.ui.resources.settings_title
import app.citti.ui.resources.settings_toolbar_move_down
import app.citti.ui.resources.settings_toolbar_move_up
import app.citti.ui.resources.settings_unsynced_help
import app.citti.ui.resources.settings_unsynced_title
import app.citti.ui.resources.settings_user
import app.citti.ui.resources.settings_version
import app.citti.ui.resources.size_gb
import app.citti.ui.resources.size_mb
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Callbacks of [SettingsScreen]; the app binds them to a [SettingsController]. */
class SettingsActions(
    val onBack: () -> Unit,
    val onInterval: (Int) -> Unit,
    val onNetwork: (SyncNetwork) -> Unit,
    val onImageCacheMb: (Int) -> Unit,
    val onRemoteImages: (RemoteImages) -> Unit,
    val onToolbarToggle: (ToolbarAction) -> Unit = {},
    val onToolbarMove: (ToolbarAction, Boolean) -> Unit = { _, _ -> },
    val onAskClearImages: () -> Unit,
    val onSyncNow: () -> Unit,
    val onOpenSection: (SettingsSection) -> Unit,
    val onOpenPicker: (ExclusionKind) -> Unit,
    val onAddFolder: (ExclusionKind, String) -> Unit,
    val onRemoveFolder: (ExclusionKind, String) -> Unit,
    val onClearFolderError: () -> Unit,
    val onAskSignOut: () -> Unit,
    val onSignInAgain: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val daily: DailySettingsActions,
    val appearance: AppearanceActions,
    val editor: ThemeEditorActions,
)

/**
 * Settings: a menu whose entries open the pages Account, Sync, Exclusions, Images, Appearance and Daily notes
 * (design brief 12), with the app version under it.
 * [folders] feeds the Journal folder picker, collected only while it is open.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    editor: ThemeEditorState,
    nowSeconds: () -> Long,
    folders: Flow<PickerFolders>,
    /** The line under the menu: the release and the commit it was built from. */
    appVersion: String,
    actions: SettingsActions,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        when (val page = state.page) {
            SettingsPage.Home -> {
                SettingsHome(appVersion, actions)
            }

            is SettingsPage.Section -> {
                SettingsSectionPage(page.section, state, nowSeconds, actions)
            }

            is SettingsPage.PickFolder -> {
                FolderPicker(page.kind, state, actions)
            }

            is SettingsPage.PickJournal -> {
                JournalFolderPage(page, folders, actions.daily)
            }

            is SettingsPage.PickTemplate -> {
                TemplatePickerPage(
                    page,
                    state.daily.config.templateOf(page.kind),
                    actions.onBack,
                    actions.daily,
                )
            }

            SettingsPage.ThemeEditor -> {
                ThemeEditorPage(editor, actions.editor)
            }
        }
        state.confirm?.let { ConfirmDialog(it, actions) }
        state.daily.importReport?.let { ImportReportDialog(it, actions.daily.onDismissImportReport) }
    }
}

/** The menu: one entry per [SettingsSection], and the app version under them. */
@Composable
private fun SettingsHome(
    appVersion: String,
    actions: SettingsActions,
) {
    Column(Modifier.fillMaxSize()) {
        CittiTopBar(
            title = stringResource(Res.string.settings_title),
            actionLabel = stringResource(Res.string.settings_back),
            onAction = actions.onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .testTag(TestTags.SETTINGS_HOME)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CittiTheme.shape.gutter),
        ) {
            for (section in SettingsSection.entries) {
                MenuRow(stringResource(section.title()), Modifier.testTag(TestTags.settingsSection(section))) {
                    actions.onOpenSection(section)
                }
            }
            BasicText(
                stringResource(Res.string.settings_version, appVersion),
                modifier = Modifier.padding(top = NotesSpacing.space6, bottom = NotesSpacing.space8).testTag(TestTags.SETTINGS_VERSION),
                style = CittiTheme.typography.meta.colored(muted = true),
            )
        }
    }
}

private fun SettingsSection.title(): StringResource =
    when (this) {
        SettingsSection.ACCOUNT -> Res.string.settings_account
        SettingsSection.SYNC -> Res.string.settings_sync
        SettingsSection.EXCLUSIONS -> Res.string.settings_exclusions
        SettingsSection.IMAGES -> Res.string.settings_images
        SettingsSection.APPEARANCE -> Res.string.settings_appearance
        SettingsSection.EDITOR -> Res.string.settings_editor
        SettingsSection.DAILY -> Res.string.settings_daily
    }

@Composable
private fun MenuRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, Modifier.weight(1f), style = CittiTheme.typography.rowTitle.colored())
        BasicText("›", style = CittiTheme.typography.rowTitle.colored(muted = true))
    }
}

/** A section page: the top bar titled [section], whose Back returns to the menu, over the scrolling [content]. */
@Composable
private fun SectionPage(
    section: SettingsSection,
    actions: SettingsActions,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        CittiTopBar(
            title = stringResource(section.title()),
            actionLabel = stringResource(Res.string.settings_back),
            onAction = actions.onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .testTag(TestTags.SETTINGS_SECTION)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CittiTheme.shape.gutter),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            content()
            Box(Modifier.padding(bottom = NotesSpacing.space8))
        }
    }
}

@Composable
private fun SettingsSectionPage(
    section: SettingsSection,
    state: SettingsUiState,
    nowSeconds: () -> Long,
    actions: SettingsActions,
) {
    when (section) {
        SettingsSection.ACCOUNT -> AccountPage(state, actions)
        SettingsSection.SYNC -> SyncPage(state, nowSeconds, actions)
        SettingsSection.EXCLUSIONS -> ExclusionsPage(state, actions)
        SettingsSection.IMAGES -> ImagesPage(state, actions)
        SettingsSection.APPEARANCE -> SectionPage(section, actions) { AppearanceSection(state.appearance, actions.appearance) }
        SettingsSection.EDITOR -> EditorPage(state, actions)
        SettingsSection.DAILY -> SectionPage(section, actions) { DailyNotesSection(state.daily, actions.daily) }
    }
}

@Composable
private fun AccountPage(
    state: SettingsUiState,
    actions: SettingsActions,
) = SectionPage(SettingsSection.ACCOUNT, actions) {
    LabelValue(stringResource(Res.string.settings_server), state.account.host)
    LabelValue(stringResource(Res.string.settings_user), state.account.user)
    CittiButton(stringResource(Res.string.settings_sign_in_again), actions.onSignInAgain, kind = ButtonKind.Quiet)
    Help(Res.string.settings_sign_in_again_help)
    CittiButton(stringResource(Res.string.settings_sign_out), actions.onAskSignOut, kind = ButtonKind.Danger)
}

@Composable
private fun SyncPage(
    state: SettingsUiState,
    nowSeconds: () -> Long,
    actions: SettingsActions,
) = SectionPage(SettingsSection.SYNC, actions) {
    val now by produceState(nowSeconds()) {
        while (true) {
            delay(30_000)
            value = nowSeconds()
        }
    }
    BasicText(stringResource(Res.string.settings_interval), style = CittiTheme.typography.meta.colored(muted = true))
    for (minutes in SYNC_INTERVAL_CHOICES) {
        OptionRow(intervalLabel(minutes), selected = state.intervalMinutes == minutes) { actions.onInterval(minutes) }
    }
    BasicText(stringResource(Res.string.settings_network), style = CittiTheme.typography.meta.colored(muted = true))
    OptionRow(stringResource(Res.string.settings_network_any), state.network == SyncNetwork.ANY) {
        actions.onNetwork(SyncNetwork.ANY)
    }
    OptionRow(stringResource(Res.string.settings_network_unmetered), state.network == SyncNetwork.UNMETERED) {
        actions.onNetwork(SyncNetwork.UNMETERED)
    }
    BasicText(
        statusText(syncLine(state.status, now), state.status),
        style = CittiTheme.typography.meta.colored(muted = true),
    )
    CittiButton(
        stringResource(Res.string.settings_sync_now),
        actions.onSyncNow,
        kind = ButtonKind.Quiet,
        enabled = !state.syncing,
    )
}

@Composable
private fun ExclusionsPage(
    state: SettingsUiState,
    actions: SettingsActions,
) = SectionPage(SettingsSection.EXCLUSIONS, actions) {
    state.daily.conflict?.let { DailyGuardBanner(it) }
    FolderList(ExclusionKind.UNSYNCED, Res.string.settings_unsynced_title, Res.string.settings_unsynced_help, state, actions)
    FolderList(ExclusionKind.HIDDEN, Res.string.settings_hidden_title, Res.string.settings_hidden_help, state, actions)
}

@Composable
private fun EditorPage(
    state: SettingsUiState,
    actions: SettingsActions,
) = SectionPage(SettingsSection.EDITOR, actions) {
    val layout = state.toolbar
    BasicText(stringResource(Res.string.settings_editor_toolbar), style = CittiTheme.typography.rowTitle.colored())
    Help(Res.string.settings_editor_toolbar_help)
    val up = stringResource(Res.string.settings_toolbar_move_up)
    val down = stringResource(Res.string.settings_toolbar_move_down)
    layout.order.forEachIndexed { index, action ->
        Row(
            Modifier.fillMaxWidth().testTag(TestTags.settingsToolbarRow(action)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
        ) {
            OptionRow(
                stringResource(action.label()),
                selected = action !in layout.hidden,
                modifier = Modifier.weight(1f),
                detail = action.mark(),
            ) { actions.onToolbarToggle(action) }
            StepButton("↑", up, enabled = index > 0) { actions.onToolbarMove(action, true) }
            StepButton("↓", down, enabled = index < layout.order.lastIndex) { actions.onToolbarMove(action, false) }
        }
    }
}

@Composable
private fun ImagesPage(
    state: SettingsUiState,
    actions: SettingsActions,
) = SectionPage(SettingsSection.IMAGES, actions) {
    BasicText(stringResource(Res.string.settings_image_cache_size), style = CittiTheme.typography.meta.colored(muted = true))
    for (mb in IMAGE_CACHE_CHOICES_MB) {
        OptionRow(cacheSizeLabel(mb), selected = state.imageCacheMb == mb) { actions.onImageCacheMb(mb) }
    }
    BasicText(
        stringResource(
            Res.string.settings_image_cache_used,
            cacheSizeLabel(((state.imageCacheBytes + MB / 2) / MB).toInt(), exact = true),
        ),
        style = CittiTheme.typography.meta.colored(muted = true),
    )
    BasicText(stringResource(Res.string.settings_image_remote), style = CittiTheme.typography.meta.colored(muted = true))
    OptionRow(
        stringResource(Res.string.settings_image_remote_ask),
        selected = state.remoteImages == RemoteImages.ASK,
    ) { actions.onRemoteImages(RemoteImages.ASK) }
    OptionRow(
        stringResource(Res.string.settings_image_remote_always),
        selected = state.remoteImages == RemoteImages.ALWAYS,
    ) { actions.onRemoteImages(RemoteImages.ALWAYS) }
    BasicText(stringResource(Res.string.settings_image_remote_help), style = CittiTheme.typography.meta.colored(muted = true))
    CittiButton(stringResource(Res.string.settings_image_cache_clear), actions.onAskClearImages, kind = ButtonKind.Quiet)
}

@Composable
private fun FolderList(
    kind: ExclusionKind,
    title: StringResource,
    help: StringResource,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val folders = state.listOf(kind)
    BasicText(stringResource(title), style = CittiTheme.typography.rowTitle.colored())
    Help(help)
    if (folders.isEmpty()) {
        BasicText(stringResource(Res.string.settings_none), style = CittiTheme.typography.body.colored(muted = true))
    }
    for (path in folders) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(path, Modifier.weight(1f), style = CittiTheme.typography.body.colored())
            CittiTextButton(
                stringResource(Res.string.settings_remove),
                { actions.onRemoveFolder(kind, path) },
                Modifier.testTag(TestTags.settingsRemove(kind, path)),
            )
        }
    }
    CittiButton(
        stringResource(Res.string.settings_add_folder),
        { actions.onOpenPicker(kind) },
        Modifier.testTag(TestTags.settingsAdd(kind)),
        kind = ButtonKind.Quiet,
    )
}

/** Folders known locally (from the Notes' categories) plus manual entry. */
@Composable
private fun FolderPicker(
    kind: ExclusionKind,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var typed by remember { mutableStateOf("") }
    val title = if (kind == ExclusionKind.UNSYNCED) Res.string.settings_pick_unsynced_title else Res.string.settings_pick_hidden_title
    Column(Modifier.fillMaxSize()) {
        CittiTopBar(stringResource(title), actionLabel = stringResource(Res.string.settings_back), onAction = actions.onBack)
        state.folderError?.let {
            Banner(
                when (it) {
                    FolderError.Blank -> stringResource(Res.string.settings_folder_blank)
                    FolderError.AlreadyListed -> stringResource(Res.string.settings_folder_listed)
                    is FolderError.JournalFolder -> stringResource(Res.string.exclusion_journal_refused, it.journal)
                },
                BannerKind.Error,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .testTag(TestTags.SETTINGS_PICKER)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CittiTheme.shape.gutter),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            CittiTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    actions.onClearFolderError()
                },
                label = stringResource(Res.string.settings_folder_path_label),
                modifier = Modifier.testTag(TestTags.SETTINGS_FOLDER_PATH),
                placeholder = stringResource(Res.string.settings_folder_path_hint),
                keyboardType = KeyboardType.Uri,
            )
            CittiButton(stringResource(Res.string.settings_folder_add), { actions.onAddFolder(kind, typed) })
            SectionHeader(Res.string.settings_known_folders)
            if (state.knownFolders.isEmpty()) Help(Res.string.settings_no_known_folders)
            val listed = state.listOf(kind)
            for (folder in state.knownFolders) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = NotesSize.touchMin)
                        .clickable(enabled = folder !in listed) { actions.onAddFolder(kind, folder) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(folder, style = CittiTheme.typography.body.colored(muted = folder in listed))
                }
            }
            Box(Modifier.padding(bottom = NotesSpacing.space8))
        }
    }
}

@Composable
private fun ConfirmDialog(
    confirm: SettingsConfirm,
    actions: SettingsActions,
) {
    val colors = CittiTheme.colors
    val (title, body, action) =
        when (confirm) {
            SettingsConfirm.SignOut -> {
                Triple(
                    stringResource(Res.string.settings_sign_out_title),
                    stringResource(Res.string.settings_sign_out_body),
                    stringResource(Res.string.settings_sign_out),
                )
            }

            SettingsConfirm.ClearImages -> {
                Triple(
                    stringResource(Res.string.settings_image_cache_clear_title),
                    stringResource(Res.string.settings_image_cache_clear_confirm),
                    stringResource(Res.string.settings_image_cache_clear),
                )
            }

            is SettingsConfirm.AddUnsynced -> {
                val base = stringResource(Res.string.settings_confirm_unsynced_body, confirm.notes, confirm.path)
                val text =
                    if (confirm.pending > 0) {
                        base + " " + stringResource(Res.string.settings_confirm_unsynced_pending, confirm.pending)
                    } else {
                        base
                    }
                Triple(
                    stringResource(Res.string.settings_confirm_unsynced_title),
                    text,
                    stringResource(Res.string.settings_confirm_delete),
                )
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = actions.onDismissConfirm)
            .padding(CittiTheme.shape.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, RoundedCornerShape(CittiTheme.shape.control))
                .clickable(enabled = false) {}
                .padding(NotesSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            BasicText(title, style = CittiTheme.typography.title.colored())
            BasicText(body, style = CittiTheme.typography.body.colored())
            CittiButton(action, actions.onConfirm, Modifier.testTag(TestTags.SETTINGS_CONFIRM), kind = ButtonKind.Danger)
            CittiButton(stringResource(Res.string.settings_cancel), actions.onDismissConfirm, kind = ButtonKind.Quiet)
        }
    }
}

@Composable
internal fun SectionHeader(title: StringResource) {
    BasicText(
        stringResource(title),
        modifier = Modifier.padding(top = NotesSpacing.space4),
        style = CittiTheme.typography.title.colored(),
    )
}

@Composable
internal fun Help(text: StringResource) {
    BasicText(stringResource(text), style = CittiTheme.typography.meta.colored(muted = true))
}

@Composable
private fun LabelValue(
    label: String,
    value: String,
) {
    Column {
        BasicText(label, style = CittiTheme.typography.meta.colored(muted = true))
        BasicText(value, style = CittiTheme.typography.body.colored())
    }
}

/** A single-choice row; the chosen one is marked by a check and the accent color, not by color alone. */
@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** A second line in `meta` (a live example). */
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        val style = CittiTheme.typography.body.copy(color = if (selected) CittiTheme.colors.accentInk else CittiTheme.colors.ink)
        BasicText(if (selected) "✓" else " ", style = style)
        Column {
            BasicText(label, style = style)
            if (detail != null) BasicText(detail, style = CittiTheme.typography.meta.colored(muted = true))
        }
    }
}

@Composable
private fun cacheSizeLabel(
    mb: Int,
    exact: Boolean = false,
): String =
    if (!exact && mb % GB_IN_MB == 0) {
        stringResource(Res.string.size_gb, mb / GB_IN_MB)
    } else {
        stringResource(Res.string.size_mb, mb)
    }

private const val MB = 1024L * 1024L
private const val GB_IN_MB = 1000

@Composable
private fun intervalLabel(minutes: Int): String =
    when {
        minutes == 0 -> stringResource(Res.string.settings_interval_manual)
        minutes % 60 == 0 -> stringResource(Res.string.settings_interval_hours, minutes / 60)
        else -> stringResource(Res.string.settings_interval_minutes, minutes)
    }
