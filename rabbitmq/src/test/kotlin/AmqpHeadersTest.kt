package org.nxtspec

import com.rabbitmq.client.impl.LongStringHelper
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class AmqpHeadersTest {

    @Test
    fun `typed AMQP values become strings and nested values become JSON text`() {
        val headers = amqpHeaders(
            mapOf(
                "x-tenant" to LongStringHelper.asLongString("acme"),
                "x-count" to 3,
                "x-flag" to true,
                "x-at" to Date(0),
                "x-table" to mapOf("a" to LongStringHelper.asLongString("b"), "n" to 1),
                "x-list" to listOf(1, LongStringHelper.asLongString("two")),
                "x-raw" to byteArrayOf(0xFF.toByte()),
                "x-null" to null
            )
        )

        assertEquals(
            mapOf(
                "x-tenant" to "acme",
                "x-count" to "3",
                "x-flag" to "true",
                "x-at" to "1970-01-01T00:00:00Z",
                "x-table" to """{"a":"b","n":1}""",
                "x-list" to """[1,"two"]""",
                "x-raw" to "base64:/w=="
            ),
            headers
        )
    }
}
