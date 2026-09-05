package org.nxtspec

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
        // `HttpPublishException` lives in `outbox-service`, which `core` cannot see. The contract
        // under test is `SanitizableDetail`, so the test uses its own implementation of it.
        val error = object : Exception("HTTP 500: Internal Server Error"), SanitizableDetail {
            override val detail: String = """{"error":"boom","token":"leaked-value-123"}"""
        }

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

    // --- The whitespace leak of the third review gate ---

    @Test
    fun `sanitize masks a url password that holds two spaces`() {
        val result = ErrorSanitizer.sanitize("Illegal character at index 18: amqp://user:aa  bb@rabbit:5672/vh")!!

        assertFalse(result.contains("aa  bb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `sanitize masks a url password that holds a tab`() {
        val result = ErrorSanitizer.sanitize("connect failed amqp://user:aa\tbb@rabbit:5672/vh")!!

        assertFalse(result.contains("aa\tbb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `sanitize masks a url password that holds a newline`() {
        val result = ErrorSanitizer.sanitize("connect failed amqp://user:aa\nbb@rabbit:5672/vh")!!

        assertFalse(result.contains("aa\nbb"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `sanitize masks a url password that holds a space and an at sign`() {
        val result = ErrorSanitizer.sanitize("bad uri amqp://user:a b@c d@rabbit:5672/vh")!!

        assertEquals("bad uri amqp://***@rabbit:5672/vh", result)
    }

    @Test
    fun `sanitize keeps prose that names a url and a mail address`() {
        val text = "amqp://rabbit:5672/vh failed for nate@example.com"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    @Test
    fun `sanitize redacts the whole tail of an unquoted colon value`() {
        val result = ErrorSanitizer.sanitize("password: my secret pass")!!

        assertFalse(result.contains("secret pass"), result)
        assertFalse(result.contains("my"), result)
    }

    @Test
    fun `sanitize keeps the tail of an unquoted equals value`() {
        val result = ErrorSanitizer.sanitize("PGPASSWORD=hunter2 psql failed")!!

        assertFalse(result.contains("hunter2"), result)
        assertTrue(result.contains("psql failed"), result)
    }

    // --- Hostile inputs of the third gate ---

    @Test
    fun `sanitize masks a url password that holds a space, a slash and an at sign`() {
        val result = ErrorSanitizer.sanitize("bad uri amqp://user:a b/c@d e@rabbit:5672/vh")!!

        assertEquals("bad uri amqp://***@rabbit:5672/vh", result)
    }

    @Test
    fun `sanitize masks a whitespace password of a nested cause`() {
        val cause = IllegalArgumentException("bad uri amqp://guest:hun ter2@rabbit:5672/vh")
        val error = RuntimeException("publish failed", cause)

        val result = ErrorSanitizer.sanitize(error)!!

        assertFalse(result.contains("hun ter2"), result)
        assertTrue(result.contains("rabbit:5672"), result)
    }

    @Test
    fun `sanitize masks a whitespace password before a prose mail address`() {
        val result = ErrorSanitizer.sanitize("amqp://user:a b@rabbit:5672/vh failed for nate@example.com")!!

        assertEquals("amqp://***@rabbit:5672/vh failed for nate@example.com", result)
    }

    @Test
    fun `sanitize masks a whitespace password and still truncates`() {
        val text = "amqp://user:aa  bb@rabbit:5672/vh " + "x".repeat(5000)

        val result = ErrorSanitizer.sanitize(text)!!

        assertFalse(result.contains("aa  bb"), "The password must not print")
        assertEquals(ErrorSanitizer.MAX_LENGTH, result.length)
    }

    // --- The reversed trade of the fourth review gate ---

    @Test
    fun `sanitize masks a whitespace password of a url with no port and no path`() {
        val result = ErrorSanitizer.sanitize("connect failed amqp://user:pass word@rabbit")!!

        assertEquals("connect failed amqp://***@rabbit", result)
    }

    // The mask of the mail address is DELIBERATE. The URL holds no path, so the pattern cannot
    // tell the prose from a real authority. A leaked password outranks a mangled word.
    @Test
    fun `sanitize deliberately masks prose after a port only url`() {
        val result = ErrorSanitizer.sanitize("amqp://rabbit:5672 refused for nate@example.com")!!

        assertEquals("amqp://***@example.com", result)
    }

    @Test
    fun `sanitize keeps a message with no scheme`() {
        val text = "delivery failed for nate@example.com after 3 attempts"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    /**
     * Eighth review gate, B1 and B2. `PGPASSWORD` was redacted and `PGPASSWD` was not, and a
     * destination error body that names `credentials` passed through untouched. Both reach the
     * log and the `outbox.last_error` column.
     */
    @Test
    fun `sanitize redacts every common abbreviation of a secret key`() {
        for (text in listOf(
            "Connection refused for db_pwd=hunter2",
            "PGPASSWD=hunter2 failed",
            """{"credentials":"sk_live_51H8xQz"}""",
            "passphrase=hunter2",
            "private_key=hunter2"
        )) {
            val result = ErrorSanitizer.sanitize(text)!!
            assertFalse(result.contains("hunter2"), "the secret printed: $result")
            assertFalse(result.contains("sk_live_51H8xQz"), "the secret printed: $result")
        }
    }

    /**
     * Eighth review gate, B3. A URI reaches an error text BECAUSE it is malformed, and a missing
     * slash is the commonest malformation. `ConfigValidator` does not validate a RabbitMQ URL, so
     * one typo put the broker password in the log and in the database on every publish attempt.
     */
    @Test
    fun `sanitize masks the user information of a malformed uri with one slash`() {
        val result = ErrorSanitizer.sanitize(
            "Expected authority at index 6: amqp:/broker:hunter2@rabbit:5672"
        )!!

        assertFalse(result.contains("hunter2"), "the broker password printed: $result")
        assertTrue(result.contains("rabbit:5672"), "the host and the port must survive: $result")
    }

    /** A colon with no slash is ordinary prose, and it must not be masked. */
    @Test
    fun `sanitize keeps prose that holds a colon and a mail address`() {
        val text = "note:see me@example.com for details"

        assertEquals(text, ErrorSanitizer.sanitize(text))
    }

    /**
     * Over-redaction is a defect too. An error message an operator cannot act on has a real cost.
     *
     * `Token` and `Bearer` are scheme names AND English words, and the key list matches a prefix,
     * so a sentence must not lose a word to either rule.
     */
    @Test
    fun `sanitize leaves an ordinary sentence alone`() {
        for (text in listOf(
            "the token bucket is empty",
            "the bearer of this message is unknown",
            "Digest realm-based auth failed",
            "column password_reset_at does not exist",
            "relation \"user_credentials_view\" does not exist",
            "tokenizer failed at line 4",
            "connect failed to [2001:db8::1]:5672",
            "Connection to db:5432 refused"
        )) {
            assertEquals(text, ErrorSanitizer.sanitize(text), "the sanitiser mangled: $text")
        }
    }

    /** A value that really looks like a credential is still redacted after that narrowing. */
    @Test
    fun `sanitize still redacts a credential that follows a scheme`() {
        for (text in listOf(
            "Basic dXNlcjpwYXNzd29yZA==",
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "Token 9f8e7d6c5b4a3210"
        )) {
            val result = ErrorSanitizer.sanitize(text)!!
            assertTrue(result.contains(REDACTED_MARK), "the credential survived: $result")
        }
    }

    /**
     * The boundary of the credential shape rule, stated so it cannot drift by accident.
     *
     * Sixteen letters keeps "Digest authentication failed" readable. The cost is that fifteen
     * lower-case letters with no digit escape. No token format in use produces that shape.
     */
    @Test
    fun `the scheme token boundary is sixteen characters for a value with no digit`() {
        assertEquals("Bearer abcdefghijklmno", ErrorSanitizer.sanitize("Bearer abcdefghijklmno"))
        assertTrue(ErrorSanitizer.sanitize("Bearer abcdefghijklmnop")!!.contains(REDACTED_MARK))
        assertEquals("Digest authentication failed", ErrorSanitizer.sanitize("Digest authentication failed"))
    }

    /** A long password and a password that holds a comma must both still be masked. */
    @Test
    fun `the bounded password run still masks a long password and one with a comma`() {
        for (text in listOf(
            "amqp://user:${"A".repeat(70)}@rabbit:5672/vh",
            "amqp://user:pa,ss@rabbit:5672/vh"
        )) {
            val result = ErrorSanitizer.sanitize(text)!!
            assertEquals("amqp://***@rabbit:5672/vh", result)
        }
    }

    private companion object {
        const val REDACTED_MARK = "[REDACTED]"
    }
}
