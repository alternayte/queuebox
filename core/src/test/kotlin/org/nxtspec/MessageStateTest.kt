package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageStateTest {

    @Test
    fun `should allow transition from Pending to Processing`() {
        assertTrue(MessageState.canTransitionTo(MessageState.Pending, MessageState.Processing))
    }

    @Test
    fun `should deny transition from Pending to Sent`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Pending, MessageState.Sent))
    }

    @Test
    fun `should deny transition from Pending to Failed`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Pending, MessageState.Failed("error", 1)))
    }

    @Test
    fun `should deny transition from Pending to Dead`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Pending, MessageState.Dead))
    }

    @Test
    fun `should allow transition from Processing to Sent`() {
        assertTrue(MessageState.canTransitionTo(MessageState.Processing, MessageState.Sent))
    }

    @Test
    fun `should allow transition from Processing to Failed`() {
        assertTrue(MessageState.canTransitionTo(MessageState.Processing, MessageState.Failed("error", 1)))
    }

    @Test
    fun `should deny transition from Processing to Pending`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Processing, MessageState.Pending))
    }

    @Test
    fun `should deny transition from Processing to Dead`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Processing, MessageState.Dead))
    }

    @Test
    fun `should allow transition from Failed to Processing for retry`() {
        assertTrue(MessageState.canTransitionTo(MessageState.Failed("error", 1), MessageState.Processing))
    }

    @Test
    fun `should allow transition from Failed to Dead after max retries`() {
        assertTrue(MessageState.canTransitionTo(MessageState.Failed("error", 5), MessageState.Dead))
    }

    @Test
    fun `should deny transition from Failed to Pending`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Failed("error", 1), MessageState.Pending))
    }

    @Test
    fun `should deny transition from Failed to Sent`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Failed("error", 1), MessageState.Sent))
    }

    @Test
    fun `should deny transition from Sent to any state`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Sent, MessageState.Pending))
        assertFalse(MessageState.canTransitionTo(MessageState.Sent, MessageState.Processing))
        assertFalse(MessageState.canTransitionTo(MessageState.Sent, MessageState.Failed("error", 1)))
        assertFalse(MessageState.canTransitionTo(MessageState.Sent, MessageState.Dead))
    }

    @Test
    fun `should deny transition from Dead to any state`() {
        assertFalse(MessageState.canTransitionTo(MessageState.Dead, MessageState.Pending))
        assertFalse(MessageState.canTransitionTo(MessageState.Dead, MessageState.Processing))
        assertFalse(MessageState.canTransitionTo(MessageState.Dead, MessageState.Sent))
        assertFalse(MessageState.canTransitionTo(MessageState.Dead, MessageState.Failed("error", 1)))
    }

    @Test
    fun `Failed state should contain error message and attempt count`() {
        val failed = MessageState.Failed("Connection timeout", 3)
        kotlin.test.assertEquals("Connection timeout", failed.error)
        kotlin.test.assertEquals(3, failed.attempt)
    }
}
