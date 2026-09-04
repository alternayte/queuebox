package org.nxtspec.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-074: a disabled retention service must not hold the instance unhealthy.
 *
 * `RetentionService.start` returns at once when `retention.enabled` is false, so `isRunning`
 * stays false for the life of the process. A contributor that reports that state makes
 * `/health` answer `unhealthy` and 503 forever. The Compose health check then never passes,
 * and no orchestrator ever marks the container ready. Retention is disabled by default, so this
 * is the state of every default deployment.
 */
class RetentionHealthContributorTest {

    @Test
    fun `a disabled retention service contributes no component`() {
        val contributors = retentionHealthContributors(enabled = false) { false }

        assertTrue(contributors.isEmpty(), "a disabled retention service must contribute nothing")
    }

    @Test
    fun `an enabled retention service contributes its running state`() {
        val contributors = retentionHealthContributors(enabled = true) { true }

        assertEquals(1, contributors.size)
        assertEquals("retention-service", contributors.single().name)
    }
}
