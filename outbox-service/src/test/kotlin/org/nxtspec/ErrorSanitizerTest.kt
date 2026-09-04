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

    @Test
    fun `sanitize masks the user information of an amqp uri`() {
        val text = "Illegal character in authority at index 18: amqp://guest:Sup3r S3cret@rabbit:5672/vh"

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("Sup3r S3cret"))
        assertTrue(result.contains("rabbit:5672"))
    }

    @Test
    fun `sanitize keeps the host and the port of a url without user information`() {
        val result = ErrorSanitizer.sanitize("Connection refused to amqp://rabbit:5672/vh")!!

        assertTrue(result.contains("rabbit:5672"))
        assertTrue(result.contains("/vh"))
    }

    @Test
    fun `sanitize redacts a key whatever its separator character`() {
        listOf("api_key", "api.key", "apiKey", "API_KEY", "access-token", "client.secret").forEach { key ->
            val result = ErrorSanitizer.sanitize("failure $key=SUPERSECRETVALUE end")!!
            assertFalse(result.contains("SUPERSECRETVALUE"), "The value of '$key' must be redacted")
            assertTrue(result.contains("failure"), "The text before '$key' must survive")
        }
    }

    @Test
    fun `sanitize redacts a bare basic scheme token`() {
        val result = ErrorSanitizer.sanitize("rejected: Basic dXNlcjpwYXNzd29yZA==")!!

        assertFalse(result.contains("dXNlcjpwYXNzd29yZA=="))
        assertTrue(result.contains("rejected"))
    }

    @Test
    fun `sanitize redacts a bare bearer scheme token`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcDEF123"

        val result = ErrorSanitizer.sanitize("rejected: Bearer $jwt")!!

        assertFalse(result.contains(jwt))
    }

    @Test
    fun `sanitize redacts every other bare scheme token`() {
        listOf("Digest", "Negotiate", "Token").forEach { scheme ->
            val result = ErrorSanitizer.sanitize("denied $scheme leaked-value-123")!!
            assertFalse(result.contains("leaked-value-123"), "The token after '$scheme' must be redacted")
        }
    }

    @Test
    fun `sanitize does not mangle an ordinary message`() {
        val text = "Connection reset by peer after 3 attempts on exchange orders"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    @Test
    fun `sanitize masks the user information of a nested cause message`() {
        val cause = IllegalArgumentException("bad uri amqp://guest:hunter2@rabbit:5672/vh")
        val error = RuntimeException("publish failed", cause)

        val result = ErrorSanitizer.sanitize(error)!!

        assertFalse(result.contains("hunter2"))
        assertTrue(result.contains("publish failed"))
        assertTrue(result.contains("IllegalArgumentException"))
        assertTrue(result.contains("bad uri"))
    }
}
