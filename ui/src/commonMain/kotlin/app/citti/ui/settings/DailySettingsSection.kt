package app.citti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import app.citti.core.daily.IMPORT_FOLDER
import app.citti.core.daily.IMPORT_FORMAT
import app.citti.core.daily.IMPORT_TEMPLATE
import app.citti.core.daily.ImportReport
import app.citti.core.daily.Refusal
import app.citti.core.note.NameError
import app.citti.core.settings.FormatResult
import app.citti.core.settings.WEEK_START_CHOICES
import app.citti.ui.TestTags
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiDialog
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.FolderPicker
import app.citti.ui.components.FolderPickerActions
import app.citti.ui.components.PickerFolders
import app.citti.ui.components.colored
import app.citti.ui.components.nameErrorText
import app.citti.ui.resources.Res
import app.citti.ui.resources.choose
import app.citti.ui.resources.format_error_roundtrip
import app.citti.ui.resources.format_error_slash
import app.citti.ui.resources.guard_banner
import app.citti.ui.resources.import_failed
import app.citti.ui.resources.import_none
import app.citti.ui.resources.import_ok
import app.citti.ui.resources.import_report_folder
import app.citti.ui.resources.import_report_format
import app.citti.ui.resources.import_report_format_default
import app.citti.ui.resources.import_report_refused
import app.citti.ui.resources.import_report_template
import app.citti.ui.resources.journal_unsynced_refused
import app.citti.ui.resources.locale_auto_detected
import app.citti.ui.resources.locale_auto_phone
import app.citti.ui.resources.locale_de
import app.citti.ui.resources.locale_en
import app.citti.ui.resources.locale_es
import app.citti.ui.resources.locale_fr
import app.citti.ui.resources.locale_it
import app.citti.ui.resources.locale_nl
import app.citti.ui.resources.locale_pt
import app.citti.ui.resources.monday
import app.citti.ui.resources.saturday
import app.citti.ui.resources.settings_daily
import app.citti.ui.resources.settings_date_format
import app.citti.ui.resources.settings_date_format_example
import app.citti.ui.resources.settings_date_locale
import app.citti.ui.resources.settings_import_obsidian
import app.citti.ui.resources.settings_journal_folder
import app.citti.ui.resources.settings_template
import app.citti.ui.resources.settings_template_none
import app.citti.ui.resources.settings_template_search
import app.citti.ui.resources.settings_template_unsynced
import app.citti.ui.resources.settings_week_start
import app.citti.ui.resources.sunday
import app.citti.ui.resources.tree_root
import app.citti.ui.resources.week_auto
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DayOfWeek
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The actions of Settings › Daily notes and its pickers; the app binds them to a [SettingsController]. */
class DailySettingsActions(
    val onOpenJournalPicker: () -> Unit,
    val journalPicker: FolderPickerActions,
    val onOpenTemplatePicker: () -> Unit,
    val onTemplateQuery: (String) -> Unit,
    /** A Note's Vault path with `.md`, or `""` for None. */
    val onChooseTemplate: (String) -> Unit,
    val onFormat: (String) -> Unit,
    val onDateLocale: (String?) -> Unit,
    val onWeekStart: (DayOfWeek?) -> Unit,
    val onImport: () -> Unit,
    val onDismissImportReport: () -> Unit,
)

fun SettingsController.dailyActions(): DailySettingsActions =
    DailySettingsActions(
        onOpenJournalPicker = ::openJournalPicker,
        journalPicker =
            FolderPickerActions(
                onOpen = ::journalOpen,
                onBack = ::journalBack,
                onNewFolder = ::journalNewFolder,
                onNewFolderText = ::journalNewFolderText,
                onNewFolderDone = ::journalNewFolderDone,
                onConfirm = ::journalConfirm,
            ),
        onOpenTemplatePicker = ::openTemplatePicker,
        onTemplateQuery = ::onTemplateQuery,
        onChooseTemplate = ::chooseTemplate,
        onFormat = ::onFormat,
        onDateLocale = ::setDateLocale,
        onWeekStart = ::setWeekStart,
        onImport = ::importFromObsidian,
        onDismissImportReport = ::dismissImportReport,
    )

/** The Date locales offered, each named in its own language. */
private val LOCALE_NAMES: List<Pair<String, StringResource>> =
    listOf(
        "en" to Res.string.locale_en,
        "fr" to Res.string.locale_fr,
        "de" to Res.string.locale_de,
        "es" to Res.string.locale_es,
        "it" to Res.string.locale_it,
        "pt" to Res.string.locale_pt,
        "nl" to Res.string.locale_nl,
    )

