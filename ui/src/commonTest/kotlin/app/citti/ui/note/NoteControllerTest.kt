package app.citti.ui.note

import app.citti.core.api.ApiError
import app.citti.core.dav.UploadResult
import app.citti.core.dav.VaultPath
import app.citti.core.markdown.Block
import app.citti.core.markdown.Selection
import app.citti.core.markdown.TextOps
import app.citti.core.markdown.ToolbarAction
import app.citti.core.markdown.apply
import app.citti.core.note.EditorDoc
import app.citti.core.note.LinkTarget
import app.citti.core.note.NoteBadge
import app.citti.core.note.NoteEditor.SaveResult
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.Snack
import app.citti.ui.image.ImageUpload
import app.citti.ui.image.LoadedImage
import app.citti.ui.image.PickedImage
import app.citti.ui.markdown.EditorHost
import app.citti.ui.markdown.EditorState
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeGateway : NoteGateway {
    val row = MutableStateFlow<EditorDoc?>(null)
    val saves = mutableListOf<Pair<String, String?>>()
    val toggles = mutableListOf<Triple<Int, String, String?>>()
    var saveResult: (String, String?) -> SaveResult = { _, _ -> SaveResult.Saved }
    var saved: String? = null
    val targets = mutableMapOf<LinkRef, LinkTarget>()

    override fun observe(): Flow<EditorDoc?> = row

    override suspend fun save(
        text: String,
        baseEtag: String?,
    ): SaveResult {
        saves += text to baseEtag
        return saveResult(text, baseEtag)
    }

    override suspend fun toggleTask(
        box: Int,
        expected: String,
        baseEtag: String?,
    ): SaveResult {
        toggles += Triple(box, expected, baseEtag)
        val current = saved ?: row.value?.text.orEmpty()
        saved = current.substring(0, box + 1) + (if (expected[1] == ' ') "x" else " ") + current.substring(box + 2)
        return SaveResult.Saved
    }

    val inserts = mutableListOf<Triple<Int, String, String?>>()

    override suspend fun insertAt(
        offset: Int,
        block: String,
        baseEtag: String?,
    ): SaveResult {
        inserts += Triple(offset, block, baseEtag)
        val current = saved ?: row.value?.text.orEmpty()
        saved = current.apply(TextOps.insertBlock(current, offset, block))
        return SaveResult.Saved
    }

    override fun savedText(): String? = saved

    override suspend fun resolve(
        folder: String,
        ref: LinkRef,
    ): LinkTarget = targets[ref] ?: LinkTarget.Missing
}

private fun doc(
    text: String?,
    etag: String? = "e1",
    readonly: Boolean = false,
    pending: Boolean = false,
    copy: Pair<Long, String>? = null,
) = EditorDoc(text, etag, "Title", "work", readonly, if (copy != null) NoteBadge.Conflict else null, copy, pending)

