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

    // --- The four leaks of the second review gate ---

    @Test
    fun `sanitize masks a url password that holds an unencoded slash`() {
        val result = ErrorSanitizer.sanitize("connect failed amqp://user:p/ssw0rd@broker:5672/vhost")!!

        assertFalse(result.contains("p/ssw0rd"), "The password must not print: $result")
        assertTrue(result.contains("broker:5672"), "The host and the port must survive: $result")
    }

    @Test
    fun `sanitize redacts a quoted value that holds a comma`() {
        val result = ErrorSanitizer.sanitize("""password="pa,ss word!" other=1""")!!

        assertFalse(result.contains("pa,ss"), "The password must not print: $result")
        assertFalse(result.contains("ss word!"), "The password tail must not print: $result")
        assertTrue(result.contains("other=1"), "The other pair must survive: $result")
    }

    @Test
    fun `sanitize masks a url password that holds an at sign`() {
        val result = ErrorSanitizer.sanitize("https://user:p@ss@host/path")!!

        assertEquals("https://***@host/path", result, "The pattern must use the last at sign")
    }

    @Test
    fun `sanitize redacts a key with a prefix such as PGPASSWORD`() {
        val result = ErrorSanitizer.sanitize("PGPASSWORD=hunter2 psql failed")!!

        assertFalse(result.contains("hunter2"), "The password must not print: $result")
        assertTrue(result.contains("psql failed"), "The rest of the message must survive: $result")
    }

    // --- The behaviours the review gate confirmed clean ---

    @Test
    fun `sanitize drops a cause deeper than five levels`() {
        var error: Throwable = IllegalStateException("bottom token=leaked-value-123")
        repeat(6) { level -> error = RuntimeException("level $level", error) }

        val result = ErrorSanitizer.sanitize(error)!!

        assertFalse(result.contains("leaked-value-123"), "The deep cause must not print: $result")
    }

    @Test
    fun `sanitize never reads a suppressed exception`() {
        val error = RuntimeException("publish failed")
        error.addSuppressed(IllegalStateException("suppressed token=leaked-value-123"))

        val result = ErrorSanitizer.sanitize(error)!!

        assertFalse(result.contains("leaked-value-123"), "The suppressed error must not print: $result")
    }

    @Test
    fun `sanitize truncates after it redacts`() {
        val text = "token=leaked-value-123 " + "x".repeat(5000)

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("leaked-value-123"))
        assertTrue(result.startsWith("token=[REDACTED]"), "The redaction must stay whole: $result")
        assertEquals(ErrorSanitizer.MAX_LENGTH, result.length)
    }

    @Test
    fun `sanitize redacts a header map toString`() {
        val headers = mapOf(
            "Authorization" to "Bearer leaked-value-123",
            "X-Api-Key" to "leaked-key-456"
        )

        val result = ErrorSanitizer.sanitize("headers $headers rejected")!!

        assertFalse(result.contains("leaked-value-123"), result)
        assertFalse(result.contains("leaked-key-456"), result)
    }

    @Test
    fun `sanitize redacts a jdbc password query parameter`() {
        val text = "jdbc:postgresql://db:5432/app?user=app&password=hunter2&ssl=true"

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("hunter2"), result)
        assertTrue(result.contains("db:5432"), result)
    }

    // --- Hostile inputs of my own invention ---

    @Test
    fun `sanitize redacts a quoted value that holds an escaped quote`() {
        val text = """{"password":"he said \"hi\", ok"}"""

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("he said"), result)
        assertFalse(result.contains("hi"), result)
        assertFalse(result.contains("ok"), result)
    }

    @Test
    fun `sanitize masks both urls of one message`() {
        val text = "amqp://a:b@h1:5672/v failed, amqp://c:d@h2:5672/v failed"

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("a:b"), result)
        assertFalse(result.contains("c:d"), result)
        assertTrue(result.contains("h1:5672"), result)
        assertTrue(result.contains("h2:5672"), result)
    }

    @Test
    fun `sanitize keeps the host of a url whose path holds an at sign`() {
        val result = ErrorSanitizer.sanitize("GET https://api.example.com/users/me@example.com failed")!!

        assertTrue(result.contains("api.example.com"), "The host must survive: $result")
        assertEquals("GET https://api.example.com/users/me@example.com failed", result)
    }

    @Test
    fun `sanitize does not redact a sentence that names a password`() {
        val text = "The password was wrong and the broker closed the channel"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    @Test
    fun `sanitize does not redact a passwordless option`() {
        val text = "passwordless=true is not supported"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    @Test
    fun `sanitize masks a url password that holds both an at sign and a slash`() {
        val result = ErrorSanitizer.sanitize("amqp://user:pa@ss/word@broker:5672/vhost")!!

        assertEquals("amqp://***@broker:5672/vhost", result)
    }
}
