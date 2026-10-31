package app.citti.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compose resources only fill positional placeholders (`%1$s`): a plain `%s` or `%d` shows on screen as typed. */
class StringsPlaceholderTest {
    @Test
    fun everyPlaceholderIsPositional() {
        val file = File("src/commonMain/composeResources/values/strings.xml")
        val plain = Regex("""%[sd]""")
        val bad =
            Regex("""<string name="([^"]+)">(.*?)</string>""")
                .findAll(file.readText())
                .filter { plain.containsMatchIn(it.groupValues[2]) }
                .map { it.groupValues[1] }
                .toList()
        assertEquals(emptyList(), bad, "strings with a non-positional placeholder")
    }
}
