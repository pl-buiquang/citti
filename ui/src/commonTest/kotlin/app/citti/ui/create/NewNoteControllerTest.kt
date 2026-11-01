package app.citti.ui.create

import app.citti.core.note.NameError
import app.citti.core.note.NameRules
import app.citti.core.note.NoteCreator.Check
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import app.citti.ui.note.LinkRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeCreation : NoteCreation {
    var unsynced = listOf("private")
    var answer: Check = Check.Free

    /** When set, [check] waits for it (the server is slow). */
    var gate: CompletableDeferred<Unit>? = null
    val checks = mutableListOf<Pair<String, String>>()
    val created = mutableListOf<Pair<String, String>>()
    val taken = mutableSetOf<Pair<String, String>>()
    private var nextId = 100L

    override suspend fun normaliseFolder(path: String) = NameRules.normaliseFolder(path, unsynced)

    override suspend fun check(
        folder: String,
        title: String,
    ): Check {
        checks += folder to title
        gate?.await()
        return answer
    }

    override suspend fun freeTitle(
        folder: String,
        title: String,
    ): String {
        if ((folder to title) !in taken) return title
        var n = 2
        while ((folder to "$title ($n)") in taken) n++
        return "$title ($n)"
    }

    override suspend fun create(
        folder: String,
        title: String,
    ): Long {
        created += folder to title
        return nextId++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewNoteControllerTest {
    private class Fixture(
        val creation: FakeCreation,
        val navigator: Navigator,
        val controller: NewNoteController,
    )

    private fun TestScope.fixture(root: Route = Route.List()): Fixture {
        val creation = FakeCreation()
        val navigator = Navigator(listOf(root))
        return Fixture(creation, navigator, NewNoteController(backgroundScope, creation, navigator))
    }

    @Test
    fun listModeCreatesAtTheVaultRootAndTreeModeInTheCurrentFolder() {
        assertEquals("", newNoteFolder(Route.List()))
        assertEquals("", newNoteFolder(Route.List(favoritesOnly = true)))
        assertEquals("", newNoteFolder(Route.Tree()))
        assertEquals("projects/citti", newNoteFolder(Route.Tree("projects/citti")))
    }

    @Test
    fun aFreeTitleIsCreatedAndOpensFocused() =
        runTest {
            val f = fixture(Route.Tree("work"))
            f.controller.open("work")
            f.controller.onTitle("  Plan  ")
            f.controller.create()
            assertTrue(
                f.controller.state.value!!
                    .checking,
            )
            runCurrent()
            assertEquals(listOf("work" to "Plan"), f.creation.checks)
            assertEquals(listOf("work" to "Plan"), f.creation.created)
            assertNull(f.controller.state.value)
            assertEquals(Route.Note(100, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun anUncheckedTitleIsCreatedAtOnce() =
        runTest {
            val f = fixture()
            f.creation.answer = Check.Unchecked
            f.controller.open("")
            f.controller.onTitle("Offline")
            f.controller.create()
            runCurrent()
            assertEquals(listOf("" to "Offline"), f.creation.created)
            assertEquals(Route.Note(100, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun invalidTitlesAreRefusedWithoutACheck() =
        runTest {
            val f = fixture()
            f.controller.open("")
            for ((title, error) in listOf(
                " " to NameError.Blank,
                "a/b" to NameError.Slash,
                "a:b" to NameError.UnsafeCharacter,
                ".x" to NameError.LeadingDot,
            )) {
                f.controller.onTitle(title)
                f.controller.create()
                runCurrent()
                assertEquals(
                    error,
                    f.controller.state.value!!
                        .error,
                    title,
                )
            }
            assertTrue(f.creation.checks.isEmpty())
            f.controller.onTitle("ok")
            assertNull(
                f.controller.state.value!!
                    .error,
            )
        }

    @Test
    fun anUnsyncedFolderIsRefused() =
        runTest {
            val f = fixture()
            f.controller.open("private/x", "T")
            f.controller.create()
            runCurrent()
            val state = assertNotNull(f.controller.state.value)
            assertEquals(NameError.Unsynced, state.error)
            assertEquals("private/x", state.errorPath)
            assertTrue(f.creation.checks.isEmpty())
        }

    @Test
    fun anExistingTitleShowsTheClashDialog() =
        runTest {
            val f = fixture()
            f.creation.answer = Check.Exists(7)
            f.creation.taken += setOf("a" to "T", "a" to "T (2)")
            f.controller.open("a", "T")
            f.controller.create()
            runCurrent()
            assertEquals(
                Clash(7, "a", "T (3)"),
                f.controller.state.value!!
                    .clash,
            )
            assertTrue(f.creation.created.isEmpty())

            f.controller.openExisting()
            assertNull(f.controller.state.value)
            assertEquals(Route.Note(7), f.navigator.top)
        }

    @Test
    fun theClashDialogCreatesTheFreeTitleWithoutASecondCheck() =
        runTest {
            val f = fixture()
            f.creation.answer = Check.Exists(7)
            f.creation.taken += "a" to "T"
            f.controller.open("a", "T")
            f.controller.create()
            runCurrent()
            f.controller.createFreeTitle()
            f.controller.createFreeTitle()
            runCurrent()
            assertEquals(1, f.creation.checks.size)
            assertEquals(listOf("a" to "T (2)"), f.creation.created)
            assertEquals(Route.Note(100, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun aDoubleTapCreatesOneNote() =
        runTest {
            val f = fixture()
            val gate = CompletableDeferred<Unit>()
            f.creation.gate = gate
            f.controller.open("", "T")
            f.controller.create()
            runCurrent()
            f.controller.create()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            f.controller.create()
            runCurrent()
            assertEquals(1, f.creation.checks.size)
            assertEquals(1, f.creation.created.size)
            assertEquals(2, f.navigator.stack.value.size)
        }

    @Test
    fun cancellingDuringTheCheckCreatesNothing() =
        runTest {
            val f = fixture()
            f.creation.gate = CompletableDeferred()
            f.controller.open("", "T")
            f.controller.create()
            runCurrent()
            f.controller.back()
            runCurrent()
            assertNull(f.controller.state.value)
            assertTrue(f.creation.created.isEmpty())
            assertEquals(1, f.navigator.stack.value.size)
        }

    @Test
    fun theFolderPickerChangesTheFolder() =
        runTest {
            val f = fixture()
            f.controller.open("a")
            f.controller.changeFolder()
            assertEquals(
                "a",
                f.controller.state.value!!
                    .picker!!
                    .path,
            )
            f.controller.pickerOpen("a/b")
            f.controller.back()
            assertEquals(
                "a",
                f.controller.state.value!!
                    .picker!!
                    .path,
            )

            f.controller.pickerNewFolder()
            f.controller.pickerNewFolderText(" new//sub/ ")
            f.controller.pickerNewFolderDone()
            runCurrent()
            val picker =
                f.controller.state.value!!
                    .picker!!
            assertEquals("a/new/sub", picker.path)
            assertNull(picker.newFolder)

            f.controller.pickerConfirm()
            val state = f.controller.state.value!!
            assertNull(state.picker)
            assertEquals("a/new/sub", state.folder)
        }

    @Test
    fun theFolderPickerRefusesBadNewFolders() =
        runTest {
            val f = fixture()
            f.controller.open("")
            f.controller.changeFolder()
            for ((typed, error) in listOf(
                "" to NameError.Blank,
                "x/../y" to NameError.DotSegment,
                "a:b" to NameError.UnsafeCharacter,
                "private/p" to NameError.Unsynced,
            )) {
                f.controller.pickerNewFolder()
                f.controller.pickerNewFolderText(typed)
                f.controller.pickerNewFolderDone()
                runCurrent()
                val picker =
                    f.controller.state.value!!
                        .picker!!
                assertEquals(error, picker.error, typed)
                assertEquals("", picker.path)
            }
            assertEquals(
                "private/p",
                f.controller.state.value!!
                    .picker!!
                    .errorPath,
            )
        }

    @Test
    fun backClosesThePickerAtTheRootThenTheDialog() =
        runTest {
            val f = fixture()
            f.controller.open("")
            f.controller.changeFolder()
            f.controller.back()
            assertNull(
                f.controller.state.value!!
                    .picker,
            )
            f.controller.back()
            assertNull(f.controller.state.value)
        }

    @Test
    fun aWikilinkCreateIsPrefilledFromTheTarget() {
        assertEquals(NewNotePrefill("a/b", "T"), linkPrefill(LinkRef.Wiki("a/b/T|alias#h"), "work"))
        assertEquals(NewNotePrefill("work", "T"), linkPrefill(LinkRef.Wiki("T"), "work"))
        assertEquals(NewNotePrefill("", "T"), linkPrefill(LinkRef.Wiki("T#Heading"), ""))
        assertEquals(NewNotePrefill("work", "T"), linkPrefill(LinkRef.Wiki("T^block"), "work"))
        assertEquals(NewNotePrefill("a", "My note"), linkPrefill(LinkRef.Wiki("/a/My%20note.md"), "work"))
        // A Markdown link is relative to the Note's Folder.
        assertEquals(NewNotePrefill("work/sub", "My note"), linkPrefill(LinkRef.Markdown("sub/My%20note.md#h"), "work"))
        assertEquals(NewNotePrefill("other", "T"), linkPrefill(LinkRef.Markdown("../other/T.md"), "work"))
    }

    @Test
    fun aWikilinkCreateOpensTheDialogPrefilledThenTheSameFlow() =
        runTest {
            val f = fixture()
            val prefill = linkPrefill(LinkRef.Wiki("a/b/T|alias#h"), "work")
            f.controller.open(prefill.folder, prefill.title)
            assertEquals(NewNoteState("T", "a/b"), f.controller.state.value)
            f.controller.create()
            runCurrent()
            assertEquals(listOf("a/b" to "T"), f.creation.checks)
            assertEquals(Route.Note(100, preview = false, focus = true), f.navigator.top)
        }
}