private fun localeName(code: String): StringResource = LOCALE_NAMES.firstOrNull { it.first == code }?.second ?: Res.string.locale_en

/**
 * Settings › Daily notes (M6 spec §5.4): the guard banner, Journal folder, Template, Date format, Date locale, Week
 * starts on and Import from Obsidian.
 */
@Composable
internal fun DailyNotesSection(
    state: DailyUiState,
    actions: DailySettingsActions,
) {
    val config = state.config
    SectionHeader(Res.string.settings_daily)
    state.conflict?.let { DailyGuardBanner(it) }

    ValueRow(
        stringResource(Res.string.settings_journal_folder),
        config.folder.ifEmpty { stringResource(Res.string.tree_root) },
        Modifier.testTag(TestTags.SETTINGS_JOURNAL_FOLDER),
        onClick = actions.onOpenJournalPicker,
    )
    ValueRow(
        stringResource(Res.string.settings_template),
        config.template.ifEmpty { stringResource(Res.string.settings_template_none) },
        Modifier.testTag(TestTags.SETTINGS_TEMPLATE),
        detail = if (state.templateLocal) null else stringResource(Res.string.settings_template_unsynced),
        onClick = actions.onOpenTemplatePicker,
    )

    CittiTextField(
        value = state.format,
        onValueChange = actions.onFormat,
        label = stringResource(Res.string.settings_date_format),
        modifier = Modifier.testTag(TestTags.SETTINGS_DATE_FORMAT),
        keyboardType = KeyboardType.Ascii,
        error =
            when (state.formatError) {
                FormatResult.Slash -> stringResource(Res.string.format_error_slash)
                FormatResult.RoundTrip -> stringResource(Res.string.format_error_roundtrip)
                is FormatResult.Saved, null -> null
            },
    )
    BasicText(
        stringResource(Res.string.settings_date_format_example, state.formatExample),
        style = CittiTheme.typography.meta.colored(muted = true),
    )

    BasicText(stringResource(Res.string.settings_date_locale), style = CittiTheme.typography.meta.colored(muted = true))
    val detected = config.localeDetected?.takeIf { config.locale == null }
    OptionRow(
        if (detected != null) {
            stringResource(Res.string.locale_auto_detected, stringResource(localeName(detected)))
        } else {
            stringResource(Res.string.locale_auto_phone)
        },
        selected = config.locale == null,
        modifier = Modifier.testTag(TestTags.settingsDateLocale(null)),
        detail = state.monthExamples[null],
    ) { actions.onDateLocale(null) }
    for ((code, name) in LOCALE_NAMES) {
        OptionRow(
            stringResource(name),
            selected = config.locale == code,
            modifier = Modifier.testTag(TestTags.settingsDateLocale(code)),
            detail = state.monthExamples[code],
        ) { actions.onDateLocale(code) }
    }

    BasicText(stringResource(Res.string.settings_week_start), style = CittiTheme.typography.meta.colored(muted = true))
    OptionRow(
        stringResource(Res.string.week_auto),
        selected = config.weekStart == null,
        modifier = Modifier.testTag(TestTags.settingsWeekStart(null)),
    ) { actions.onWeekStart(null) }
    for (day in WEEK_START_CHOICES) {
        OptionRow(
            stringResource(weekdayName(day)),
            selected = config.weekStart == day,
            modifier = Modifier.testTag(TestTags.settingsWeekStart(day.name)),
        ) { actions.onWeekStart(day) }
    }

    CittiButton(
        stringResource(Res.string.settings_import_obsidian),
        actions.onImport,
        Modifier.testTag(TestTags.SETTINGS_IMPORT),
        kind = ButtonKind.Quiet,
        busy = state.importing,
    )
}

/** The Journal folder lies under an Unsynced folder: shown in Settings › Daily notes and in Settings › Exclusions. */
@Composable
internal fun DailyGuardBanner(journal: String) {
    Banner(stringResource(Res.string.guard_banner, journal), BannerKind.Error, Modifier.testTag(TestTags.SETTINGS_DAILY_GUARD))
}

private fun weekdayName(day: DayOfWeek): StringResource =
    when (day) {
        DayOfWeek.SUNDAY -> Res.string.sunday
        DayOfWeek.SATURDAY -> Res.string.saturday
        else -> Res.string.monday
    }

