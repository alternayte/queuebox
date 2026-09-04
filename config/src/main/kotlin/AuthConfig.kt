package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication configuration for incoming inbox webhooks.
 * Validates requests before processing.
 */
@Serializable
sealed class InboxAuthConfig {
    /**
     * Bearer token authentication.
     * Validates Authorization header contains "Bearer <token>".
     */
    @Serializable
    @SerialName("bearer")
    data class Bearer(
        val token: Secret
    ) : InboxAuthConfig()

    /**
     * API key authentication via custom header.
     */
    @Serializable
    @SerialName("api-key")
    data class ApiKey(
        val headerName: String = "X-API-Key",
        val key: Secret
    ) : InboxAuthConfig()

    /**
     * HMAC signature verification for webhook payloads.
     * Computes HMAC of request body and compares to signature header.
     */
    @Serializable
    @SerialName("hmac")
    data class HmacSignature(
        val secret: Secret,
        val headerName: String = "X-Signature",
        val algorithm: String = "HmacSHA256",
        val signaturePrefix: String = "sha256=",
        val timestampHeader: String? = null,
        val timestampTolerance: Long = 300000
    ) : InboxAuthConfig()
}
