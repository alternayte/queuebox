package org.nxtspec.auth

import org.nxtspec.DestinationAuthConfig
import java.util.Base64

/**
 * Resolves authentication configuration to HTTP headers.
 * Supports OAuth2, Basic Auth, and custom header authentication.
 */
class DestinationAuthResolver(private val tokenManager: OAuth2TokenManager) {
    /**
     * Resolves the authentication configuration to HTTP headers.
     *
     * @param authConfig The authentication configuration, or null for no auth
     * @return Map of header names to values to add to the request
     */
    suspend fun resolveAuthHeaders(authConfig: DestinationAuthConfig?): Map<String, String> {
        if (authConfig == null) return emptyMap()

        return when (authConfig) {
            is DestinationAuthConfig.OAuth2 -> {
                val token = tokenManager.getToken(authConfig)
                mapOf("Authorization" to "Bearer $token")
            }

            is DestinationAuthConfig.Basic -> {
                val credentials = "${authConfig.username}:${authConfig.password.reveal()}"
                val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
                mapOf("Authorization" to "Basic $encoded")
            }

            is DestinationAuthConfig.Header -> {
                mapOf(authConfig.headerName to authConfig.headerValue.reveal())
            }
        }
    }
}
