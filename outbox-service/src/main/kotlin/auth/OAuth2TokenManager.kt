package org.nxtspec.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.nxtspec.DestinationAuthConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth2 token response from authorization server.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null
)

/**
 * Cached token with expiration timestamp.
 */
internal data class CachedToken(
    val token: String,
    val expiresAt: Long
)

/**
 * Manages OAuth2 tokens with caching and thread-safe refresh.
 *
 * Features:
 * - Automatic token caching
 * - Mutex-based refresh to prevent thundering herd
 * - 30-second buffer before token expiry
 */
class OAuth2TokenManager(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
) {
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val mutexMap = ConcurrentHashMap<String, Mutex>()

    /**
     * Gets an access token for the given OAuth2 configuration.
     * Returns cached token if still valid, otherwise fetches a new one.
     *
     * @param config The OAuth2 configuration
     * @return The access token string
     */
    suspend fun getToken(config: DestinationAuthConfig.OAuth2): String {
        val cacheKey = "${config.clientId}@${config.tokenUrl}"

        // Check cache first
        tokenCache[cacheKey]?.let { cached ->
            // Return if token still valid (with 30s buffer)
            if (System.currentTimeMillis() < cached.expiresAt - TOKEN_EXPIRY_BUFFER_MS) {
                return cached.token
            }
        }

        // Use mutex to prevent thundering herd on token refresh
        val mutex = mutexMap.getOrPut(cacheKey) { Mutex() }
        return mutex.withLock {
            // Double-check after acquiring lock
            tokenCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() < cached.expiresAt - TOKEN_EXPIRY_BUFFER_MS) {
                    return@withLock cached.token
                }
            }

            val response: TokenResponse = httpClient.submitForm(
                url = config.tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "client_credentials")
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret.reveal())
                    config.scope?.let { append("scope", it) }
                    config.extraParams.forEach { (k, v) -> append(k, v) }
                }
            ).body()

            val expiresAt = System.currentTimeMillis() + (response.expiresIn ?: DEFAULT_TOKEN_TTL_SECONDS) * 1000
            val cached = CachedToken(response.accessToken, expiresAt)
            tokenCache[cacheKey] = cached
            cached.token
        }
    }

    /**
     * Invalidates the cached token for the given configuration.
     * Useful when a 401 response indicates the token is no longer valid.
     *
     * @param config The OAuth2 configuration
     */
    fun invalidateToken(config: DestinationAuthConfig.OAuth2) {
        val cacheKey = "${config.clientId}@${config.tokenUrl}"
        tokenCache.remove(cacheKey)
    }

    /**
     * Closes the HTTP client.
     */
    fun close() {
        httpClient.close()
    }

    companion object {
        private const val TOKEN_EXPIRY_BUFFER_MS = 30_000L
        private const val DEFAULT_TOKEN_TTL_SECONDS = 3600L
    }
}
