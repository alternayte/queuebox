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

/**
 * One named part of the readiness answer.
 *
 * F-050: the database is not the only dependency. The poller, the retention service, the inbox
 * relay and each RabbitMQ connection each contribute a named component.
 */
interface HealthContributor {
    /** The component name in the readiness body. */
    val name: String

    /** True when the component works. */
    fun isHealthy(): Boolean
}

/**
 * A contributor that reads a supplied function.
 */
class SimpleHealthContributor(
    override val name: String,
    private val probe: () -> Boolean
) : HealthContributor {
    override fun isHealthy(): Boolean = try {
        probe()
    } catch (e: Exception) {
        false
    }
}

/**
 * Answers the liveness question and the readiness question.
 *
 * F-049: liveness reports the process only. It does no input or output, so a broken database
 * cannot fail it. Readiness reports the database and every registered contributor.
 */
class HealthManager(
    private val dataSource: DataSource,
    private val contributors: List<HealthContributor> = emptyList()
) {
    /**
     * The process answer. It does no input or output.
     */
    fun live(): HealthStatus = HealthStatus(
        status = HEALTHY,
        components = mapOf("process" to ComponentHealth(UP))
    )

    /**
     * The dependency answer. It checks the database and every contributor.
     */
    fun ready(): HealthStatus {
        val components = LinkedHashMap<String, ComponentHealth>()
        components["database"] = ComponentHealth(if (checkDatabase()) UP else DOWN)
        for (contributor in contributors) {
            components[contributor.name] = ComponentHealth(if (contributor.isHealthy()) UP else DOWN)
        }
        val healthy = components.values.all { it.status == UP }
        return HealthStatus(
            status = if (healthy) HEALTHY else UNHEALTHY,
            components = components
        )
    }

    /**
     * The compatibility answer. It is an alias of readiness. See F-049.
     */
    fun check(): HealthStatus = ready()

    private fun checkDatabase(): Boolean {
        return try {
            dataSource.connection.use { it.isValid(5) }
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val HEALTHY = "healthy"
        const val UNHEALTHY = "unhealthy"
        const val UP = "up"
        const val DOWN = "down"
    }
}
