package app.citti.e2e.flows

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import app.citti.core.fake.Endpoint
import app.citti.core.note.TextCounts
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Note ⋮ menu: Favorite, Rename, Move, Delete, Note info and Insert image, each seen by the server. */
class NoteActionsTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun menuListsTheActionsInOrder() {
        list.openNote("Ideas")
        note.openMenu()
        note.assertMenuItems(
            Strings.MENU_FAVORITE_ADD,
            Strings.MENU_MOVE,
            Strings.MENU_RENAME,
            Strings.MENU_INSERT_IMAGE,
            Strings.MENU_NOTE_INFO,
            Strings.MENU_DELETE,
        )
    }

    @Test
    fun favoriteIsPushed() {
        val id = e2e.setup.id("Ideas")
        list.openNote("Ideas")
        note.setFavorite(true)
        note.assertSnackbar(Strings.TOAST_FAVORITE_ADDED)
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$id") && "\"favorite\":true" in it.body }
        assertTrue(E2e.server.note(id)!!.favorite, "favorite on the server")

        note.setFavorite(false)
        note.assertSnackbar(Strings.TOAST_FAVORITE_REMOVED)
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$id") && "\"favorite\":false" in it.body }
        assertTrue(!E2e.server.note(id)!!.favorite, "favorite still on the server")
    }

    @Test
    fun renameIsPushedAndAClashIsRefused() {
        val id = e2e.setup.id("Ideas")
        list.openNote("Ideas")
        note.rename("Inbox")
        note.assertRenameRefused(Strings.clashError("Inbox", ""))
        note.cancelDialog()
        server.assertRequests(Endpoint.UPDATE, 0)

        note.rename("Ideas 2026")
        note.assertRenameDone()
        note.assertTitle("Ideas 2026")
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$id") && "Ideas 2026" in it.body }
        assertEquals("Ideas 2026", E2e.server.note(id)!!.title)
        note.leave()
        list.assertRow("Ideas 2026")
        list.assertNoRow("Ideas")
    }

    @Test
    fun moveIsPushedAndMoveIntoAHiddenFolderLeavesTheList() {
        val ideas = e2e.setup.id("Ideas")
        list.openNote("Ideas")
        val picker = note.openMove()
        picker.assertNoFolder("private")
        picker.openFolder("work")
        picker.confirm()
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$ideas") && "\"category\":\"work\"" in it.body }
        assertEquals("work", E2e.server.note(ideas)!!.category)
        note.leave()
        list.assertRow("Ideas")

        val shopping = e2e.setup.id("Shopping")
        list.openNote("Shopping")
        note.openMove().apply {
            // Hidden folders are listed in the picker, Unsynced ones are not.
            openFolder("archive")
            confirm()
        }
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$shopping") && "\"category\":\"archive\"" in it.body }
        note.leave()
        list.assertNoRow("Shopping")
    }

    @Test
    fun deletePopsAndIsPushed() {
        val id = e2e.setup.id("Quotes")
        list.openNote("Quotes")
        note.delete()
        list.assertSnackbar(Strings.TOAST_DELETED)
        list.assertNoRow("Quotes")
        server.awaitRequest(Endpoint.DELETE) { it.path.endsWith("/$id") }
        assertNull(E2e.server.note(id), "Quotes is still on the server")
    }

    @Test
    fun noteInfoShowsTheMetadata() {
        val text = E2e.server.note(e2e.setup.id("todo"))!!.content
        val counts = TextCounts.of(text)
        list.openNote("todo")
        note.assertText("Buy milk")
        E2e.server.clearRequests()
        note.openInfo()
        note.assertInfo(Strings.INFO_TITLE, "todo")
        note.assertInfo(Strings.INFO_FOLDER, Strings.TREE_ROOT)
        note.assertInfo(Strings.INFO_WORDS, counts.words.toString())
        note.assertInfo(Strings.INFO_LINES, counts.lines.toString())
        note.assertInfo(Strings.INFO_STATE, Strings.INFO_SYNCED)
        note.assertInfo(Strings.INFO_FAVORITE, Strings.YES)
        note.assertInfo(Strings.INFO_READONLY, Strings.NO)
        server.assertNoRequests()
    }

    @Test
    fun insertImageFromTheGalleryUploadsAndInsertsTheLink() {
        val id = e2e.setup.id("Ideas")
        val png = pngBytes()
        val picked = cacheFile("e2e beach.png", png)
        list.openNote("Ideas")
        withIntents {
            intending(hasAction(MediaStore.ACTION_PICK_IMAGES)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(picked)))
            note.insertImageFromMenu()
            note.pickGallery()
            val put = server.awaitRequest(Endpoint.DAV_PUT)
            assertEquals("*", put.header("If-None-Match"), "the upload never overwrites")
            assertTrue(put.path.endsWith("/attachments/e2e%20beach.png"), "uploaded to ${put.path}")
        }
        // The fixture's .obsidian/app.json names `attachments`: the link is relative to it.
        val link = "![](e2e%20beach.png)"
        note.assertText(link)
        note.assertNoUpload()
        assertContentEquals(png, assertNotNull(E2e.server.file("attachments/e2e beach.png"), "no file on the server"))
        server.awaitRequest(Endpoint.UPDATE) { it.path.endsWith("/$id") && "e2e%20beach.png" in it.body }
    }

    @Test
    fun insertImageFromTheCameraUploadsAShotNamedByTheClock() {
        val png = pngBytes()
        list.openNote("todo")
        note.caretAfter("Buy milk")
        withIntents {
            intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
                // The camera app writes the shot to the FileProvider file it was given.
                val out = checkNotNull(IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java))
                E2e.app.contentResolver
                    .openOutputStream(out)!!
                    .use { it.write(png) }
                ActivityResult(Activity.RESULT_OK, null)
            }
            note.insertImageFromToolbar()
            note.pickCamera()
            server.awaitRequest(Endpoint.DAV_PUT)
        }
        note.assertNoUpload()
        val name = Regex("""!\[]\((IMG_\d{8}_\d{6}\.jpg)\)""").find(note.editorText())?.groupValues?.get(1)
        assertNotNull(name, "no camera link in the text")
        assertTrue(note.editorText().contains("Buy milk\n![]($name)\n"), "the link is on its own line after the caret")
        assertContentEquals(png, E2e.server.file("attachments/$name"))
    }

    private fun <T> withIntents(block: () -> T): T {
        Intents.init()
        try {
            return block()
        } finally {
            Intents.release()
        }
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A file the app's FileProvider serves (`cacheDir/camera/`), as the Photo Picker hands out a content URI. */
    private fun cacheFile(
        name: String,
        bytes: ByteArray,
    ): Uri {
        val app = E2e.app
        val file = File(File(app.cacheDir, "camera").apply { mkdirs() }, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }
}
