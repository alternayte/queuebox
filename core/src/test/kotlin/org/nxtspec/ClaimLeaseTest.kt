package org.nxtspec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaimLeaseTest {

    @Test
    fun `slow work renews the claim about every third of the lease`() = runBlocking {
        val renewals = AtomicInteger(0)
        val result = withClaimLease(
            leaseMs = 300,
            renew = {
                renewals.incrementAndGet()
                true
            }
        ) {
            delay(1000)
            "finished"
        }

        assertEquals("finished", result)
        // Work of one second renews every hundred milliseconds. Timing is not exact under
        // load, so the assertion only requires several renewals.
        assertTrue(renewals.get() >= 5, "expected several renewals, got ${renewals.get()}")
    }

    @Test
    fun `fast work returns before the first renewal`() = runBlocking {
        val renewals = AtomicInteger(0)
        val result = withClaimLease(leaseMs = 3000, renew = {
            renewals.incrementAndGet()
            true
        }) { 42 }

        assertEquals(42, result)
        assertEquals(0, renewals.get())
    }

    @Test
    fun `a lost claim cancels the work`() = runBlocking {
        val finished = AtomicBoolean(false)
        assertFailsWith<CancellationException> {
            withClaimLease(leaseMs = 150, renew = { false }) {
                delay(5000)
                finished.set(true)
            }
        }
        assertFalse(finished.get(), "the work must not run to its end after the claim was lost")
    }

    @Test
    fun `a failing renewal counts as a lost claim`() = runBlocking {
        val finished = AtomicBoolean(false)
        assertFailsWith<CancellationException> {
            withClaimLease(leaseMs = 150, renew = { throw IllegalStateException("the database is gone") }) {
                delay(5000)
                finished.set(true)
            }
        }
        assertFalse(finished.get())
    }

    @Test
    fun `the renewal stops when the work fails`() = runBlocking {
        val renewals = AtomicInteger(0)
        assertFailsWith<IllegalStateException> {
            withClaimLease(leaseMs = 300, renew = {
                renewals.incrementAndGet()
                true
            }) {
                delay(200)
                error("the publish failed")
            }
        }
        val afterFailure = renewals.get()
        delay(500)
        assertEquals(afterFailure, renewals.get(), "the renewal must not outlive the work")
    }
}
