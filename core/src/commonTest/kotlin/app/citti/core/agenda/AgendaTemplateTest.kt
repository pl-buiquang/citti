package app.citti.core.agenda

import kotlin.test.Test
import kotlin.test.assertEquals

class AgendaTemplateTest {
    private val values = mapOf("when" to "09:00–10:00", "summary" to "Standup", "location" to "", "calendar" to "Work")

    @Test
    fun substitutesFieldsAndBlanksUnknownOnes() {
        assertEquals("09:00–10:00 Standup", AgendaTemplate.render("{{ when }} {{summary}}{{nope}}", values))
    }

    @Test
    fun rendersSectionsOnlyWhenTheFieldIsSet() {
        val t = "{{summary}}{{#location}} @ {{location}}{{/location}}{{#calendar}} [{{calendar}}]{{/calendar}}"
        assertEquals("Standup [Work]", AgendaTemplate.render(t, values))
    }

    @Test
    fun rendersInvertedSectionsWhenTheFieldIsEmpty() {
        assertEquals("remote", AgendaTemplate.render("{{^location}}remote{{/location}}", values))
    }

    @Test
    fun supportsNestedSections() {
        assertEquals("<Standup>", AgendaTemplate.render("{{#calendar}}<{{#summary}}{{summary}}{{/summary}}>{{/calendar}}", values))
    }
}
