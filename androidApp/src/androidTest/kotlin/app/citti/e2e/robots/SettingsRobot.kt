package app.citti.e2e.robots

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.citti.core.settings.ExclusionKind
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import app.citti.ui.settings.SettingsSection
import kotlin.test.assertFalse

class SettingsRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    fun assertShown() {
        waitFor(hasText(Strings.SETTINGS) and isHeading())
    }

    /** Opens the menu entry of [section]. */
    fun openSection(section: SettingsSection) = openSettingsSection(section)

    /** Settings is on the page of [section] (its Back button leads to the menu). */
    fun assertSectionShown(section: SettingsSection) {
        waitFor(hasText(sectionTitle(section)) and isHeading())
    }

    /** The menu shows the release and the commit it was built from ("citti 1.0.1 · 9ae40fe"). */
    fun assertVersionShown() {
        waitFor(hasTestTag(TestTags.SETTINGS_VERSION) and hasText(Strings.SETTINGS_VERSION, substring = true))
        waitFor(hasTestTag(TestTags.SETTINGS_VERSION) and hasText(" · ", substring = true))
    }

    /** Back to the menu from a section page. */
    fun backToMenu() {
        click(Strings.BACK)
        assertShown()
    }

    /** Leaves Settings for the list, from the menu or from a section page. */
    fun leave() {
        if (!exists(hasTestTag(TestTags.SETTINGS_HOME))) pressBack()
        pressBack()
    }

    fun addHiddenFolder(path: String) = addFolder(ExclusionKind.HIDDEN, path)

    /** Adds an Unsynced folder and confirms that its local Notes go. */
    fun addUnsyncedFolder(path: String) {
        addFolder(ExclusionKind.UNSYNCED, path)
        waitFor(hasTestTag(TestTags.SETTINGS_CONFIRM)).performClick()
        assertSectionShown(SettingsSection.EXCLUSIONS)
    }

    /**
     * Adding [path] as an Unsynced folder, confirmed, is refused with [error] (the refusal comes after the "Delete
     * local Notes?" confirmation, and the picker stays open); Back leaves it.
     */
    fun addUnsyncedFolderRefused(
        path: String,
        error: String,
    ) {
        addFolder(ExclusionKind.UNSYNCED, path)
        waitFor(hasTestTag(TestTags.SETTINGS_CONFIRM)).performClick()
        waitFor(hasText(error))
        click(Strings.BACK)
        assertSectionShown(SettingsSection.EXCLUSIONS)
    }

    fun assertFolderNotListed(
        kind: ExclusionKind,
        path: String,
    ) {
        openSettingsSection(SettingsSection.EXCLUSIONS)
        assertFalse(exists(hasTestTag(TestTags.settingsRemove(kind, path))), "$path is listed as $kind")
    }

    // ---- Daily notes ----

    // The report dialog merges its content: its tag is in the unmerged tree only.

    /** Import from Obsidian: the report dialog shows. */
    fun importFromObsidian() {
        openSettingsSection(SettingsSection.DAILY)
        waitFor(hasTestTag(TestTags.SETTINGS_IMPORT)).performScrollTo().performClick()
        waitFor(hasTestTag(TestTags.SETTINGS_IMPORT_REPORT), unmerged = true)
    }

    /** The Import report contains [line]. */
    fun assertImportReport(line: String) {
        waitFor(hasTestTag(TestTags.SETTINGS_IMPORT_REPORT) and hasText(line, substring = true), unmerged = true)
    }

    fun dismissImportReport() {
        click(Strings.IMPORT_OK)
        waitGone(hasTestTag(TestTags.SETTINGS_IMPORT_REPORT), unmerged = true)
    }

    fun removeHiddenFolder(path: String) = remove(ExclusionKind.HIDDEN, path)

    fun removeUnsyncedFolder(path: String) = remove(ExclusionKind.UNSYNCED, path)

    fun assertFolderListed(
        kind: ExclusionKind,
        path: String,
    ) {
        openSettingsSection(SettingsSection.EXCLUSIONS)
        waitFor(hasTestTag(TestTags.settingsRemove(kind, path))).performScrollTo()
    }

    /** Sign out, confirmed. */
    fun signOut() {
        openSettingsSection(SettingsSection.ACCOUNT)
        waitFor(button(Strings.SETTINGS_SIGN_OUT)).performScrollTo().performClick()
        waitFor(hasTestTag(TestTags.SETTINGS_CONFIRM)).performClick()
    }

    private fun addFolder(
        kind: ExclusionKind,
        path: String,
    ) {
        openSettingsSection(SettingsSection.EXCLUSIONS)
        waitFor(hasTestTag(TestTags.settingsAdd(kind))).performScrollTo().performClick()
        waitFor(hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.SETTINGS_FOLDER_PATH)), unmerged = true)
            .performTextReplacement(path)
        click(Strings.SETTINGS_ADD)
    }

    private fun remove(
        kind: ExclusionKind,
        path: String,
    ) {
        openSettingsSection(SettingsSection.EXCLUSIONS)
        waitFor(hasTestTag(TestTags.settingsRemove(kind, path))).performScrollTo().performClick()
        waitGone(hasTestTag(TestTags.settingsRemove(kind, path)))
    }
}
