package app.citti.ui.settings

import app.citti.core.settings.AddResult
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.SyncNetwork
import app.citti.core.sync.SyncTrigger
import app.citti.ui.image.ImageCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore(
    val local: Map<String, Pair<Int, Int>> = emptyMap(),
) : SettingsStore {
    val interval = MutableStateFlow(30)
    val network = MutableStateFlow(SyncNetwork.ANY)
    val unsynced = MutableStateFlow(emptyList<String>())
    val hidden = MutableStateFlow(emptyList<String>())
    val imageMb = MutableStateFlow(500)

    override fun setImageCacheMb(mb: Int) {
        imageMb.value = mb
    }

    override fun setIntervalMinutes(minutes: Int) {
        interval.value = minutes
    }

    override fun setNetwork(network: SyncNetwork) {
        this.network.value = network
    }

    override fun addExclusion(
        kind: ExclusionKind,
        path: String,
    ): AddResult {
        val flow = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
        if (path in flow.value) return AddResult.Duplicate
        flow.value = flow.value + path
        return AddResult.Added(path)
    }

    override fun removeExclusion(
        kind: ExclusionKind,
        path: String,
    ) {
        val flow = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
        flow.value = flow.value - path
    }

    override fun notesUnder(path: String) = local[path]?.first ?: 0

    override fun pendingUnder(path: String) = local[path]?.second ?: 0
}

private class FakeImageCache(
    var bytes: Long = 312L * 1024 * 1024,
) : ImageCache {
    var cleared = 0

    override suspend fun size() = bytes

    override suspend fun clear() {
        cleared++
        bytes = 0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    private val images = FakeImageCache()

    private val triggers = mutableListOf<SyncTrigger>()
    private var signedOut = 0
    private var signInAgain = 0

    private fun TestScope.controller(store: FakeStore) =
        SettingsController(
            scope = backgroundScope,
            account = AccountInfo("cloud.example.com", "alice"),
            store = store,
            interval = store.interval,
            network = store.network,
            status = MutableStateFlow(null),
            unsynced = store.unsynced,
            hidden = store.hidden,
            knownFolders = MutableStateFlow(listOf("a", "a/b")),
            imageCacheMb = store.imageMb,
            imageCache = images,
            sync = { triggers += it },
            signOut = { signedOut++ },
            signInAgain = { signInAgain++ },
        )

    @Test
    fun addingHiddenFolderStoresItAndSyncsManually() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.openPicker(ExclusionKind.HIDDEN)
            assertEquals(SettingsPage.PickFolder(ExclusionKind.HIDDEN), c.state.value.page)
            c.addFolder(ExclusionKind.HIDDEN, " /secret/ ")
            assertEquals(listOf("secret"), c.state.value.hidden)
            assertEquals(SettingsPage.Home, c.state.value.page)
            assertEquals(listOf(SyncTrigger.Manual), triggers)
        }

    @Test
    fun unsyncedFolderWithLocalNotesAsksFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore(local = mapOf("old" to (12 to 2)))
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "old")
            assertEquals(SettingsConfirm.AddUnsynced("old", 12, 2), c.state.value.confirm)
            assertTrue(
                c.state.value.unsynced
                    .isEmpty(),
            )
            assertTrue(triggers.isEmpty())

            c.dismissConfirm()
            assertNull(c.state.value.confirm)
            assertTrue(triggers.isEmpty())

            c.addFolder(ExclusionKind.UNSYNCED, "old")
            c.confirm()
            assertEquals(listOf("old"), c.state.value.unsynced)
            assertNull(c.state.value.confirm)
            assertEquals(listOf(SyncTrigger.Manual), triggers)
        }

    @Test
    fun unsyncedFolderWithoutLocalNotesAndHiddenNeverAsk() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore(local = mapOf("old" to (5 to 0)))
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "empty")
            assertNull(c.state.value.confirm)
            c.addFolder(ExclusionKind.HIDDEN, "old")
            assertNull(c.state.value.confirm)
            assertEquals(listOf("empty"), c.state.value.unsynced)
            assertEquals(listOf("old"), c.state.value.hidden)
        }

    @Test
    fun blankAndDuplicateAreRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.openPicker(ExclusionKind.UNSYNCED)
            c.addFolder(ExclusionKind.UNSYNCED, " / ")
            assertEquals(FolderError.Blank, c.state.value.folderError)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            c.openPicker(ExclusionKind.UNSYNCED)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            assertEquals(FolderError.AlreadyListed, c.state.value.folderError)
            assertEquals(listOf("x"), c.state.value.unsynced)
            assertEquals(1, triggers.size)
        }

    @Test
    fun removingSyncsAndBackWalksOutOfPickerAndDialogs() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            c.removeFolder(ExclusionKind.UNSYNCED, "x")
            assertTrue(
                c.state.value.unsynced
                    .isEmpty(),
            )
            assertEquals(2, triggers.size)

            c.openPicker(ExclusionKind.HIDDEN)
            c.askSignOut()
            assertTrue(c.onBack())
            assertNull(c.state.value.confirm)
            assertTrue(c.onBack())
            assertEquals(SettingsPage.Home, c.state.value.page)
            assertFalse(c.onBack())
        }

    @Test
    fun syncSettingsPassThroughAndAccountActions() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.setInterval(0)
            c.setNetwork(SyncNetwork.UNMETERED)
            assertEquals(0, c.state.value.intervalMinutes)
            assertEquals(SyncNetwork.UNMETERED, c.state.value.network)
            c.syncNow()
            assertEquals(listOf(SyncTrigger.Manual), triggers)

            c.signInAgain()
            assertEquals(1, signInAgain)
            assertEquals(0, signedOut)
            c.askSignOut()
            assertEquals(SettingsConfirm.SignOut, c.state.value.confirm)
            assertEquals(0, signedOut)
            c.confirm()
            assertEquals(1, signedOut)
            assertNull(c.state.value.confirm)
        }

    @Test
    fun imageCacheSizeChoiceIsStoredAndShown() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            assertEquals(500, c.state.value.imageCacheMb)
            assertEquals(312L * 1024 * 1024, c.state.value.imageCacheBytes)
            c.setImageCacheMb(2000)
            assertEquals(2000, store.imageMb.value)
            assertEquals(2000, c.state.value.imageCacheMb)
        }

    @Test
    fun clearingTheImageCacheAsksFirstThenEmptiesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.askClearImages()
            assertEquals(SettingsConfirm.ClearImages, c.state.value.confirm)
            c.dismissConfirm()
            assertEquals(0, images.cleared)
            c.askClearImages()
            c.confirm()
            assertEquals(1, images.cleared)
            assertNull(c.state.value.confirm)
            assertEquals(0L, c.state.value.imageCacheBytes)
        }
}
