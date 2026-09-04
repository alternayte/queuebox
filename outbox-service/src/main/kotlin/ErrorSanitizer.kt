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

    // The schemes that carry the credential directly after the scheme name, with no "key=value"
    // shape. See F-016.
    private val AUTH_SCHEMES = listOf("Basic", "Bearer", "Digest", "Negotiate", "Token")

    // A key writes its word separator as '-', '_' or '.', so every form of one key matches one
    // pattern. The pattern also accepts a key with no separator at all.
    private fun keyAlternative(key: String): String =
        key.split('-', '_', '.').joinToString("[-_.]?") { Regex.escape(it) }

    // Matches "<key><separator><value>". The key can carry a closing quote, as it does in JSON.
    //
    // The key accepts a prefix, so an environment variable such as "PGPASSWORD" matches. The key
    // still ends on a word boundary, so "passwordless" does not match.
    //
    // The separator is ':' or '='. A quoted value ends at its closing quote, so a comma inside
    // the quotes cannot cut the redaction short. An unquoted value ends at whitespace or at a
    // separator character. An unquoted value can start with an authentication scheme name, so
    // "Authorization: Bearer <token>" redacts the token as well.
    private val secretPattern: Regex = Regex(
        "(?i)([A-Za-z0-9_]*(?:" + SECRET_KEYS.joinToString("|") { keyAlternative(it) } +
            "))\\b\"?\\s*[:=]+\\s*" +
            "(?:\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?|" +
            "(?:(?:" + AUTH_SCHEMES.joinToString("|") + ")\\s+)?[^\\s,;}\\]&\\n\"]*)"
    )

    // Matches a bare authentication scheme and the token that follows it.
    private val schemePattern: Regex = Regex(
        "(?i)\\b(" + AUTH_SCHEMES.joinToString("|") + ")\\s+[A-Za-z0-9._~+/\\-]+=*"
    )

    // Matches the user information of a URL. `CredentialMasking.maskUrl` rejects a password that
    // holds a space, and a broker reports such a URL in its error text. This pattern accepts one
    // space.
    //
    // The pattern ends at the LAST at sign of the authority, so a password with an at sign
    // cannot leak its tail. A slash is accepted only after the ':' of the password, so a path
    // that holds an at sign keeps its host. The host that follows carries no at sign, so the
    // pattern cannot run past one authority into a later word.
    //
    // The alternation is deliberate. An optional group makes the Java engine keep the FIRST '@',
    // and a password with an '@' then leaks its tail.
    private const val PASSWORD_RUN = "(?:(?!://)\\S)*"

    private val userInfoPattern: Regex = Regex(
        "([a-zA-Z][a-zA-Z0-9+.\\-]*://)" +
            "(?:[^\\s/@]*:(?:$PASSWORD_RUN $PASSWORD_RUN|$PASSWORD_RUN)|[^\\s/@]*)" +
            "@(?=[^/?#\\s@]*(?:[/?#\\s]|$))"
    )

    /**
     * Redacts the secret values in the text and truncates the result.
     */
    fun sanitize(text: String?): String? {
        if (text == null) return null

        var redacted = secretPattern.replace(text) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }

        // F-038: the password of an AMQP URI or a JDBC URL is not a "key=value" pair. The host
        // and the port stay, because an operator needs them.
        redacted = CredentialMasking.maskUrl(redacted)
        redacted = userInfoPattern.replace(redacted) { match -> match.groupValues[1] + "***@" }

        redacted = schemePattern.replace(redacted) { match ->
            "${match.groupValues[1]} $REDACTED"
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
        val head = describeChain(error)
        val body = (error as? org.nxtspec.http.HttpPublishException)?.body
        val text = if (body.isNullOrBlank()) head else "$head | body: $body"
        return sanitize(text)
    }

    /**
     * Names the throwable and every cause below it.
     *
     * A driver puts the connection URL in the message of the cause, not of the wrapper. The
     * chain therefore reaches the redaction, and no cause message escapes it.
     */
    private fun describeChain(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            parts += "${current::class.simpleName}: ${current.message ?: "no message"}"
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return parts.joinToString(" | caused by ")
    }

    private const val MAX_CAUSE_DEPTH = 5

    private const val TRUNCATION_MARKER = "...[truncated]"
}