/** A view that keeps its text in a string, like the platform view would. */
private class FakeHost(
    private val state: () -> EditorState,
) : EditorHost {
    var text = ""
    var sel = Selection(0)

    override fun text() = text

    override fun replaceSilently(
        start: Int,
        end: Int,
        replacement: String,
        selection: Selection,
    ) {
        text = text.substring(0, start) + replacement + text.substring(end)
        sel = selection
    }

    override fun setAll(
        text: String,
        caret: Int,
    ) {
        this.text = text
        sel = Selection(caret)
    }

    override fun restyle(lines: IntRange) = Unit

    override fun selection() = sel

    override fun scrollToCaret() = Unit

    override fun firstVisibleOffset() = 0

    override fun scrollToOffset(offset: Int) = Unit

    var focusRequests = 0

    override fun focusAndShowKeyboard() {
        focusRequests++
    }

    fun type(s: String) {
        val before = sel
        text = text.substring(0, before.start) + s + text.substring(before.end)
        sel = Selection(before.start + s.length)
        state().userChanged(before.start, "", s, before, sel)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NoteControllerTest {
    private class Fixture(
        scope: TestScope,
        val gateway: FakeGateway,
        val navigator: Navigator,
        val snacks: MutableList<Snack>,
        val controller: NoteController,
    ) {
        val host = FakeHost { controller.editor }

        fun attach() = controller.editor.attach(host)
    }

    private fun TestScope.open(
        row: EditorDoc? = doc("first\nsecond"),
        route: Route.Note = Route.Note(1),
        status: SyncStatus? = SyncStatus.Idle(null, null),
        scope: CoroutineScope = backgroundScope,
        imageUpload: ImageUpload? = null,
        setup: FakeGateway.() -> Unit = {},
    ): Fixture {
        val gateway =
            FakeGateway().also {
                it.row.value = row
                it.setup()
            }
        val navigator = Navigator(listOf(Route.List(), route))
        val snacks = mutableListOf<Snack>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller =
            NoteController(
                scope = scope,
                leaveScope = backgroundScope,
                gateway = gateway,
                status = MutableStateFlow(status),
                route = route,
                navigator = navigator,
                notify = { snacks += it },
                clockMs = { testScheduler.currentTime },
                parseDispatcher = dispatcher,
                imageUpload = imageUpload,
            )
        runCurrent()
        return Fixture(this, gateway, navigator, snacks, controller)
    }

    @Test
    fun opensInEditModeWithTheCaretAtTheEnd() =
        runTest {
            val f = open()
            val state = f.controller.state.value
            assertFalse(state.loading)
            assertFalse(state.preview)
            assertEquals("first\nsecond", f.controller.editor.text)
            assertEquals(12, f.controller.editor.selection.start)
            assertEquals("Title", state.title)
            assertEquals(SaveIndicator.Saved, state.save)
        }

    @Test
    fun aFocusRouteFocusesTheEditorOnceAndClearsTheFlag() =
        runTest {
            val f = open(doc(""), route = Route.Note(1, focus = true))
            f.controller.show(f.navigator.top as Route.Note)
            runCurrent()
            assertEquals(Route.Note(1), f.navigator.top)
            assertFalse(f.controller.state.value.preview)
            // The view attaches after the focus was asked for: it takes it then, once.
            f.attach()
            assertEquals(1, f.host.focusRequests)
            f.controller.show(f.navigator.top as Route.Note)
            runCurrent()
            f.controller.editor.detach(f.host)
            f.attach()
            assertEquals(1, f.host.focusRequests)
        }

    @Test
    fun aReadOnlyNoteOpensInPreviewAndIgnoresTheToggleAndTheToolbar() =
        runTest {
            val f = open(doc("- [ ] a", readonly = true))
            f.attach()
            val state = f.controller.state.value
            assertTrue(state.preview)
            assertTrue(state.readonly)
            assertNotNull(state.doc)
            f.controller.togglePreview()
            f.controller.onToolbar(ToolbarAction.Bold)
            f.controller.onTaskToggle(
                assertNotNull(
                    state.doc!!
                        .blocks
                        .filterIsInstance<Block.ListItem>()
                        .single()
                        .task,
                ),
            )
            runCurrent()
            assertEquals(Route.Note(1), f.navigator.top)
            assertEquals("- [ ] a", f.host.text)
            assertTrue(f.gateway.toggles.isEmpty())
        }

    @Test
    fun typingSavesOnceFiveHundredMillisAfterTheLastKeystroke() =
        runTest {
            val f = open()
            f.attach()
            f.host.type("a")
            advanceTimeBy(300)
            f.host.type("b")
            assertEquals(SaveIndicator.Pending, f.controller.state.value.save)
            advanceTimeBy(499)
            assertTrue(f.gateway.saves.isEmpty())
            advanceTimeBy(2)
            assertEquals(listOf<Pair<String, String?>>("first\nsecondab" to "e1"), f.gateway.saves)
            assertEquals(SaveIndicator.Saved, f.controller.state.value.save)
        }

    @Test
    fun leavingSavesAtOnce() =
        runTest {
            val f = open()
            f.attach()
            f.host.type("x")
            f.controller.onLeave()
            runCurrent()
            assertEquals(listOf<Pair<String, String?>>("first\nsecondx" to "e1"), f.gateway.saves)
            // The timer does not save the same text again.
            advanceTimeBy(1_000)
            assertEquals(1, f.gateway.saves.size)
        }

    @Test
    fun openingAndWaitingSavesNothing() =
        runTest {
            val f = open()
            f.attach()
            advanceTimeBy(2_000)
            assertTrue(f.gateway.saves.isEmpty())
        }

    @Test
    fun aServerVersionWithNothingUnsavedReplacesTheTextOnTheSameLine() =
        runTest {
            val f = open(doc("one\ntwo words\nthree"))
            f.attach()
            f.host.sel = Selection(8)
            f.gateway.row.value = doc("uno\ndos más\nlast", etag = "e2")
            runCurrent()
            assertEquals("uno\ndos más\nlast", f.controller.editor.text)
            assertEquals("uno\ndos más\nlast", f.host.text)
            assertEquals(8, f.host.sel.start)
            assertFalse(f.controller.editor.canUndo.value)
            advanceTimeBy(1_000)
            assertTrue(f.gateway.saves.isEmpty())
            f.host.type("!")
            advanceTimeBy(600)
            assertEquals(
                "e2",
                f.gateway.saves
                    .single()
                    .second,
            )
        }

    @Test
    fun unsavedTextIsNotReplacedAndTheNextSaveIsRefusedAndReloads() =
        runTest {
            val f = open()
            f.attach()
            f.gateway.saveResult =
                { _, base -> if (base == "e1") SaveResult.Conflict(9, "Title (conflict 2026-10-31)") else SaveResult.Saved }
            f.host.type("mine")
            f.gateway.row.value = doc("server text", etag = "e2")
            runCurrent()
            assertEquals("first\nsecondmine", f.controller.editor.text)
            advanceTimeBy(600)
            assertEquals(listOf<Snack>(Snack.ConflictCopy("Title (conflict 2026-10-31)")), f.snacks)
            // The server text shows, the editor is back on the new base.
            assertEquals("server text", f.controller.editor.text)
            assertFalse(f.controller.state.value.loading)
            f.host.type("!")
            advanceTimeBy(600)
            // The caret stays on its line and column, clamped to the new text.
            assertEquals("server tex!t" to "e2", f.gateway.saves.last())
        }

    @Test
    fun afterARefusedEditTheEditorWaitsForTheServerText() =
        runTest {
            val f = open()
            f.attach()
            f.gateway.saveResult = { _, _ -> SaveResult.Conflict(9, "T (conflict)") }
            f.gateway.row.value = doc(null, etag = "e2")
            f.host.type("mine")
            advanceTimeBy(600)
            assertTrue(f.controller.state.value.loading)
            f.gateway.row.value = doc("from server", etag = "e2")
            runCurrent()
            assertFalse(f.controller.state.value.loading)
            assertEquals("from server", f.controller.editor.text)
        }

    @Test
    fun ourOwnPushOnlyMovesTheBase() =
        runTest {
            val f = open(doc("text", pending = true))
            f.attach()
            f.host.type("1")
            advanceTimeBy(600)
            // The push wrote a new etag; the text typed meanwhile is not lost and is based on it.
            f.host.type("2")
            f.gateway.row.value = doc("text1", etag = "e5")
            runCurrent()
            assertEquals("text12", f.controller.editor.text)
            advanceTimeBy(600)
            assertEquals("text12" to "e5", f.gateway.saves.last())
            assertTrue(f.snacks.isEmpty())
        }

    @Test
    fun noTextYetMeansLoadingAndItFillsInWhenItArrives() =
        runTest {
            val f = open(doc(null, etag = "e1"), status = SyncStatus.Offline(null))
            assertTrue(f.controller.state.value.loading)
            assertTrue(f.controller.state.value.offline)
            f.gateway.row.value = doc("arrived")
            runCurrent()
            assertFalse(f.controller.state.value.loading)
            assertEquals("arrived", f.controller.editor.text)
        }

    @Test
    fun aDeletedNoteWithUnsavedTextKeepsItInACopyAndPops() =
        runTest {
            val f = open()
            f.attach()
            f.gateway.saveResult = { _, _ -> SaveResult.Gone(8, "Title (conflict 2026-10-31)") }
            f.host.type("mine")
            f.gateway.row.value = null
            runCurrent()
            assertEquals(listOf<Snack>(Snack.Deleted("Title (conflict 2026-10-31)")), f.snacks)
            assertEquals(
                "first\nsecondmine",
                f.gateway.saves
                    .single()
                    .first,
            )
            assertTrue(f.controller.state.value.gone)
        }

    @Test
    fun aDeletedNoteWithNothingUnsavedJustPops() =
        runTest {
            val f = open()
            f.gateway.row.value = null
            runCurrent()
            assertTrue(f.controller.state.value.gone)
            assertTrue(f.snacks.isEmpty())
            assertTrue(f.gateway.saves.isEmpty())
        }

    @Test
    fun togglingPreviewFlushesFirstThenFlipsTheRoute() =
        runTest {
            val f = open()
            f.attach()
            f.host.type("x")
            f.controller.togglePreview()
            runCurrent()
            assertEquals(1, f.gateway.saves.size)
            assertEquals(Route.Note(1, preview = true), f.navigator.top)
            f.controller.show(f.navigator.top as Route.Note)
            runCurrent()
            val state = f.controller.state.value
            assertTrue(state.preview)
            assertNotNull(state.doc)
            f.controller.togglePreview()
            runCurrent()
            assertEquals(Route.Note(1, preview = false), f.navigator.top)
            f.controller.show(f.navigator.top as Route.Note)
            runCurrent()
            assertNull(f.controller.state.value.doc)
        }

    @Test
    fun theRouteDrivesPreviewAtOpen() =
        runTest {
            val f = open(route = Route.Note(1, preview = true))
            assertTrue(f.controller.state.value.preview)
            assertNotNull(f.controller.state.value.doc)
        }

    @Test
    fun tappingATaskWritesAtOnceAndFlipsTheShownBox() =
        runTest {
            val f = open(doc("- [ ] a\n- [x] b\n"), route = Route.Note(1, preview = true))
            val items =
                f.controller.state.value.doc!!
                    .blocks
                    .filterIsInstance<Block.ListItem>()
            f.controller.onTaskToggle(items[0].task!!)
            runCurrent()
            assertEquals(listOf<Triple<Int, String, String?>>(Triple(2, "[ ]", "e1")), f.gateway.toggles)
            assertEquals("- [x] a\n- [x] b\n", f.controller.editor.text)
            val after =
                f.controller.state.value.doc!!
                    .blocks
                    .filterIsInstance<Block.ListItem>()
            assertEquals(listOf(true, true), after.map { it.task?.checked })
        }

    @Test
    fun aTapOnALinkOpensTheNoteOrExplainsWhyNot() =
        runTest {
            val f =
                open(doc("[[a]] [[b]] [[c]] [[Title]]"), route = Route.Note(1, preview = true)) {
                    targets[LinkRef.Wiki("a")] = LinkTarget.Note(5)
                    targets[LinkRef.Wiki("b")] = LinkTarget.Unsynced("x/b")
                    targets[LinkRef.Wiki("Title")] = LinkTarget.Note(1)
                }
            // They are resolved as soon as the Preview is parsed, and a tap uses that.
            assertEquals(LinkTarget.Note(5), f.controller.state.value.links[LinkRef.Wiki("a")])
            f.controller.onLink(LinkRef.Wiki("a"))
            f.controller.onLink(LinkRef.Wiki("b"))
            f.controller.onLink(LinkRef.Wiki("c"))
            f.controller.onLink(LinkRef.Wiki("Title"))
            runCurrent()
            assertEquals(Route.Note(5), f.navigator.top)
            assertEquals(listOf<Snack>(Snack.LinkUnsynced, Snack.LinkMissing("c")), f.snacks)
            assertEquals(3, f.navigator.stack.value.size)
        }

    @Test
    fun aTapOnAnImageOpensTheViewerOnTheNote() =
        runTest {
            val f = open(doc("![a|900](supernote/a.png)"), route = Route.Note(1, preview = true))
            f.controller.onImage("supernote/a.png")
            assertEquals(Route.ImageViewer(1, "supernote/a.png"), f.navigator.top)
            assertEquals(3, f.navigator.stack.value.size)
        }

    @Test
    fun theConflictBannerOpensTheCopy() =
        runTest {
            val f = open(doc("x", copy = 7L to "Title (conflict 2026-10-31)"))
            val conflict = assertNotNull(f.controller.state.value.conflict)
            assertEquals(7L, conflict.localId)
            f.controller.openConflict()
            assertIs<Route.Note>(f.navigator.top)
            assertEquals(7L, (f.navigator.top as Route.Note).localId)
        }

    // ---- insert image ----

    private class FakePick : PickedImage {
        var disposed = false

        override suspend fun load() = LoadedImage(byteArrayOf(1, 2, 3), "IMG_1.jpg", "image/jpeg")

        override fun dispose() {
            disposed = true
        }
    }

    /** An upload that waits for [result], so the test can act while it runs. */
    private class SlowUpload : ImageUpload {
        val result = CompletableDeferred<UploadResult>()
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun upload(
            image: LoadedImage,
            noteFolder: String,
        ): UploadResult {
            calls += image.name to noteFolder
            return result.await()
        }
    }

    private val uploaded = UploadResult.Uploaded(VaultPath.parse("media/IMG_1.jpg")!!, "![](IMG_1.jpg)")

    @Test
    fun theImageToolbarButtonOpensTheSheetAndChangesNoText() =
        runTest {
            val f = open()
            f.attach()
            f.controller.onToolbar(ToolbarAction.Image)
            runCurrent()
            assertTrue(f.controller.state.value.imageSheet)
            assertEquals("first\nsecond", f.host.text)
            assertFalse(f.controller.editor.canUndo.value)
            f.controller.dismissImageSheet()
            runCurrent()
            assertFalse(f.controller.state.value.imageSheet)
        }

    @Test
    fun aReadOnlyNoteHasNoImageSheet() =
        runTest {
            val f = open(doc("x", readonly = true))
            f.controller.openImageSheet()
            runCurrent()
            assertFalse(f.controller.state.value.imageSheet)
        }

    @Test
    fun anUploadInsertsTheLinkAtTheMarkerMovedByTypingAboveIt() =
        runTest {
            val upload = SlowUpload()
            val f = open(imageUpload = upload)
            f.attach()
            // The caret after "first", where the picker opens.
            f.host.sel = Selection(5)
            f.controller.openImageSheet()
            runCurrent()
            assertTrue(f.controller.state.value.imageSheet)
            f.controller.pickImage()
            runCurrent()
            assertFalse(f.controller.state.value.imageSheet)
            val pick = FakePick()
            f.controller.onImagePicked(pick)
            runCurrent()
            assertTrue(f.controller.state.value.uploading)
            assertEquals(listOf("IMG_1.jpg" to "work"), upload.calls)
            // The user types at the start while the upload runs: the marker moves with the text.
            f.host.sel = Selection(0)
            f.host.type("> ")
            upload.result.complete(uploaded)
            runCurrent()
            assertEquals("> first\n![](IMG_1.jpg)\nsecond", f.host.text)
            assertFalse(f.controller.state.value.uploading)
            assertTrue(pick.disposed)
            // The user's caret stayed where it was typing.
            assertEquals(Selection(2), f.host.sel)
            // One undo step takes the link out again.
            f.controller.undo()
            assertEquals("> first\nsecond", f.host.text)
            advanceTimeBy(1_000)
            assertEquals(
                "> first\nsecond",
                f.gateway.saves
                    .last()
                    .first,
            )
        }

    @Test
    fun anUploadOfflineOrFailedShowsASnackbarAndLeavesTheText() =
        runTest {
            val offline = SlowUpload().also { it.result.complete(UploadResult.Failed(ApiError.Network(RuntimeException()))) }
            val f = open(imageUpload = offline)
            f.attach()
            f.controller.pickImage()
            f.controller.onImagePicked(FakePick())
            runCurrent()
            assertEquals(listOf<Snack>(Snack.UploadOffline), f.snacks)
            assertEquals("first\nsecond", f.host.text)
            assertFalse(f.controller.state.value.uploading)

            val failed = SlowUpload().also { it.result.complete(UploadResult.Failed(ApiError.Server(507))) }
            val g = open(imageUpload = failed)
            g.attach()
            g.controller.pickImage()
            g.controller.onImagePicked(FakePick())
            runCurrent()
            assertEquals(listOf<Snack>(Snack.UploadFailed("HTTP 507")), g.snacks)
            assertEquals("first\nsecond", g.host.text)
        }

    @Test
    fun aCancelledPickOrUploadInsertsNothing() =
        runTest {
            val upload = SlowUpload()
            val f = open(imageUpload = upload)
            f.attach()
            f.controller.pickImage()
            f.controller.onImagePicked(null)
            runCurrent()
            assertFalse(f.controller.state.value.uploading)
            assertTrue(upload.calls.isEmpty())

            f.controller.pickImage()
            f.controller.onImagePicked(FakePick())
            runCurrent()
            f.controller.cancelUpload()
            runCurrent()
            upload.result.complete(uploaded)
            runCurrent()
            assertFalse(f.controller.state.value.uploading)
            assertEquals("first\nsecond", f.host.text)
            assertTrue(f.snacks.isEmpty())
        }

    @Test
    fun anUploadEndingAfterTheRouteLeftGoesThroughInsertAt() =
        runTest {
            val upload = SlowUpload()
            val entry = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val f =
                open(scope = entry, imageUpload = upload) {
                    saveResult = { text, _ ->
                        saved = text
                        SaveResult.Saved
                    }
                }
            f.attach()
            f.host.sel = Selection(5)
            f.controller.pickImage()
            f.controller.onImagePicked(FakePick())
            runCurrent()
            // The user leaves: the text is flushed, the entry's scope is cancelled.
            f.host.sel = Selection(0)
            f.host.type("> ")
            f.controller.onLeave()
            runCurrent()
            f.controller.editor.detach(f.host)
            entry.cancel()
            upload.result.complete(uploaded)
            runCurrent()
            assertEquals(listOf<Triple<Int, String, String?>>(Triple(7, "![](IMG_1.jpg)", "e1")), f.gateway.inserts)
            assertEquals("> first\n![](IMG_1.jpg)\nsecond", f.gateway.saved)
            assertEquals(1, f.gateway.saves.size)
        }
}
