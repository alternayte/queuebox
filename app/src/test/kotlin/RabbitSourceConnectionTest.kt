package org.nxtspec.app

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the third adversarial review gate, defect 2.
 *
 * An unparsable AMQP source URL must not reach stderr. The container log is the place where an
 * operator reads a start failure, so the broker password must never be part of the message.
 */
class RabbitSourceConnectionTest {

    private val badUrl = "amqp://appuser:s3cr3t p@ss@rabbit:5672"

    @Test
    fun `an invalid source url fails the start with a sanitised message`() {
        val error = assertFailsWith<IllegalStateException> {
            createSourceConnection("orders", badUrl)
        }

        val message = error.message ?: ""
        assertTrue(
            message.contains("orders"),
            "The message must name the source. message=$message"
        )
        assertFalse(
            message.contains("s3cr3t"),
            "The message must not carry the broker password. message=$message"
        )
        assertFalse(
            message.contains("rabbit:5672"),
            "The message must not carry the URI. message=$message"
        )
    }

    @Test
    fun `the cause of an invalid source url carries no uri either`() {
        val error = assertFailsWith<IllegalStateException> {
            createSourceConnection("orders", badUrl)
        }

        val causeMessage = error.cause?.message ?: ""
        assertFalse(
            causeMessage.contains("s3cr3t"),
            "The cause must not carry the broker password. cause=$causeMessage"
        )
    }

    @Test
    fun `a valid source url builds a connection`() {
        // The constructor only parses the URI. It opens no socket, so no broker is needed.
        createSourceConnection("orders", "amqp://guest:guest@localhost:5672")
    }
}
