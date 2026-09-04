package org.nxtspec.app

import org.nxtspec.AdminConfig
import org.nxtspec.InboxAuthConfig
import org.nxtspec.Secret
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Covers F-034. The admin endpoint must not start without authentication.
 */
class AdminGuardTest {

    @Test
    fun `accepts the disabled admin endpoint`() {
        requireAdminAuth(AdminConfig())
    }

    @Test
    fun `accepts an enabled admin endpoint with authentication`() {
        requireAdminAuth(
            AdminConfig(enabled = true, auth = InboxAuthConfig.Bearer(token = Secret("token")))
        )
    }

    @Test
    fun `accepts an enabled admin endpoint with the insecure flag`() {
        requireAdminAuth(AdminConfig(enabled = true, insecure = true))
    }

    @Test
    fun `refuses an enabled admin endpoint with no authentication`() {
        val exception = assertFailsWith<InsecureAdminException> {
            requireAdminAuth(AdminConfig(enabled = true))
        }

        assertContains(exception.message!!, "admin.auth")
        assertContains(exception.message!!, "admin.insecure")
    }
}
