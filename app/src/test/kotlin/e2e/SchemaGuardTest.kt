package org.nxtspec.e2e

import org.nxtspec.ColumnMappingConfig
import org.nxtspec.DatabaseConfig
import org.nxtspec.InboxColumnMapping
import org.nxtspec.Secret
import org.nxtspec.app.StartupFailedException
import org.nxtspec.app.requireInboxHeadersColumn
import org.nxtspec.repository.DatabaseType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaGuardTest : E2ETestBase() {

    private fun config(table: String) = DatabaseConfig(
        url = "unused",
        username = "unused",
        password = Secret("unused"),
        inboxTableName = table,
        columnMapping = ColumnMappingConfig(inbox = InboxColumnMapping(headers = "meta"))
    )

    private fun execute(sql: String) = sharedDataSource().connection.use { conn ->
        conn.createStatement().use { it.execute(sql) }
        if (!conn.autoCommit) conn.commit()
    }

    @Test
    fun `a custom inbox table without the headers column stops the start and gives the statement that adds it`() {
        execute("DROP TABLE IF EXISTS custom_inbox_guard")
        execute("CREATE TABLE custom_inbox_guard (id UUID PRIMARY KEY, headers JSONB)")

        val error = assertFailsWith<StartupFailedException> {
            requireInboxHeadersColumn(sharedDataSource(), config("custom_inbox_guard"), DatabaseType.POSTGRESQL)
        }
        val statement = """ALTER TABLE "custom_inbox_guard" ADD COLUMN "meta" JSONB NOT NULL DEFAULT '{}';"""
        assertTrue(error.message!!.contains("'meta'"), error.message)
        assertTrue(error.message!!.endsWith(statement), error.message)

        execute(statement)
        requireInboxHeadersColumn(sharedDataSource(), config("custom_inbox_guard"), DatabaseType.POSTGRESQL)
    }
}
