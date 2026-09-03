package org.nxtspec

/**
 * Prepares an error text for the `last_error` column. See F-016.
 *
 * The text can carry a destination header, so the sanitiser redacts the value of every known
 * secret-bearing header, and of any key that names a token, a secret or a password. It then
 * truncates the result, so one row cannot hold an unbounded string.
 */
object ErrorSanitizer {

    const val MAX_LENGTH: Int = 2000

    private val SECRET_KEYS = listOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "apikey",
        "cookie",
        "set-cookie",
        "x-auth-token",
        "token",
        "access_token",
        "refresh_token",
        "client_secret",
        "secret",
        "password"
    )

    private const val REDACTED = "[REDACTED]"

    // Matches "<key><separator><value>". The key can carry a closing quote, as it does in JSON.
    // The separator is ':' or '='. The value can be quoted. The value ends at a comma, a
    // semicolon, a closing brace, a quote or a line end.
    private val secretPattern: Regex = Regex(
        "(?i)\\b(" + SECRET_KEYS.joinToString("|") { Regex.escape(it) } +
            ")\\b\"?\\s*[:=]+\\s*\"?[^,;}\\n\"]*\"?"
    )

    /**
     * Redacts the secret values in the text and truncates the result.
     */
    fun sanitize(text: String?): String? {
        if (text == null) return null

        val redacted = secretPattern.replace(text) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }

        return if (redacted.length <= MAX_LENGTH) {
            redacted
        } else {
            redacted.take(MAX_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        }
    }

    /**
     * Builds the error text of a throwable and sanitises it.
     *
     * A failed HTTP publish carries the response body, which helps an operator. The body can
     * carry a secret, so it passes through the same redaction.
     */
    fun sanitize(error: Throwable): String? {
        val head = "${error::class.simpleName}: ${error.message ?: "no message"}"
        val body = (error as? org.nxtspec.http.HttpPublishException)?.body
        val text = if (body.isNullOrBlank()) head else "$head | body: $body"
        return sanitize(text)
    }

    private const val TRUNCATION_MARKER = "...[truncated]"
}
