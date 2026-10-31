package app.citti.core.note

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.insertNew
import app.citti.core.updateContent
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkResolverTest {
    private val db =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val notes = db.noteQueries
    private val resolver = LinkResolver(db)
    private var remote = 0L

    private fun add(
        title: String,
        folder: String = "",
        modified: Long = 1,
        content: String = "body",
    ): Long {
        notes.upsertFromServer(++remote, "e$remote", title, folder, false, modified, false, false)
        val id = notes.selectByRemoteId(remote).executeAsOne().local_id
        notes.updateContent(content, "e$remote", id)
        return id
    }

    private fun note(id: Long) = LinkTarget.Note(id)

    @Test
    fun pathFormIsExactAndCaseInsensitive() {
        val a = add("2026-W44", "journal")
        add("2026-W44", "other")
        assertEquals(note(a), resolver.resolve("elsewhere", "journal/2026-W44"))
        assertEquals(note(a), resolver.resolve("elsewhere", "JOURNAL/2026-w44"))
        assertEquals(LinkTarget.Missing, resolver.resolve("", "journal/nope"))
    }

    @Test
    fun bareNamePrefersTheCurrentFolderThenTheVault() {
        val here = add("Plan", "work", modified = 1)
        add("Plan", "home", modified = 9)
        val unique = add("Only", "deep/er")
        assertEquals(note(here), resolver.resolve("work", "Plan"))
        // Not in the current folder: the most recent duplicate.
        assertEquals(
            LinkTarget.Note(
                notes
                    .selectByTitle("Plan")
                    .executeAsList()
                    .first()
                    .local_id,
            ),
            resolver.resolve("none", "plan"),
        )
        assertEquals(note(unique), resolver.resolve("work", "only"))
        assertEquals(LinkTarget.Missing, resolver.resolve("work", "Ghost"))
    }

    @Test
    fun headingBlockMdAndEscapesAreStripped() {
        val a = add("My Note", "f")
        assertEquals(note(a), resolver.resolve("f", "My Note#Heading"))
        assertEquals(note(a), resolver.resolve("f", "My Note^abc123"))
        assertEquals(note(a), resolver.resolve("f", "My Note.md"))
        assertEquals(note(a), resolver.resolve("x", "f/My%20Note.md"))
    }

    @Test
    fun markdownLinksAreRelativeThenVaultPaths() {
        val sibling = add("action-plan", "work/sub")
        val root = add("readme", "")
        val far = add("deep", "a/b")
        assertEquals(note(sibling), resolver.resolveMarkdownLink("work/sub", "action-plan.md"))
        assertEquals(note(sibling), resolver.resolveMarkdownLink("elsewhere", "work/sub/action-plan.md"))
        assertEquals(note(far), resolver.resolveMarkdownLink("x/y", "../../a/b/deep.md#h"))
        assertEquals(note(root), resolver.resolveMarkdownLink("work", "/readme.md"))
        assertEquals(LinkTarget.Missing, resolver.resolveMarkdownLink("work", "../../../x.md"))
    }

    @Test
    fun hiddenFoldersResolveAndDeletedRowsDoNot() {
        val hidden = add("Secret", "private")
        db.settingQueries.insertExclusion("private", "HIDDEN")
        notes.recomputeHidden()
        assertEquals(note(hidden), resolver.resolve("", "private/Secret"))
        notes.markDeleted(hidden)
        assertEquals(LinkTarget.Missing, resolver.resolve("", "private/Secret"))
    }

    @Test
    fun anUnsyncedFolderTargetIsUnsynced() {
        db.settingQueries.insertExclusion("archives", "UNSYNCED")
        assertEquals(LinkTarget.Unsynced("archives/old/Thing"), resolver.resolve("", "archives/old/Thing"))
        assertEquals(LinkTarget.Missing, resolver.resolve("", "elsewhere/Thing"))
    }

    @Test
    fun suggestRanksExactThenPrefixThenTheRest() {
        val rest = add("A big plan", modified = 9)
        val prefix = add("Planning", modified = 5)
        val exact = add("Plan", modified = 1)
        add("Unrelated", modified = 10)
        assertEquals(listOf(exact, prefix, rest), resolver.suggest("plan").map { it.localId })
    }

    @Test
    fun suggestFoldsAccentsAndIncludesHidden() {
        val a = add("Été 2026", "journal")
        db.settingQueries.insertExclusion("journal", "HIDDEN")
        notes.recomputeHidden()
        assertEquals(listOf(a), resolver.suggest("ete").map { it.localId })
        assertEquals(Suggestion(a, "Été 2026", "journal"), resolver.suggest("ÉTÉ 20").single())
    }

    @Test
    fun suggestUsesTheLastPathPartAndSurvivesOperatorWords() {
        val a = add("Plan", "work")
        assertEquals(listOf(a), resolver.suggest("work/pla").map { it.localId })
        assertEquals(emptyList(), resolver.suggest("zzz"))
        resolver.suggest("OR")
        resolver.suggest("a NOT")
        resolver.suggest("\"quote")
    }

    @Test
    fun emptyPrefixGivesTheEightMostRecent() {
        val ids = (1..12).map { add("N$it", modified = it.toLong()) }
        assertEquals(ids.reversed().take(8), resolver.suggest("").map { it.localId })
        assertEquals(8, resolver.suggest("N").size)
    }
}
