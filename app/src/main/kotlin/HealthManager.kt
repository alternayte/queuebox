package org.nxtspec.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class HealthStatus(val status: String, val components: Map<String, ComponentHealth>)

@Serializable
data class ComponentHealth(val status: String)

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

    /**
     * An advisory component is reported but does not decide readiness.
     *
     * Change data capture is the case this exists for. Capture only shortens the delivery
     * delay, and SQL delivery continues without it, so a capture fault must not take a
     * working instance out of service. The component still shows `down`, so an operator
     * sees the fault.
     */
    val advisory: Boolean get() = false
}

/**
 * A contributor that reads a supplied function.
 */
class SimpleHealthContributor(
    override val name: String,
    override val advisory: Boolean = false,
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
    private val contributors: List<HealthContributor> = emptyList(),
    /** Upper bound for one readiness check. A slower check counts as down. See F-049. */
    private val checkTimeoutMs: Long = 3000
) {
    // Every check runs here, so a blocking check cannot hold the caller.
    private val checkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The process answer. It does no input or output.
     */
    fun live(): HealthStatus = HealthStatus(
        status = HEALTHY,
        components = mapOf("process" to ComponentHealth(UP))
    )

    /**
     * The dependency answer. It checks the database and every contributor.
     *
     * Every check runs on the input and output dispatcher, under a bound. The database check
     * blocks for the pool timeout when the pool is exhausted, and a contributor can block for as
     * long as it wants. Without the bound a slow dependency holds a server thread and the probe
     * times out, rather than answering 503. See F-049 and F-050.
     */
    suspend fun ready(): HealthStatus {
        val components = LinkedHashMap<String, ComponentHealth>()

        components["database"] = ComponentHealth(if (checkBounded { checkDatabase() }) UP else DOWN)
        for (contributor in contributors) {
            components[contributor.name] =
                ComponentHealth(if (checkBounded { contributor.isHealthy() }) UP else DOWN)
        }

        val advisoryNames = contributors.filter { it.advisory }.map { it.name }.toSet()
        val healthy = components.entries.all { it.key in advisoryNames || it.value.status == UP }
        return HealthStatus(
            status = if (healthy) HEALTHY else UNHEALTHY,
            components = components
        )
    }

    /**
     * The compatibility answer. It is an alias of readiness. See F-049.
     */
    suspend fun check(): HealthStatus = ready()

    /**
     * Runs one check on the input and output dispatcher, under the timeout. A check that does not
     * answer counts as down.
     *
     * The check runs in its own job. A blocking call inside it does not observe cancellation, so
     * the timeout cannot stop the work. It does stop the wait, which is what the probe needs:
     * readiness answers 503 rather than holding a server thread. The abandoned job ends when the
     * blocking call returns.
     */
    private suspend fun checkBounded(check: () -> Boolean): Boolean {
        // The job belongs to the health manager, not to the caller. A structured child would
        // make the caller wait for it, and a blocking call inside it cannot be cancelled.
        val running = checkScope.async { runCatching { check() }.getOrDefault(false) }
        return withTimeoutOrNull(checkTimeoutMs) { running.await() } ?: false
    }

    private fun checkDatabase(): Boolean = try {
        dataSource.connection.use { it.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS) }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val DATABASE_VALIDATION_TIMEOUT_SECONDS = 2
        const val HEALTHY = "healthy"
        const val UNHEALTHY = "unhealthy"
        const val UP = "up"
        const val DOWN = "down"
    }
}

/**
 * The readiness components that the retention service contributes.
 *
 * F-074: `RetentionService.start` returns at once when retention is disabled, so `isRunning`
 * stays false for the life of the process. A component that reports that state makes the
 * readiness answer `unhealthy` forever, and the container never becomes ready. Retention is
 * disabled by default, so a disabled service contributes nothing instead.
 *
 * @param enabled the value of `retention.enabled`.
 * @param isRunning the running state of the service.
 */
fun retentionHealthContributors(enabled: Boolean, isRunning: () -> Boolean): List<HealthContributor> =
    optionalComponent("retention-service", enabled, isRunning = isRunning)

/**
 * The readiness component of a worker that the configuration can turn off.
 *
 * Eleventh review gate B2. `inbox.relay.enabled: false` is a documented mode, and the relay
 * contributor was registered unconditionally. `InboxRelay.start` returns before it sets the
 * running flag when the relay is disabled, so `/health/ready` answered 503 for the life of the
 * process and no orchestrator ever routed traffic to the instance. That is the same defect the
 * retention service had, so the two share one rule.
 */
fun optionalComponent(
    name: String,
    enabled: Boolean,
    advisory: Boolean = false,
    isRunning: () -> Boolean
): List<HealthContributor> = if (enabled) listOf(SimpleHealthContributor(name, advisory, isRunning)) else emptyList()
