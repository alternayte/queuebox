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

    // --- The whitespace leak of the third review gate ---

    @Test
    fun `maskUrl masks a password that holds two spaces`() {
        val result = CredentialMasking.maskUrl("amqp://user:aa  bb@rabbit:5672/vh")

        assertFalse(result.contains("aa  bb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `maskUrl masks a password that holds a tab`() {
        val result = CredentialMasking.maskUrl("amqp://user:aa\tbb@rabbit:5672/vh")

        assertFalse(result.contains("aa\tbb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `maskUrl masks a password that holds a newline`() {
        val result = CredentialMasking.maskUrl("amqp://user:aa\nbb@rabbit:5672/vh")

        assertFalse(result.contains("aa\nbb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `maskUrl masks a password that holds a space and an at sign`() {
        val result = CredentialMasking.maskUrl("amqp://user:a b@c d@rabbit:5672/vh")

        assertEquals("amqp://***@rabbit:5672/vh", result)
    }

    // --- The prose that must stay readable ---

    @Test
    fun `maskUrl keeps prose that names a url and a mail address`() {
        val text = "amqp://rabbit:5672/vh failed for nate@example.com"

        assertEquals(text, CredentialMasking.maskUrl(text))
    }

    @Test
    fun `maskUrl masks both urls of one message`() {
        val result = CredentialMasking.maskUrl("amqp://a:b@h1:5672/v failed, amqp://c:d@h2:5672/v failed")

        assertEquals("amqp://***@h1:5672/v failed, amqp://***@h2:5672/v failed", result)
    }

    // --- Hostile inputs of my own invention ---

    @Test
    fun `maskUrl masks a password that holds a space, a slash and an at sign`() {
        val result = CredentialMasking.maskUrl("amqp://user:a b/c@d e@rabbit:5672/vh")

        assertEquals("amqp://***@rabbit:5672/vh", result)
    }

    @Test
    fun `maskUrl masks a password that holds a carriage return`() {
        val result = CredentialMasking.maskUrl("amqp://user:aa\rbb@rabbit:5672/vh")

        assertFalse(result.contains("aa\rbb"), result)
    }

    @Test
    fun `maskUrl masks a password of whitespace only`() {
        val result = CredentialMasking.maskUrl("amqp://user:   @rabbit:5672/vh")

        assertEquals("amqp://***@rabbit:5672/vh", result)
    }

    @Test
    fun `maskUrl masks a whitespace password before a prose mail address`() {
        val result = CredentialMasking.maskUrl("amqp://user:a b@rabbit:5672/vh failed for nate@example.com")

        assertEquals("amqp://***@rabbit:5672/vh failed for nate@example.com", result)
    }

    // --- The reversed trade of the fourth review gate ---

    @Test
    fun `maskUrl masks a whitespace password of a url with no port and no path`() {
        val result = CredentialMasking.maskUrl("amqp://user:pass word@rabbit")

        assertEquals("amqp://***@rabbit", result)
    }

    // The mask of the mail address is DELIBERATE. The URL holds no path, so the pattern cannot
    // tell the prose from a real authority. A leaked password outranks a mangled word.
    @Test
    fun `maskUrl deliberately masks prose after a port only url`() {
        val result = CredentialMasking.maskUrl("amqp://rabbit:5672 refused for nate@example.com")

        assertEquals("amqp://***@example.com", result)
    }

    @Test
    fun `maskUrl keeps a message with no scheme`() {
        val text = "The password was wrong and the broker closed the channel"

        assertEquals(text, CredentialMasking.maskUrl(text))
    }

    @Test
    fun `maskUrl keeps a mail address on its own`() {
        val text = "delivery failed for nate@example.com"

        assertEquals(text, CredentialMasking.maskUrl(text))
    }

    /**
     * Ninth review gate B2. Over-masking destroyed the whole message.
     *
     * The password runs were unbounded and allowed a comma, so an HTTP error that named a base
     * URL with a port and later named an address collapsed to nine characters. The host, the
     * port and the failure reason were all gone, which is the opposite of the promise that the
     * host and the port survive.
     */
    @Test
    fun `maskUrl keeps a message that names a url with a port and an address later`() {
        val text = "https://api.example.com:8443 failed, contact ops@example.com"

        assertEquals(text, CredentialMasking.maskUrl(text))
    }

    /** The narrowing must not reopen any leak that an earlier gate closed. */
    @Test
    fun `maskUrl still masks every user information shape`() {
        for (text in listOf(
            "amqp://user:Sup3rS3cret@broker:5672/vhost",
            "amqp://user:pass word@rabbit",
            "amqp://user:aa  bb@rabbit:5672/vh",
            "Expected authority at index 6: amqp:/broker:Sup3rS3cret@rabbit:5672",
            "jdbc:postgresql://qb:Sup3rS3cret@db:5432/qb"
        )) {
            val result = CredentialMasking.maskUrl(text)
            assertFalse(result.contains("Sup3rS3cret"), "the password printed: $result")
            assertFalse(result.contains("pass word"), "the password printed: $result")
            assertFalse(result.contains("aa  bb"), "the password printed: $result")
        }
    }
}