/** A setting shown as its value, opening a picker on tap. */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        BasicText(label, style = CittiTheme.typography.meta.colored(muted = true))
        BasicText(value, style = CittiTheme.typography.body.colored())
        if (detail != null) BasicText(detail, style = CittiTheme.typography.meta.colored(muted = true))
    }
}

/** The shared Folder picker on the Journal folder; Choose shows the refusal under the list. */
@Composable
internal fun JournalFolderPage(
    page: SettingsPage.PickJournal,
    folders: Flow<PickerFolders>,
    actions: DailySettingsActions,
) {
    val shown by remember(folders) { folders }.collectAsState(null)
    FolderPicker(
        title = stringResource(Res.string.settings_journal_folder),
        confirmLabel = stringResource(Res.string.choose),
        state = page.picker,
        folders = shown,
        actions = actions.journalPicker,
        error =
            page.error?.let {
                if (it == NameError.Unsynced) {
                    stringResource(Res.string.journal_unsynced_refused, page.picker.path)
                } else {
                    nameErrorText(it, page.picker.path)
                }
            },
    )
}

/** The Template picker: a search over the Notes on this phone, "None" first. */
@Composable
internal fun TemplatePickerPage(
    page: SettingsPage.PickTemplate,
    current: String,
    onBack: () -> Unit,
    actions: DailySettingsActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        CittiTopBar(stringResource(Res.string.settings_template), onBack = onBack)
        Column(Modifier.padding(horizontal = CittiTheme.shape.gutter)) {
            CittiTextField(
                value = page.query,
                onValueChange = actions.onTemplateQuery,
                label = stringResource(Res.string.settings_template_search),
                modifier = Modifier.testTag(TestTags.SETTINGS_TEMPLATE_SEARCH),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter)) {
            item(key = "none") {
                OptionRow(
                    stringResource(Res.string.settings_template_none),
                    selected = current.isEmpty(),
                    modifier = Modifier.testTag(TestTags.SETTINGS_TEMPLATE_NONE),
                ) { actions.onChooseTemplate("") }
            }
            items(page.suggestions, key = { it.localId }) { note ->
                val path = note.templatePath()
                OptionRow(
                    note.title,
                    selected = current == path,
                    detail = note.folder.ifEmpty { null },
                ) { actions.onChooseTemplate(path) }
            }
        }
    }
}

/** What Import from Obsidian read ("Folder journal, Template templates/daily.md, Format: default") and refused. */
@Composable
internal fun ImportReportDialog(
    report: ImportReport,
    onDismiss: () -> Unit,
) {
    CittiDialog(stringResource(Res.string.settings_import_obsidian), onDismiss = onDismiss) {
        BasicText(
            importReportText(report),
            Modifier.testTag(TestTags.SETTINGS_IMPORT_REPORT),
            style = CittiTheme.typography.body.colored(),
            overflow = TextOverflow.Clip,
        )
        CittiButton(stringResource(Res.string.import_ok), onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
}

@Composable
private fun importReportText(report: ImportReport): String {
    if (report.error != null) return stringResource(Res.string.import_failed)
    if (!report.readSomething) return stringResource(Res.string.import_none)
    val read =
        buildList {
            report.read[IMPORT_FOLDER]?.let { add(stringResource(Res.string.import_report_folder, it)) }
            report.read[IMPORT_TEMPLATE]?.let {
                add(stringResource(Res.string.import_report_template, it.ifEmpty { stringResource(Res.string.settings_template_none) }))
            }
            when {
                IMPORT_FORMAT in report.read -> add(stringResource(Res.string.import_report_format, report.read.getValue(IMPORT_FORMAT)))
                report.refused.none { it.key == IMPORT_FORMAT } -> add(stringResource(Res.string.import_report_format_default))
            }
        }
    val refused = report.refused.map { stringResource(Res.string.import_report_refused, it.value, refusalReason(it)) }
    return (listOf(read.joinToString(", ")).filter { it.isNotEmpty() } + refused).joinToString("\n")
}

@Composable
private fun refusalReason(refusal: Refusal): String =
    when (refusal) {
        is Refusal.Folder -> {
            if (refusal.error == NameError.Unsynced) {
                stringResource(Res.string.journal_unsynced_refused, refusal.value)
            } else {
                nameErrorText(refusal.error, refusal.value)
            }
        }

        is Refusal.Format -> {
            stringResource(if (refusal.result == FormatResult.Slash) Res.string.format_error_slash else Res.string.format_error_roundtrip)
        }
    }
