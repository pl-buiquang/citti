package app.citti.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaTest {
    private fun database(): CittiDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CittiDatabase.Schema.create(driver)
        return CittiDatabase(driver)
    }

    @Test
    fun missingKeyIsNull() {
        assertNull(database().metaQueries.selectValue("schema").executeAsOneOrNull())
    }

    @Test
    fun upsertReplacesValue() {
        val queries = database().metaQueries
        queries.upsert("schema", "1")
        queries.upsert("schema", "2")
        assertEquals("2", queries.selectValue("schema").executeAsOne())
    }
}
