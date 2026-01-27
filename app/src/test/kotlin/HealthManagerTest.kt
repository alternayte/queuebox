package org.nxtspec.app

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
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
}
