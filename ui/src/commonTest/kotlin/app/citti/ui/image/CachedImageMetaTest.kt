package app.citti.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachedImageMetaTest {
    @Test
    fun roundTripsWithAndWithoutEtag() {
        val full = CachedImageMeta(1_700_000_000_000, "\"abc123\"", "image/png")
        assertEquals(full, CachedImageMeta.decode(full.encode()))
        val bare = CachedImageMeta(5, null, null)
        assertEquals(bare, CachedImageMeta.decode(bare.encode()))
        assertNull(CachedImageMeta.decode("garbage"))
        assertNull(CachedImageMeta.decode(""))
    }

    @Test
    fun staleAfterTwentyFourHours() {
        val meta = CachedImageMeta(1_000, null, null)
        assertFalse(meta.isStale(1_000 + IMAGE_REVALIDATE_AFTER_MS - 1))
        assertTrue(meta.isStale(1_000 + IMAGE_REVALIDATE_AFTER_MS))
    }

    @Test
    fun fileNameOfALink() {
        assertEquals("a b.png", imageFileName("supernote/a%20b.png"))
        assertEquals("x.png", imageFileName("https://h.example/p/x.png?w=3"))
        assertEquals("x.png", imageFileName("x.png"))
    }
}
