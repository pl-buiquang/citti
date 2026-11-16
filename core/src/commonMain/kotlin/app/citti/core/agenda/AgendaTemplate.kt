package app.citti.core.agenda

/**
 * The Event template, as in the Obsidian CalDAV Agenda plugin:
 * - `{{field}}`: the field's value ("" when unknown)
 * - `{{#field}}…{{/field}}`: shown only when the field is not blank
 * - `{{^field}}…{{/field}}`: shown only when the field is blank
 *
 * The field names avoid `date`, `time` and `title`, which Obsidian's Templates plugin replaces everywhere in a
 * template file, code blocks included.
 */
object AgendaTemplate {
    val FIELDS =
        listOf("when", "start", "end", "day", "duration", "summary", "location", "description", "calendar", "url", "status")

    private val SECTION = Regex("""\{\{([#^])\s*([\w-]+)\s*\}\}([\s\S]*?)\{\{/\s*\2\s*\}\}""")
    private val VARIABLE = Regex("""\{\{\s*([\w-]+)\s*\}\}""")

    fun render(
        template: String,
        values: Map<String, String>,
    ): String {
        var out = template
        // Again until stable, so nested sections work.
        var prev: String
        do {
            prev = out
            out =
                SECTION.replace(out) { m ->
                    val present = values[m.groupValues[2]].orEmpty().isNotBlank()
                    if ((m.groupValues[1] == "#") == present) m.groupValues[3] else ""
                }
        } while (out != prev)
        return VARIABLE.replace(out) { m -> values[m.groupValues[1]].orEmpty() }
    }
}
