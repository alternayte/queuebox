package org.nxtspec.e2e

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals

/**
 * Exposed resolves a transaction against one global default database.
 *
 * `E2EStartupTest` and `E2EShutdownTest` let the application connect its own pool and then close
 * it, which leaves that default pointing at a closed pool. The next thing to open a transaction
 * then fails with "HikariDataSource has been closed", and the failure lands on a later test that
 * did nothing wrong. `E2ETestBase` binds the shared database before every test so that cannot
 * happen; this test proves the binding works.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EDatabaseBindingTest : E2ETestBase() {

    @Test
    @Order(1)
    fun `a test that closes its own pool leaves the default database pointing at it`() {
        val config = HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
        }
        val ownPool = HikariDataSource(config)
        val ownDatabase = Database.connect(ownPool)
        TransactionManager.defaultDatabase = ownDatabase

        // Exactly what the startup and shutdown tests do at the end of their work.
        ownPool.close()

        assertEquals(ownDatabase, TransactionManager.defaultDatabase)
    }

    @Test
    @Order(2)
    fun `the next test still reaches the database`() {
        // Without the binding in E2ETestBase this call fails with "HikariDataSource has been
        // closed", because the default still names the pool the previous test closed.
        val id = insertOutboxMessage(topic = "order.created")
        assertEquals("pending", getOutboxMessageState(id))
    }
}
