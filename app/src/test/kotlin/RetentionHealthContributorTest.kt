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

    /**
     * Eleventh review gate B2. `inbox.relay.enabled: false` is a documented mode, and the relay
     * contributor was registered unconditionally, so readiness answered 503 for ever.
     */
    @Test
    fun `a disabled inbox relay contributes no component`() {
        val contributors = optionalComponent("inbox-relay", enabled = false) { false }

        assertTrue(contributors.isEmpty(), "a disabled relay must contribute nothing")
    }

    @Test
    fun `an enabled inbox relay contributes its running state`() {
        val contributors = optionalComponent("inbox-relay", enabled = true) { true }

        assertEquals(1, contributors.size)
        assertEquals("inbox-relay", contributors.single().name)
    }
}
