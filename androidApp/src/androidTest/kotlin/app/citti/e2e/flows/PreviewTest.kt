package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PreviewTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun wikilinksOpenTheRightNoteAndABrokenOneLeadsNowhere() {
        list.openNote("links")
        note.showPreview()
        note.tapLinkIn("Full path with an alias")
        note.assertTitle("alpha")
        note.pressBack()

        note.assertTitle("links")
        // A broken link leads nowhere: the Note stays open.
        note.tapLinkIn("Broken:")
        note.assertTitle("links")
    }

    @Test
    fun imagesLoadFromEachCandidatePathAndAMissingOneShowsAPlaceholder() {
        list.openNote("images")
        note.showPreview()
        note.assertImageLoaded("by note")
        note.assertImageLoaded("by root")
        note.assertImageLoaded("by attachment")
        note.assertImageMissing("missing")
        for (file in listOf("by-note.png", "by-root.png", "by-attachment.png")) {
            server.awaitRequest(Endpoint.DAV_GET) { it.path.endsWith("/attachments/$file") && it.status == 200 }
        }
    }

    @Test
    fun checkboxTapChangesOnlyItsLine() {
        val before = E2e.server.note(e2e.setup.id("todo"))!!.content
        list.openNote("todo")
        note.showPreview()
        note.tapTask("Buy milk")
        server.awaitRequest(Endpoint.UPDATE)
        assertEquals(before.replace("- [ ] Buy milk", "- [x] Buy milk"), E2e.server.note(e2e.setup.id("todo"))!!.content)
    }

    @Test
    fun aDoubleTapSwitchesTheModeBothWays() {
        list.openNote("todo")
        note.assertPreviewMode()
        note.doubleTapBlock("Buy milk")
        note.assertEditorFocused()
        note.doubleTapEditor()
        note.assertPreviewMode()
    }
}
