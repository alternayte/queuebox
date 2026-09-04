package org.nxtspec.app

import org.nxtspec.ColumnMappingConfig
import org.nxtspec.DatabaseConfig
import org.nxtspec.InboxColumnMapping
import org.nxtspec.OutboxColumnMapping
import org.nxtspec.Secret
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Covers F-030. The bundled migrations create the default schema only.
 */
class MigrationGuardTest {

    private fun database() = DatabaseConfig(
        url = "jdbc:postgresql://localhost:5432/queuebox",
        username = "postgres",
        password = Secret("secret")
    )

    @Test
    fun `accepts the default schema`() {
        requireDefaultSchemaForMigrations(database())
    }

    @Test
    fun `rejects a renamed outbox table`() {
        val exception = assertFailsWith<MigrationNotApplicableException> {
            requireDefaultSchemaForMigrations(database().copy(outboxTableName = "my_outbox"))
        }

        assertContains(exception.message!!, "database.outboxTableName")
        assertContains(exception.message!!, "database.migrate")
    }

    @Test
    fun `rejects a renamed inbox table`() {
        val exception = assertFailsWith<MigrationNotApplicableException> {
            requireDefaultSchemaForMigrations(database().copy(inboxTableName = "my_inbox"))
        }

        assertContains(exception.message!!, "database.inboxTableName")
    }

    @Test
    fun `rejects a renamed column`() {
        val exception = assertFailsWith<MigrationNotApplicableException> {
            requireDefaultSchemaForMigrations(
                database().copy(
                    columnMapping = ColumnMappingConfig(
                        outbox = OutboxColumnMapping(topic = "subject"),
                        inbox = InboxColumnMapping()
                    )
                )
            )
        }

        assertContains(exception.message!!, "database.columnMapping.outbox")
    }
}
