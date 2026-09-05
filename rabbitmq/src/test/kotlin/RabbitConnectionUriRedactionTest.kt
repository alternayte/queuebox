package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Defence in depth for the AMQP URI, at the source rather than after the fact.
 *
 * `ConnectionFactory.setUri` throws a `URISyntaxException` whose message embeds the whole URI it
 * rejected, and a broker URI holds the password. Five review gates repaired the redaction of that
 * text downstream, and four of those repairs created a new defect. The credential must not enter
 * the text at all. The sanitiser stays as the second layer.
 */
class RabbitConnectionUriRedactionTest {

    @Test
    fun `an invalid amqp uri never names the password`() {
        val thrown = assertFailsWith<InvalidAmqpUriException> {
            RabbitConnection("amqp://guest:Sup3rS3cret p@ss@rabbit:5672/vh")
        }

        val rendered = java.io.StringWriter().also { writer ->
            thrown.printStackTrace(java.io.PrintWriter(writer))
        }.toString()

        assertFalse(rendered.contains("Sup3rS3cret"), "the broker password printed: $rendered")
        assertNull(thrown.cause, "the cause would carry the raw URI")
    }

    @Test
    fun `an amqp uri with the wrong scheme never names the password`() {
        val thrown = assertFailsWith<InvalidAmqpUriException> {
            RabbitConnection("http://guest:Sup3rS3cret@rabbit:5672/vh")
        }

        assertFalse(thrown.message!!.contains("Sup3rS3cret"), "the password printed: ${thrown.message}")
    }
}
