package org.nxtspec

/**
 * Hides the credentials that a configured value can still carry. See F-038.
 *
 * Some fields cannot become a Secret, because their whole value is not a credential. A JDBC
 * URL, an AMQP URI, and a static header map all carry one part that must never print.
 */
object CredentialMasking {

    /**
     * Replaces the user information and the password query parameter of a URL with the mask.
     *
     * The user information of an AMQP URI becomes the mask, so the password does not print.
     * A password query parameter of a JDBC URL becomes the mask as well.
     *
     * Kotlin nests a block comment, so this text carries no URL example. A slash pair followed
     * by a star opens a comment inside a comment.
     */
    fun maskUrl(url: String): String {
        var masked = USER_INFO.replace(url) { match -> match.groupValues[1] + "***@" }
        masked = PASSWORD_PARAMETER.replace(masked) { match -> match.groupValues[1] + "=***" }
        return masked
    }

    /**
     * Replaces the value of every header whose name names a credential.
     */
    fun maskHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (isSecretHeader(name)) Secret.MASK else value
    }

    private fun isSecretHeader(name: String): Boolean =
        SECRET_HEADER_NAMES.any { name.equals(it, ignoreCase = true) } ||
            SECRET_HEADER_PARTS.any { name.contains(it, ignoreCase = true) }

    // Matches "<scheme>://<user information>@" and keeps the scheme. The user information can
    // hold a slash after the ':' of the password, and an at sign, so the pattern ends at the LAST
    // at sign of the authority. The host that follows carries no at sign, so the pattern stays
    // inside one authority. A path that holds an at sign keeps its host.
    //
    // The alternation is deliberate. An optional group makes the Java engine keep the FIRST at
    // sign, and a password with an at sign then leaks its tail.
    private val USER_INFO = Regex(
        "([a-zA-Z][a-zA-Z0-9+.-]*://)" +
            "(?:[^\\s/@]*:(?:(?!://)\\S)*|[^\\s/@]*)" +
            "@(?=[^/?#\\s@]*(?:[/?#\\s]|$))"
    )

    // Matches a password query parameter, whatever its separator.
    private val PASSWORD_PARAMETER = Regex("(?i)([?&;](?:password|pwd|secret|token))=[^&;\\s]*")

    private val SECRET_HEADER_NAMES = listOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie"
    )

    private val SECRET_HEADER_PARTS = listOf("api-key", "apikey", "token", "secret", "password")
}
