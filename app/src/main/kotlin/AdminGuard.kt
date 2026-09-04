package org.nxtspec.app

import org.nxtspec.AdminConfig

/**
 * Thrown at startup when the admin routes are enabled with no authentication.
 */
class InsecureAdminException(message: String) : RuntimeException(message)

/**
 * F-034: refuses to start when the admin routes are enabled and no authentication is configured.
 *
 * The admin endpoint evaluates a caller-supplied JSONata expression. That is remote compute on
 * the message-processing host, so an open endpoint is a denial of service risk. The operator can
 * accept that risk with 'admin.insecure', for a local test only.
 *
 * @throws InsecureAdminException when the admin routes are enabled, 'admin.auth' is absent, and
 *   'admin.insecure' is false
 */
fun requireAdminAuth(admin: AdminConfig) {
    if (!admin.enabled) return
    if (admin.auth != null) return
    if (admin.insecure) return

    throw InsecureAdminException(
        "The admin routes are enabled with no authentication. Set 'admin.auth' to a bearer " +
            "token, an API key, or an HMAC signature. Set 'admin.insecure' to true only for a " +
            "local test, or set 'admin.enabled' to false."
    )
}
