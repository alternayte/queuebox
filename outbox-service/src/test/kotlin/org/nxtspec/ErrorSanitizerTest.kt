package org.nxtspec

import org.nxtspec.http.HttpPublishException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers F-016. The persisted error must name the failure and must not carry a secret.
 */
class ErrorSanitizerTest {

    @Test
    fun `sanitize returns null for a null text`() {
        assertNull(ErrorSanitizer.sanitize(null as String?))
    }

    @Test
    fun `sanitize redacts an authorization header value`() {
        val text = "HTTP 500 for request with Authorization: Bearer super-secret-token, retrying"

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("super-secret-token"))
        assertTrue(result.contains("[REDACTED]"))
        assertTrue(result.contains("HTTP 500"))
    }

    @Test
    fun `sanitize redacts every known secret bearing key`() {
        val keys = listOf(
            "Authorization", "Proxy-Authorization", "X-Api-Key", "api-key", "Cookie",
            "Set-Cookie", "X-Auth-Token", "token", "access_token", "refresh_token",
            "client_secret", "secret", "password"
        )

        keys.forEach { key ->
            val result = ErrorSanitizer.sanitize("failure $key=leaked-value-123 end")!!
            assertFalse(result.contains("leaked-value-123"), "The value of '$key' must be redacted")
        }
    }

    @Test
    fun `sanitize truncates a long text`() {
        val result = ErrorSanitizer.sanitize("x".repeat(5000))!!

        assertEquals(ErrorSanitizer.MAX_LENGTH, result.length)
        assertTrue(result.endsWith("...[truncated]"))
    }

    @Test
    fun `sanitize keeps a short text unchanged`() {
        assertEquals("HTTP 404: Not Found", ErrorSanitizer.sanitize("HTTP 404: Not Found"))
    }

    @Test
    fun `sanitize of a throwable names the type and the message`() {
        val result = ErrorSanitizer.sanitize(IllegalStateException("router exploded"))!!

        assertTrue(result.contains("IllegalStateException"))
        assertTrue(result.contains("router exploded"))
    }

    @Test
    fun `sanitize of a failed http publish keeps the body and redacts it`() {
        val error = HttpPublishException(
            message = "HTTP 500: Internal Server Error",
            statusCode = 500,
            body = """{"error":"boom","token":"leaked-value-123"}"""
        )

        val result = ErrorSanitizer.sanitize(error)!!

        assertTrue(result.contains("HTTP 500"))
        assertTrue(result.contains("boom"))
        assertFalse(result.contains("leaked-value-123"))
    }
}
