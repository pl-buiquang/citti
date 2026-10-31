package app.citti.core.auth

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignOutTest {
    @Test
    fun wipesAccountDataAndKeepsMeta() =
        runTest {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            CittiDatabase.Schema.create(driver)
            val db = CittiDatabase(driver)
            db.metaQueries.upsert("schema", "1")
            db.settingQueries.upsertSetting("syncInterval", "30")
            db.settingQueries.insertExclusion("Archive", "UNSYNCED")
            db.syncStateQueries.upsert("list", "etag", 5, null)
            db.noteQueries.upsertFromServer(1, "e", "Title", "cat", false, 10, false, false)
            assertEquals(1, db.noteQueries.countNotes().executeAsOne())

            val store = CredentialStoreFactory().create()
            store.save(Credentials("https://host", "alice", "pw"))

            signOut(store, db)

            assertNull(store.load())
            assertEquals(0, db.noteQueries.countNotes().executeAsOne())
            assertNull(db.settingQueries.selectSetting("syncInterval").executeAsOneOrNull())
            assertEquals(
                0,
                db.settingQueries
                    .selectExclusionsByKind("UNSYNCED")
                    .executeAsList()
                    .size,
            )
            assertNull(db.syncStateQueries.select("list").executeAsOneOrNull())
            assertEquals("1", db.metaQueries.selectValue("schema").executeAsOne())
        }
}
