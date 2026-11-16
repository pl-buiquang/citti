package app.citti.e2e.robots

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import app.citti.ui.settings.SettingsSection
import kotlin.test.assertEquals

/** Settings › Agenda and the Agenda blocks of the Preview. */
class AgendaRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    fun openSettings() = openSettingsSection(SettingsSection.AGENDA)

    fun discover() {
        waitFor(hasTestTag(TestTags.SETTINGS_AGENDA_DISCOVER)).performClick()
    }

    fun assertCalendarListed(
        href: String,
        name: String,
    ) {
        waitFor(hasText(name) and hasAnyAncestor(hasTestTag(TestTags.settingsAgendaCalendar(href))))
    }

    /** Flips the enabled switch of the calendar at [href]. */
    fun toggleCalendar(href: String) {
        val switch =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
                hasAnyAncestor(hasTestTag(TestTags.settingsAgendaCalendar(href)))
        waitFor(switch).performScrollTo().performClick()
    }

    private fun itemText(summary: String) = hasText(summary, substring = true) and hasAnyAncestor(hasTestTag(TestTags.AGENDA_ITEM))

    /** The event [summary] is listed in an Agenda block, dimmed or not. */
    fun assertEvent(
        summary: String,
        dimmed: Boolean,
    ) {
        waitFor(itemText(summary), unmerged = true)
        val row =
            compose
                .onAllNodes(hasTestTag(TestTags.AGENDA_ITEM), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single { node -> descendantTexts(node).any { summary in it } }
        assertEquals(if (dimmed) Strings.AGENDA_PAST else null, row.config.getOrNull(SemanticsProperties.StateDescription), summary)
    }

    fun assertNoEvent(summary: String) {
        assertEquals(false, exists(itemText(summary), unmerged = true), "$summary is listed")
    }

    /** The status line of the Agenda block contains [text]. */
    fun assertStatus(text: String) {
        waitFor(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(TestTags.AGENDA_STATUS)), unmerged = true)
    }

    private fun descendantTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::descendantTexts)
}
