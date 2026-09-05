package org.nxtspec.capture

import org.nxtspec.*
import kotlin.test.*

class CapturePropertiesTest {
    @Test fun `only inserts and snapshot records wake delivery`() {
        assertTrue(isInsert("""{"payload":{"op":"c"}}"""))
        assertTrue(isInsert("""{"op":"r"}"""))
        listOf(null, "null", """{"op":"u"}""", """{"op":"d"}""", """{"payload":null}""").filterNot {
            it == "null"
        }.forEach { assertFalse(isInsert(it)) }
    }

    @Test fun `an unreadable capture record still wakes delivery`() {
        assertTrue(isInsert("not json"))
        assertTrue(isInsert("[1,2,3]"))
        assertFalse(isInsert(null))
    }

    @Test fun `the fingerprint follows the settings that the offsets belong to`() {
        val database =
            DatabaseConfig(
                url = "jdbc:postgresql://localhost:5432/db",
                username = "user",
                password = Secret("secret")
            )
        val config = CaptureConfig(mode = "postgres-logical", stateDirectory = "/tmp/state")
        val base = captureFingerprint(captureProperties(database, config))

        assertEquals(base, captureFingerprint(captureProperties(database, config.copy(identity = "other"))))
        assertEquals(
            base,
            captureFingerprint(
                captureProperties(database, config.copy(connection = CaptureConnection(username = "other")))
            )
        )
        listOf(
            config.copy(slot = "other_slot"),
            config.copy(publication = "other_publication"),
            config.copy(schema = "other_schema")
        ).forEach { changed ->
            assertNotEquals(base, captureFingerprint(captureProperties(database, changed)))
        }
        assertNotEquals(
            base,
            captureFingerprint(captureProperties(database.copy(outboxTableName = "other_table"), config))
        )
        assertNotEquals(
            base,
            captureFingerprint(
                captureProperties(database, config.copy(connection = CaptureConnection(database = "other_database")))
            )
        )
    }

    @Test fun `the fingerprint never carries a secret`() {
        val database =
            DatabaseConfig(
                url = "jdbc:postgresql://localhost:5432/db",
                username = "user",
                password = Secret("secret")
            )
        val config = CaptureConfig(mode = "postgres-logical", stateDirectory = "/tmp/state")
        assertEquals(
            captureFingerprint(captureProperties(database, config)),
            captureFingerprint(captureProperties(database.copy(password = Secret("different")), config))
        )
    }

    @Test fun `PostgreSQL URL settings and explicit overrides reach connector`() {
        val database =
            DatabaseConfig(
                url = "jdbc:postgresql://localhost:5433/db?sslmode=require",
                username = "user",
                password = Secret("secret"),
                outboxTableName = "custom"
            )
        val config =
            CaptureConfig(
                mode = "postgres-logical",
                stateDirectory = "/tmp/state",
                connection = CaptureConnection(hostname = "replica", database = "override")
            )
        val properties = captureProperties(database, config)
        assertEquals("replica", properties["database.hostname"])
        assertEquals("5433", properties["database.port"])
        assertEquals("override", properties["database.dbname"])
        assertEquals("require", properties["database.sslmode"])
        assertEquals("false", properties["slot.drop.on.stop"])
        assertEquals("disabled", properties["publication.autocreate.mode"])
    }

    @Test fun `SQL Server URL settings reach connector without splitting escaped properties`() {
        val database =
            DatabaseConfig(
                type = "sqlserver",
                url = "jdbc:sqlserver://localhost:1434;databaseName=events;encrypt=false",
                username = "user",
                password = Secret("secret")
            )
        val properties =
            captureProperties(database, CaptureConfig(mode = "sqlserver-cdc", stateDirectory = "/tmp/state"))
        assertEquals("localhost", properties["database.hostname"])
        assertEquals("1434", properties["database.port"])
        assertEquals("events", properties["database.names"])
    }
}
