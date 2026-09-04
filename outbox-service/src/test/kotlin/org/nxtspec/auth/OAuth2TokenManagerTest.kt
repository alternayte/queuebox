package org.nxtspec.auth

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.nxtspec.DestinationAuthConfig
import org.nxtspec.Secret
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuth2TokenManagerTest {

    @Test
    fun `getToken fetches token from OAuth2 endpoint`() = runTest {
        var requestCount = 0
        val mockClient = createMockClient { request ->
            requestCount++
            assertEquals("https://auth.example.com/token", request.url.toString())
            respond(
                content = """{"access_token":"test-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        val token = tokenManager.getToken(config)

        assertEquals("test-token", token)
        assertEquals(1, requestCount)

        tokenManager.close()
    }

    @Test
    fun `getToken returns cached token on second call`() = runTest {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            requestCount.incrementAndGet()
            respond(
                content = """{"access_token":"cached-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        val token1 = tokenManager.getToken(config)
        val token2 = tokenManager.getToken(config)

        assertEquals("cached-token", token1)
        assertEquals("cached-token", token2)
        assertEquals(1, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `getToken includes scope in request when provided`() = runTest {
        var capturedFormData: String? = null
        val mockClient = createMockClient { request ->
            capturedFormData = String(request.body.toByteArray())
            respond(
                content = """{"access_token":"scoped-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token",
            scope = "read write"
        )

        tokenManager.getToken(config)

        assertTrue(capturedFormData!!.contains("scope=read+write") || capturedFormData!!.contains("scope=read%20write"))

        tokenManager.close()
    }

    @Test
    fun `getToken includes extraParams in request`() = runTest {
        var capturedFormData: String? = null
        val mockClient = createMockClient { request ->
            capturedFormData = String(request.body.toByteArray())
            respond(
                content = """{"access_token":"extra-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token",
            extraParams = mapOf("audience" to "https://api.example.com")
        )

        tokenManager.getToken(config)

        assertTrue(capturedFormData!!.contains("audience="))

        tokenManager.close()
    }

    @Test
    fun `invalidateToken removes cached token`() = runTest {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            respond(
                content =
                """{"access_token":"token-${requestCount.incrementAndGet()}",""" +
                    """"token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        val token1 = tokenManager.getToken(config)
        tokenManager.invalidateToken(config)
        val token2 = tokenManager.getToken(config)

        assertEquals("token-1", token1)
        assertEquals("token-2", token2)
        assertEquals(2, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `concurrent requests only make single HTTP call`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            requestCount.incrementAndGet()
            delay(100) // Simulate network delay
            respond(
                content = """{"access_token":"concurrent-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        // Launch multiple concurrent token requests
        val results = (1..10).map {
            async {
                tokenManager.getToken(config)
            }
        }.awaitAll()

        // All should get the same token
        assertTrue(results.all { it == "concurrent-token" })
        // Only one HTTP request should have been made
        assertEquals(1, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `default token TTL is 3600 when not specified`() = runTest {
        var tokenFetchCount = 0
        val mockClient = createMockClient {
            tokenFetchCount++
            respond(
                content = """{"access_token":"default-ttl-token","token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        // First call fetches token
        tokenManager.getToken(config)
        // Second call should use cached token (default TTL of 3600s)
        tokenManager.getToken(config)

        assertEquals(1, tokenFetchCount)

        tokenManager.close()
    }

    @Test
    fun `should refresh token when within 30s of expiry`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            val count = requestCount.incrementAndGet()
            respond(
                // Return token with very short expiry (25 seconds, within 30s buffer)
                content = """{"access_token":"token-$count","token_type":"Bearer","expires_in":25}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        // First call fetches token
        val token1 = tokenManager.getToken(config)
        // Second call should also fetch since token is within 30s buffer
        val token2 = tokenManager.getToken(config)

        assertEquals("token-1", token1)
        assertEquals("token-2", token2)
        // Should have made 2 requests since token is immediately considered expired
        assertEquals(2, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `should use different cache keys for different clientIds`() = runTest {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            val count = requestCount.incrementAndGet()
            respond(
                content = """{"access_token":"token-$count","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config1 = DestinationAuthConfig.OAuth2(
            clientId = "client-1",
            clientSecret = Secret("secret"),
            tokenUrl = "https://auth.example.com/token"
        )
        val config2 = DestinationAuthConfig.OAuth2(
            clientId = "client-2",
            clientSecret = Secret("secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        val token1 = tokenManager.getToken(config1)
        val token2 = tokenManager.getToken(config2)
        // Get from cache for client-1
        val token1Again = tokenManager.getToken(config1)

        assertEquals("token-1", token1)
        assertEquals("token-2", token2)
        assertEquals("token-1", token1Again)
        // Should have made 2 requests (one per clientId)
        assertEquals(2, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `should use different cache keys for different tokenUrls`() = runTest {
        val requestCount = AtomicInteger(0)
        val mockClient = createMockClient {
            val count = requestCount.incrementAndGet()
            respond(
                content = """{"access_token":"token-$count","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config1 = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("secret"),
            tokenUrl = "https://auth1.example.com/token"
        )
        val config2 = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("secret"),
            tokenUrl = "https://auth2.example.com/token"
        )

        val token1 = tokenManager.getToken(config1)
        val token2 = tokenManager.getToken(config2)

        assertEquals("token-1", token1)
        assertEquals("token-2", token2)
        // Should have made 2 requests (one per tokenUrl)
        assertEquals(2, requestCount.get())

        tokenManager.close()
    }

    @Test
    fun `close should close httpClient`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"access_token":"test-token","token_type":"Bearer","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json()
            }
        }

        val tokenManager = OAuth2TokenManager(mockClient)
        val config = DestinationAuthConfig.OAuth2(
            clientId = "client-id",
            clientSecret = Secret("client-secret"),
            tokenUrl = "https://auth.example.com/token"
        )

        tokenManager.getToken(config)
        // Invalidate so the next call will need to make an HTTP request
        tokenManager.invalidateToken(config)
        tokenManager.close()

        // After close, attempting to fetch a new token should fail
        var exceptionThrown = false
        try {
            tokenManager.getToken(config)
        } catch (e: Exception) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown, "Expected exception after closing token manager")
    }

    private fun createMockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
        install(ContentNegotiation) {
            json()
        }
    }
}
