package app.citti.e2e.robots

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import app.citti.ui.settings.SettingsSection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base of the screen Robots: waits and finders over the Compose tree. Tests talk to Robots, never to finders.
 * Every finder also waits for the sync to settle (the sync idling resource).
 */
abstract class Robot(
    protected val e2e: E2eRules,
) {
    protected val compose get() = e2e.compose

    protected fun exists(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ): Boolean = compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    protected fun waitFor(
        matcher: SemanticsMatcher,
        timeout: Duration = TIMEOUT,
        unmerged: Boolean = false,
    ): SemanticsNodeInteraction {
        waitUntil("$matcher", timeout) { exists(matcher, unmerged) }
        return compose.onNode(matcher, useUnmergedTree = unmerged)
    }

    /**
     * [compose]'s waitUntil, which on timeout also logs the texts on screen: ATD screenshots come out black, so this
     * is what shows the screen of a failure (logcat tag `E2eScreen`).
     */
    protected fun waitUntil(
        what: String,
        timeout: Duration = TIMEOUT,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeout.inWholeMilliseconds, condition)
        } catch (e: ComposeTimeoutException) {
            Log.e("E2eScreen", "timed out waiting for $what; on screen:\n${screenTexts()}")
            throw e
        }
    }

    private fun screenTexts(): String =
        runCatching {
            compose
                .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .mapNotNull { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
                    val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
                    listOfNotNull(text, description?.let { "[$it]" }).takeIf { it.isNotEmpty() }?.joinToString(" ")
                }.joinToString("\n")
        }.getOrElse { "(no tree: $it)" }

    protected fun waitGone(
        matcher: SemanticsMatcher,
        timeout: Duration = TIMEOUT,
        unmerged: Boolean = false,
    ) = waitUntil("$matcher to go", timeout) { !exists(matcher, unmerged) }

    /** Taps the button or tab labelled [label]; buttons show their label in capitals, so case is ignored. */
    protected fun click(label: String) {
        waitFor(button(label)).performClick()
    }

    /**
     * Settings is on the page of [section]: from the menu it opens the entry, from another section page it goes
     * Back to the menu first, and on the page itself it does nothing.
     */
    protected fun openSettingsSection(section: SettingsSection) {
        val heading = hasText(sectionTitle(section)) and isHeading()
        if (!exists(heading)) {
            val row = hasTestTag(TestTags.settingsSection(section))
            if (!exists(row)) click(Strings.BACK)
            waitFor(row).performClick()
        }
        waitFor(heading)
    }

    protected fun sectionTitle(section: SettingsSection) =
        when (section) {
            SettingsSection.ACCOUNT -> "Account"
            SettingsSection.SYNC -> "Sync"
            SettingsSection.EXCLUSIONS -> "Exclusions"
            SettingsSection.IMAGES -> "Images"
            SettingsSection.APPEARANCE -> "Appearance"
            SettingsSection.EDITOR -> "Editor"
            SettingsSection.DAILY -> "Journal"
        }

    protected fun button(label: String) = hasText(label, ignoreCase = true) and hasClickAction()

    /** Scrolls the lazy list tagged [listTag] until a node matching [item] shows (waiting for it to load). */
    protected fun scrollTo(
        listTag: String,
        item: SemanticsMatcher,
        timeout: Duration = TIMEOUT,
    ): SemanticsNodeInteraction {
        val list = waitFor(hasTestTag(listTag))
        val inList = item and hasAnyAncestor(hasTestTag(listTag))
        // A list too short to scroll refuses performScrollToNode, so look first.
        waitUntil("$item in $listTag", timeout) {
            exists(inList) || runCatching { list.performScrollToNode(item) }.isSuccess
        }
        return compose.onNode(inList)
    }

    /** True when the whole list tagged [listTag] has no node matching [item]. */
    protected fun listHas(
        listTag: String,
        item: SemanticsMatcher,
    ): Boolean {
        val list = waitFor(hasTestTag(listTag))
        if (exists(item and hasAnyAncestor(hasTestTag(listTag)))) return true
        val found = runCatching { list.performScrollToNode(item) }.isSuccess
        runCatching { list.performScrollToIndex(0) }
        return found
    }

    /** A snackbar or toast reading [text]. */
    fun assertSnackbar(text: String) {
        waitFor(hasText(text))
    }

    fun pressBack() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    companion object {
        val TIMEOUT = 10.seconds
    }
}
