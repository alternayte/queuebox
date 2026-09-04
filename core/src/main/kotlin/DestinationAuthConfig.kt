package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication configuration for outbound HTTP destinations.
 * Adds authentication headers to outgoing requests.
 */
@Serializable
sealed class DestinationAuthConfig {
    /**
     * OAuth2 client credentials flow.
     * Fetches and caches access tokens automatically.
     */
    @Serializable
    @SerialName("oauth2")
    data class OAuth2(
        val clientId: String,
        val clientSecret: Secret,
        val tokenUrl: String,
        val scope: String? = null,
        val extraParams: Map<String, String> = emptyMap()
    ) : DestinationAuthConfig()

    /**
     * HTTP Basic authentication.
     * Encodes credentials as Base64 in Authorization header.
     */
    @Serializable
    @SerialName("basic")
    data class Basic(
        val username: String,
        val password: Secret
    ) : DestinationAuthConfig()

    /**
     * Custom header authentication.
     * Adds a custom header with the specified value.
     */
    @Serializable
    @SerialName("header")
    data class Header(
        val headerName: String = "Authorization",
        val headerValue: Secret
    ) : DestinationAuthConfig()
}
