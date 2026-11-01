package app.citti

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraShotsTest {
    @Test
    fun removesTheShotsAndLeavesTheRestOfTheCache() {
        val cache = Files.createTempDirectory("citti-cache").toFile()
        try {
            val camera = cache.resolve("camera").apply { mkdirs() }
            camera.resolve("IMG_1.jpg").writeBytes(byteArrayOf(1))
            val images = cache.resolve("images").apply { mkdirs() }
            images.resolve("keep").writeBytes(byteArrayOf(2))
            clearCameraShots(cache)
            assertFalse(camera.exists())
            assertEquals(listOf("keep"), images.list()!!.toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun noCameraFolderIsFine() {
        val cache = Files.createTempDirectory("citti-cache").toFile()
        try {
            clearCameraShots(cache)
            assertTrue(cache.exists())
        } finally {
            cache.deleteRecursively()
        }
    }
}
