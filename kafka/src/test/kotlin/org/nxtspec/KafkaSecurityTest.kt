package org.nxtspec

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KafkaSecurityTest {

    private fun applied(
        protocol: String,
        mechanism: String? = null,
        username: String? = null,
        password: Secret? = null
    ): Properties = Properties().also { applySecurity(it, protocol, mechanism, username, password) }

    @Test
    fun `a plain protocol sets no login module`() {
        val properties = applied("PLAINTEXT")
        assertEquals("PLAINTEXT", properties.getProperty("security.protocol"))
        assertNull(properties.getProperty("sasl.jaas.config"))
    }

    @Test
    fun `an SSL protocol without SASL sets no login module`() {
        val properties = applied("SSL")
        assertEquals("SSL", properties.getProperty("security.protocol"))
        assertNull(properties.getProperty("sasl.jaas.config"))
    }

    @Test
    fun `a PLAIN mechanism uses the plain login module`() {
        val properties = applied("SASL_PLAINTEXT", "PLAIN", "user", Secret("secret"))
        val jaas = properties.getProperty("sasl.jaas.config")
        assertEquals("PLAIN", properties.getProperty("sasl.mechanism"))
        assertTrue(jaas.contains("PlainLoginModule"), jaas)
        assertTrue(jaas.contains("""username="user""""), jaas)
        // The endpoint check belongs to SASL_SSL only.
        assertNull(properties.getProperty("ssl.endpoint.identification.algorithm"))
    }

    @Test
    fun `a SCRAM mechanism uses the scram login module and checks the endpoint over SSL`() {
        val properties = applied("SASL_SSL", "SCRAM-SHA-512", "user", Secret("secret"))
        val jaas = properties.getProperty("sasl.jaas.config")
        assertTrue(jaas.contains("ScramLoginModule"), jaas)
        assertEquals("https", properties.getProperty("ssl.endpoint.identification.algorithm"))
    }

    @Test
    fun `a SASL protocol with no credential fails rather than connect anonymously`() {
        assertFailsWith<IllegalArgumentException> { applied("SASL_PLAINTEXT") }
        assertFailsWith<IllegalArgumentException> { applied("SASL_PLAINTEXT", "PLAIN") }
        assertFailsWith<IllegalArgumentException> { applied("SASL_PLAINTEXT", "PLAIN", "user") }
    }
}
