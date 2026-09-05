package org.nxtspec

/**
 * Prepares an error text for the `last_error` column. See F-016.
 *
 * The text can carry a destination header, so the sanitiser redacts the value of every known
 * secret-bearing header, and of any key that names a token, a secret or a password. It then
 * truncates the result, so one row cannot hold an unbounded string.
 */
/**
 * A throwable that carries extra text worth reporting, which can hold a secret.
 *
 * `ErrorSanitizer` lives in `core`, so every module can redact a log line. It therefore cannot
 * name a type of another module. A throwable that wants its detail reported implements this.
 */
interface SanitizableDetail {
    /** The extra text, for example the response body of a failed HTTP publish. */
    val detail: String?
}

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
        "password",
        // Eighth review gate B1 and B2. `PGPASSWORD` was redacted and `PGPASSWD` was not, and a
        // destination error body that names `credentials` passed through untouched. The key
        // pattern matches a prefix, so one entry covers `db_pwd`, `PGPASSWD` and `credentials`.
        "pwd",
        "passwd",
        "credential",
        "credentials",
        "passphrase",
        "private_key"
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
    // A quoted value ends at its closing quote, so a comma inside the quotes cannot cut the
    // redaction short.
    //
    // An unquoted value depends on the separator, because the two separators belong to two
    // notations.
    //
    // A ':' separator is the log, the YAML and the header notation. A value there can hold a
    // space, so the value runs to a comma, a semicolon, a closing brace, a closing bracket, an
    // ampersand, a quote or the end of the line. "password: my secret pass" therefore loses its
    // whole tail.
    //
    // An '=' separator is the environment and the query notation. A value there ends at the first
    // whitespace, so "PGPASSWORD=hunter2 psql failed" keeps the words that follow.
    //
    // An unquoted value can start with an authentication scheme name, so
    // "Authorization: Bearer <token>" redacts the token as well.
    private const val QUOTED_VALUE = "\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?"

    private val schemePrefix: String = "(?:(?:" + AUTH_SCHEMES.joinToString("|") + ")\\s+)?"

    private val secretPattern: Regex = Regex(
        "(?i)([A-Za-z0-9_]*(?:" + SECRET_KEYS.joinToString("|") { keyAlternative(it) } +
            "))\\b\"?\\s*(?:" +
            ":+\\s*(?:" + QUOTED_VALUE + "|" + schemePrefix + "[^,;}\\]&\\n\"]*)" +
            "|" +
            "=+\\s*(?:" + QUOTED_VALUE + "|" + schemePrefix + "[^\\s,;}\\]&\\n\"]*)" +
            ")"
    )

    // Matches a bare authentication scheme and the token that follows it.
    private val schemePattern: Regex = Regex(
        "(?i)\\b(" + AUTH_SCHEMES.joinToString("|") + ")\\s+[A-Za-z0-9._~+/\\-]+=*"
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
        // and the port stay, because an operator needs them. CredentialMasking owns the one
        // URL pattern, so the two files cannot drift apart.
        redacted = CredentialMasking.maskUrl(redacted)

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
        val body = (error as? SanitizableDetail)?.detail
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
