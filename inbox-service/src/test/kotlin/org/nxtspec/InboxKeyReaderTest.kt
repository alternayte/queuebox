package org.nxtspec

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issues #83 and #85. See `docs/specs/inbox-keys-and-delay.md`. */
class InboxKeyReaderTest {

    private val reader = InboxKeyReader()

    private fun read(body: String, expression: String? = null, vararg paths: String): String? = reader.read(
        "orders",
        Json.parseToJsonElement(body),
        BodyKey("aggregateId", expression, paths.takeIf { it.isNotEmpty() }?.let { KeyPaths(*it) })
    )

    @Test
    fun `an expression reads one segment of a text for every shape of the subject`() {
        val expression = "\$split(subject, \"/\")[2]"

        assertEquals("515725", read("""{"subject":"/organization/515725"}""", expression))
        assertEquals("515725", read("""{"subject":"/organization/515725/invitation/66725563"}""", expression))
    }

    @Test
    fun `an expression translates a value, and its path is the fallback for a value the table lacks`() {
        val expression = "\$lookup({\"Organization.Review.accepted.v1\": \"Review.Accepted\"}, type)"

        assertEquals("Review.Accepted", read("""{"type":"Organization.Review.accepted.v1"}""", expression, "$.type"))
        assertEquals("Other.v1", read("""{"type":"Other.v1"}""", expression, "$.type"))
        assertNull(read("""{"type":"Other.v1"}""", expression))
    }

    @Test
    fun `a whole number has no decimal point`() {
        assertEquals("515725", read("""{"subject":"/organization/515725"}""", "\$number(\$split(subject, \"/\")[2])"))
        assertEquals("42", read("""{"a":40}""", "a + 2"))
        assertEquals("1.5", read("""{"a":1}""", "a + 0.5"))
    }

    @Test
    fun `an object, an array, a blank string and a failed expression give no value`() {
        assertNull(read("""{"a":{"b":1}}""", "a"))
        assertNull(read("""{"a":[1,2]}""", "a"))
        assertNull(read("""{"a":"  "}""", "a"))
        assertNull(read("""{"a":"x"}""", "\$number(a)"))
        assertEquals("x", read("""{"a":"x"}""", "\$number(a)", "$.a"))
    }

    @Test
    fun `the first path that gives a value wins`() {
        assertEquals("1", read("""{"orderId":"1"}""", null, "$.orderId", "$.OrderId"))
        assertEquals("2", read("""{"OrderId":"2"}""", null, "$.orderId", "$.OrderId"))
        assertEquals("1", read("""{"orderId":"1","OrderId":"2"}""", null, "$.orderId", "$.OrderId"))
        assertNull(read("""{"other":"3"}""", null, "$.orderId", "$.OrderId"))
    }
}
