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

class HealthManagerTest {

    @Test
    fun `should return healthy when connection is valid`() {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("healthy", status.status)
        assertEquals("up", status.components["database"]?.status)
        verify { mockConnection.close() }
    }

    @Test
    fun `should return unhealthy when connection throws exception`() {
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } throws SQLException("Connection refused")

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["database"]?.status)
    }

    @Test
    fun `should return unhealthy when connection is not valid`() {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()

        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns false
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.check()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["database"]?.status)
        verify { mockConnection.close() }
    }

    @Test
    fun `should close connection even when isValid throws exception`() {
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
    fun `liveness stays healthy when the data source is broken`() {
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } throws SQLException("Connection refused")

        val healthManager = HealthManager(mockDataSource)
        val status = healthManager.live()

        assertEquals("healthy", status.status)
        assertEquals("up", status.components["process"]?.status)
        verify(exactly = 0) { mockDataSource.connection }
    }

    @Test
    fun `readiness turns unhealthy when the poller stops`() {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns true
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
    fun `a contributor that throws counts as down`() {
        val mockConnection = mockk<Connection>()
        val mockDataSource = mockk<DataSource>()
        every { mockDataSource.connection } returns mockConnection
        every { mockConnection.isValid(5) } returns true
        every { mockConnection.close() } just Runs

        val healthManager = HealthManager(
            mockDataSource,
            listOf(SimpleHealthContributor("rabbitmq.orders") { throw IllegalStateException("closed") })
        )

        val status = healthManager.ready()

        assertEquals("unhealthy", status.status)
        assertEquals("down", status.components["rabbitmq.orders"]?.status)
    }
}
