package org.nxtspec.auth

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.nxtspec.DestinationAuthConfig
import org.nxtspec.Secret
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DestinationAuthResolverTest {

    @Test
    fun `resolveAuthHeaders returns empty map for null config`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val resolver = DestinationAuthResolver(tokenManager)

        val headers = resolver.resolveAuthHeaders(null)

        assertTrue(headers.isEmpty())
    }

    @Test
    fun `OAuth2 returns Bearer token header`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        coEvery { tokenManager.getToken(config) } returns "test-access-token"

        val resolver = DestinationAuthResolver(tokenManager)
        val headers = resolver.resolveAuthHeaders(config)

        assertEquals(1, headers.size)
        assertEquals("Bearer test-access-token", headers["Authorization"])
    }

    @Test
    fun `Basic auth returns correctly encoded header`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val resolver = DestinationAuthResolver(tokenManager)

        val config = DestinationAuthConfig.Basic(
            username = "user",
            password = Secret("pass")
        )

        val headers = resolver.resolveAuthHeaders(config)

        assertEquals(1, headers.size)
        val expectedEncoded = Base64.getEncoder().encodeToString("user:pass".toByteArray(Charsets.UTF_8))
        assertEquals("Basic $expectedEncoded", headers["Authorization"])
    }

    @Test
    fun `Basic auth handles special characters`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val resolver = DestinationAuthResolver(tokenManager)

        val config = DestinationAuthConfig.Basic(
            username = "user@example.com",
            password = Secret("p@ss:word!")
        )

        val headers = resolver.resolveAuthHeaders(config)

        assertEquals(1, headers.size)
        val expectedEncoded = Base64.getEncoder().encodeToString(
            "user@example.com:p@ss:word!".toByteArray(Charsets.UTF_8)
        )
        assertEquals("Basic $expectedEncoded", headers["Authorization"])
    }

    @Test
    fun `Header auth returns custom header`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val resolver = DestinationAuthResolver(tokenManager)

        val config = DestinationAuthConfig.Header(
            headerName = "X-API-Key",
            headerValue = Secret("my-api-key")
        )

        val headers = resolver.resolveAuthHeaders(config)

        assertEquals(1, headers.size)
        assertEquals("my-api-key", headers["X-API-Key"])
    }

    @Test
    fun `Header auth with default Authorization header`() = runTest {
        val tokenManager = mockk<OAuth2TokenManager>()
        val resolver = DestinationAuthResolver(tokenManager)

        val config = DestinationAuthConfig.Header(
            headerName = "Authorization",
            headerValue = Secret("Bearer static-token")
        )

        val headers = resolver.resolveAuthHeaders(config)

        assertEquals(1, headers.size)
        assertEquals("Bearer static-token", headers["Authorization"])
    }
}
