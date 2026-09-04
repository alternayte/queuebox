package org.nxtspec.app

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.nxtspec.OutboxPoller
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthManagerTest {

    @Test
    fun `should return healthy when connection is valid`() = kotlinx.coroutines.runBlocking {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(any()) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("healthy", status.status)
        assertEquals("up", status.components["database"]?.status)
        verify { mockConnection.close() }
    }

    @Test
    fun `should return unhealthy when connection throws exception`() = kotlinx.coroutines.runBlocking {
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } throws SQLException("Connection refused")

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["database"]?.status)
    }

    @Test
    fun `should return unhealthy when connection is not valid`() = kotlinx.coroutines.runBlocking {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(any()) } returns false
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["database"]?.status)
        verify { mockConnection.close() }
    }

    @Test
    fun `should close connection even when isValid throws exception`() = kotlinx.coroutines.runBlocking {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } throws SQLException("Timeout")
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["database"]?.status)
        verify { mockConnection.close() }
    }

    @Test
    fun `liveness stays healthy when the data source is broken`() = kotlinx.coroutines.runBlocking {
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } throws SQLException("Connection refused")

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.live()

        assertEquals("healthy", status.status)
        assertEquals("up", status.components["process"]?.status)
        verify(exactly = 0) { mockDataSource.connection }
    }

    @Test
    fun `readiness turns unhealthy when the poller stops`() = kotlinx.coroutines.runBlocking {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(any()) } returns true
        every { mockConnection.close() } just Runs

        val poller = mockk<OutboxPoller>()
        every { poller.isRunning() } returns true

        val healthManager = HealthManager(
            mockDataSource,
            listOf(SimpleHealthContributor("outbox-poller") { poller.isRunning() })
        )

        assertEquals("healthy", healthManager.ready().status)

        every { poller.isRunning() } returns false
        val status = healthManager.ready()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["outbox-poller"]?.status)
        assertEquals("up", status.components["database"]?.status)
    }

    @Test
    fun `a contributor that throws counts as down`() = kotlinx.coroutines.runBlocking {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(any()) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(
            mockDataSource,
            listOf(SimpleHealthContributor("rabbitmq.orders") { throw IllegalStateException("closed") })
        )

        val status = healthManager.ready()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["rabbitmq.orders"]?.status)
    }

    // --- F-049 and F-050: a slow check must not hold the answer ---

    @Test
    fun `a contributor that never answers counts as down inside the bound`() =
        kotlinx.coroutines.runBlocking {
            val mockConnection = mockk<Connection>()
            val mockDataSource = mockk<DataSource>()
            every { mockDataSource.connection } returns mockConnection
            every { mockConnection.isValid(any()) } returns true
            every { mockConnection.close() } just Runs

            val healthManager = HealthManager(
                dataSource = mockDataSource,
                contributors = listOf(
                    SimpleHealthContributor("frozen") {
                        Thread.sleep(30000)
                        true
                    }
                ),
                checkTimeoutMs = 200
            )

            val elapsed = kotlin.system.measureTimeMillis {
                val status = healthManager.ready()

                assertEquals("unhealthy", status.status)
                assertEquals("down", status.components["frozen"]?.status)
                assertEquals("up", status.components["database"]?.status)
            }

            assertTrue(
                elapsed < 5000,
                "Readiness must answer inside the bound, not wait for the check. Took ${elapsed}ms"
            )
        }
}
