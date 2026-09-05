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
        var masked = USER_INFO_SPACE_WITH_SLASH.replace(url) { match -> match.groupValues[1] + "***@" }
        masked = USER_INFO_SPACE_NO_SLASH.replace(masked) { match -> match.groupValues[1] + "***@" }
        masked = USER_INFO_NO_SPACE.replace(masked) { match -> match.groupValues[1] + "***@" }
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

    // Matches "<scheme>://<user information>@" and keeps the scheme.
    //
    // The user information has three shapes. The masking applies them in the order below.
    //
    // Two shapes hold whitespace in the password. A password with a space, a tab or a newline is
    // exactly the reason a URI reaches an error text, because whitespace is illegal in an
    // authority. The two shapes trade a slash against the strictness of the host.
    //
    // SPACE_WITH_SLASH accepts a slash in the password, so it demands a plausible host after the
    // at sign: a host with a port, or a host that a path, a query or a fragment follows.
    //
    // SPACE_NO_SLASH stops at a slash, so it accepts any host, even a bare host with no port and
    // no path. "amqp://user:pass word@rabbit" is a normal AMQP configuration, and the mask must
    // cover it. The cost is prose of the form "amqp://rabbit:5672 refused for nate@example.com",
    // which becomes "amqp://***@example.com". That mask is DELIBERATE. A leaked broker password is
    // a security defect. A mangled word is an inconvenience. The slash stop still protects the
    // common prose "amqp://rabbit:5672/vh failed for nate@example.com", which stays whole.
    //
    // Neither whitespace shape can cross a second "://", so a message that names two URLs keeps
    // both hosts.
    //
    // The third shape holds no whitespace. It accepts a slash and an at sign after the ':' of the
    // password, so an unencoded slash cannot break the mask.
    //
    // Every shape ends at the LAST at sign of the authority. The alternation is deliberate. An
    // optional group makes the Java engine keep the FIRST at sign, and a password with an at sign
    // then leaks its tail.
    // Eighth review gate B3. The pattern demanded `://`. A URI reaches an error text BECAUSE it
    // is malformed, and a missing slash is the commonest malformation, so `amqp:/host:pass@broker`
    // escaped the mask. One or two slashes are accepted now. At least one slash is still
    // mandatory, so ordinary prose of the form `note:see me@example.com` does not match.
    private const val SCHEME = "([a-zA-Z][a-zA-Z0-9+.-]*:/{1,2})"

    private const val USER = "[^\\s/?#@]*:"

    private const val HOST_AFTER = "(?=[^/?#\\s@]*(?:[/?#\\s]|$))"

    private const val PLAUSIBLE_HOST_AFTER = "(?=[^/?#\\s@]*:[0-9]+(?:[/?#\\s]|$)|[^/?#\\s@]*[/?#])"

    // Ninth review gate B2. The runs were unbounded, so
    // "https://api.example.com:8443 failed, contact ops@example.com" collapsed to
    // "https://***@example.com". The whole failure reason was gone, which is the opposite of the
    // promise that the host and the port survive.
    //
    // Tenth review gate B1 and B2. The first repair excluded a comma outright and stopped at 64
    // characters, and that reopened the leak it was meant to bound: a password that holds
    // whitespace AND a comma, or whitespace and more than 64 characters, matched no shape at all
    // and printed in clear. The discriminator is a comma FOLLOWED BY WHITESPACE, which is prose
    // and never a URI user information, and the bound is 200 characters, above any real
    // passphrase.
    private const val SLASH_RUN = "(?:(?!://)(?!,\\s)[^?#]){0,200}"

    private const val NO_SLASH_RUN = "(?:(?!,\\s)[^/?#]){0,200}"

    private val USER_INFO_SPACE_WITH_SLASH = Regex(
        SCHEME + USER + SLASH_RUN + "\\s" + SLASH_RUN + "@" + PLAUSIBLE_HOST_AFTER
    )

    private val USER_INFO_SPACE_NO_SLASH = Regex(
        SCHEME + USER + NO_SLASH_RUN + "\\s" + NO_SLASH_RUN + "@" + HOST_AFTER
    )

    private val USER_INFO_NO_SPACE = Regex(
        SCHEME + "(?:[^\\s/@]*:(?:(?!://)\\S)*|[^\\s/@]*)@" + HOST_AFTER
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
