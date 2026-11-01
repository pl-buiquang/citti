package app.citti.ui.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenableUrlTest {
    @Test
    fun webMailAndPhoneLinksOpen() {
        listOf("https://example.com/a?b=c", "HTTP://example.com", "mailto:a@b.c", "tel:+33123456789").forEach {
            assertTrue(isOpenableUrl(it), it)
        }
    }

    @Test
    fun otherSchemesAndRelativeTextStayInert() {
        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://app.citti.files/a", "citti://daily", "a.pdf", "")
            .forEach { assertFalse(isOpenableUrl(it), it) }
    }
}
