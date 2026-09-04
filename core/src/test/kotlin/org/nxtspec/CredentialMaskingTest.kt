package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers F-038. A configured URL must not print its password.
 */
class CredentialMaskingTest {

    @Test
    fun `maskUrl masks the user information and keeps the host`() {
        assertEquals("amqp://***@rabbit:5672/vh", CredentialMasking.maskUrl("amqp://guest:guest@rabbit:5672/vh"))
    }

    @Test
    fun `maskUrl keeps a url without user information`() {
        assertEquals("amqp://rabbit:5672/vh", CredentialMasking.maskUrl("amqp://rabbit:5672/vh"))
    }

    @Test
    fun `maskUrl masks a password that holds an unencoded slash`() {
        val result = CredentialMasking.maskUrl("amqp://user:p/ssw0rd@broker:5672/vhost")

        assertFalse(result.contains("p/ssw0rd"), result)
        assertTrue(result.contains("broker:5672"), result)
    }

    @Test
    fun `maskUrl masks a password that holds an at sign`() {
        assertEquals("https://***@host/path", CredentialMasking.maskUrl("https://user:p@ss@host/path"))
    }

    @Test
    fun `maskUrl masks a password that holds both an at sign and a slash`() {
        assertEquals(
            "amqp://***@broker:5672/vhost",
            CredentialMasking.maskUrl("amqp://user:pa@ss/word@broker:5672/vhost")
        )
    }

    @Test
    fun `maskUrl keeps the host of a url whose path holds an at sign`() {
        val url = "https://api.example.com/users/me@example.com"

        assertEquals(url, CredentialMasking.maskUrl(url))
    }

    @Test
    fun `maskUrl masks a password query parameter`() {
        val result = CredentialMasking.maskUrl("jdbc:postgresql://db:5432/app?user=app&password=hunter2&ssl=true")

        assertFalse(result.contains("hunter2"), result)
        assertTrue(result.contains("db:5432"), result)
        assertTrue(result.contains("ssl=true"), result)
    }

    @Test
    fun `maskHeaders masks a credential header only`() {
        val masked = CredentialMasking.maskHeaders(
            mapOf("Authorization" to "Bearer abc", "X-Api-Key" to "k1", "Accept" to "application/json")
        )

        assertEquals(Secret.MASK, masked["Authorization"])
        assertEquals(Secret.MASK, masked["X-Api-Key"])
        assertEquals("application/json", masked["Accept"])
    }
}
