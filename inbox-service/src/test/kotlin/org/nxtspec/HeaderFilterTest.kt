package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeaderFilterTest {

    private fun filter(require: List<HeaderRule> = emptyList(), exclude: List<HeaderRule> = emptyList()) =
        HeaderFilter(HeaderFilterConfig(require, exclude))

    @Test
    fun `a message passes when it matches every require rule and no exclude rule`() {
        val f = filter(
            require = listOf(
                HeaderRule("x-tenant", equals = "acme"),
                HeaderRule("x-region", `in` = listOf("eu", "us"))
            ),
            exclude = listOf(HeaderRule("x-test", exists = true))
        )

        assertNull(f.firstFailure(mapOf("x-tenant" to "acme", "x-region" to "eu")))
        assertEquals("require[0] (x-tenant equals)", f.firstFailure(mapOf("x-tenant" to "other", "x-region" to "eu")))
        assertEquals("require[1] (x-region in)", f.firstFailure(mapOf("x-tenant" to "acme", "x-region" to "apac")))
        assertEquals(
            "exclude[0] (x-test exists)",
            f.firstFailure(mapOf("x-tenant" to "acme", "x-region" to "us", "x-test" to ""))
        )
    }

    @Test
    fun `a missing header fails a require rule and does not match an exclude rule`() {
        assertEquals(
            "require[0] (x-tenant exists)",
            filter(require = listOf(HeaderRule("x-tenant", exists = true))).firstFailure(emptyMap())
        )
        assertNull(filter(exclude = listOf(HeaderRule("x-tenant", equals = "acme"))).firstFailure(emptyMap()))
    }

    @Test
    fun `the header name matches in any letter case and the value matches exactly`() {
        val f = filter(require = listOf(HeaderRule("X-Tenant", equals = "acme")))

        assertNull(f.firstFailure(mapOf("x-TENANT" to "acme")))
        assertEquals("require[0] (X-Tenant equals)", f.firstFailure(mapOf("x-tenant" to "ACME")))
    }

    @Test
    fun `matches uses the topic glob`() {
        val f = filter(require = listOf(HeaderRule("x-event", matches = "order.*")))

        assertNull(f.firstFailure(mapOf("x-event" to "order.created")))
        assertEquals("require[0] (x-event matches order.*)", f.firstFailure(mapOf("x-event" to "order.created.v2")))
        assertNull(
            filter(require = listOf(HeaderRule("x-event", matches = "order.**"))).firstFailure(
                mapOf(
                    "x-event" to "order.created.v2"
                )
            )
        )
    }
}
