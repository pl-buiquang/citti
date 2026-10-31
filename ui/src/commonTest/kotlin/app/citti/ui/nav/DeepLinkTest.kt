package app.citti.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    @Test
    fun aNoteLinkRoundTripsWithAndWithoutALine() {
        assertEquals(DeepLinkTarget.OpenNote(42, null), DeepLink.parse(DeepLink.noteUri(42, null)))
        assertEquals(DeepLinkTarget.OpenNote(42, 17), DeepLink.parse(DeepLink.noteUri(42, 17)))
        assertEquals(DeepLinkTarget.OpenNote(7, 0), DeepLink.parse(DeepLink.noteUri(7, 0)))
    }

    @Test
    fun theUriShapeIsStable() {
        assertEquals("citti://note/5?line=3", DeepLink.noteUri(5, 3))
        assertEquals("citti://note/5", DeepLink.noteUri(5, null))
        assertEquals("citti://daily", DeepLink.dailyUri(false))
        assertEquals("citti://daily?resume=1", DeepLink.dailyUri(true))
    }

    @Test
    fun aWrongSchemeOrHostIsRejected() {
        assertNull(DeepLink.parse("https://note/1"))
        assertNull(DeepLink.parse("citti://notes/1"))
        assertNull(DeepLink.parse("citti://other"))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("citti:/note/1"))
    }

    @Test
    fun aBadIdIsRejected() {
        for (id in listOf("0", "-1", "abc", "", "+5", "1.5", "99999999999999999999")) {
            assertNull(DeepLink.parse("citti://note/$id"), id)
        }
        assertNull(DeepLink.parse("citti://note"))
    }

    @Test
    fun anExtraPathSegmentIsRejected() {
        assertNull(DeepLink.parse("citti://note/1/2"))
        assertNull(DeepLink.parse("citti://note/1/"))
        assertNull(DeepLink.parse("citti://daily/2026-10-31"))
    }

    @Test
    fun aBadLineIsDroppedButTheNoteStillOpens() {
        for (line in listOf("-1", "x", "", "1.5", "99999999999")) {
            assertEquals(DeepLinkTarget.OpenNote(3, null), DeepLink.parse("citti://note/3?line=$line"), line)
        }
    }

    @Test
    fun theDailyLinkRoundTrips() {
        assertEquals(DeepLinkTarget.Today(false), DeepLink.parse(DeepLink.dailyUri(false)))
        assertEquals(DeepLinkTarget.Today(true), DeepLink.parse(DeepLink.dailyUri(true)))
        assertEquals(DeepLinkTarget.Today(false), DeepLink.parse("citti://daily?resume=yes"))
        assertEquals(DeepLinkTarget.Today(false), DeepLink.parse("citti://daily?resume=0"))
    }

    @Test
    fun otherQueryParametersAndFragmentsAreIgnored() {
        assertEquals(DeepLinkTarget.OpenNote(9, 4), DeepLink.parse("citti://note/9?x=1&line=4#top"))
    }
}
