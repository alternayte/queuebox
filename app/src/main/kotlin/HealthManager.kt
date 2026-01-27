package org.nxtspec.app

import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthStatus(
    val status: String,
    val components: Map<String, ComponentHealth>
)

@Serializable
data class ComponentHealth(
    val status: String
)

class HealthManager(private val dataSource: DataSource) {
    fun check(): HealthStatus {
        val dbHealthy = checkDatabase()
        return HealthStatus(
            status = if (dbHealthy) "healthy" else "unhealthy",
            components = mapOf(
                "database" to ComponentHealth(if (dbHealthy) "up" else "down")
            )
        )
    }

    private fun checkDatabase(): Boolean {
        return try {
            dataSource.connection.use { it.isValid(5) }
        } catch (e: Exception) {
            false
        }
    }
}
