package org.nxtspec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The exact bytes that an HMAC signature covers. See F-035.
 *
 * `BODY` signs the request body only. A captured request can then be replayed with a fresh
 * timestamp header, because the timestamp is not signed.
 *
 * `TIMESTAMP_DOT_BODY` signs `timestamp + "." + body`, as Stripe and GitHub do. A replay with a
 * fresh timestamp fails, because the signature no longer matches.
 */
@Serializable
enum class SignaturePayloadFormat {
    @SerialName("body")
    BODY,

    @SerialName("timestamp-dot-body")
    TIMESTAMP_DOT_BODY
}

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
        val timestampTolerance: Long = 300000,
        /**
         * The bytes that the signature covers. The default follows `timestampHeader`: a
         * configured timestamp header means the timestamp is signed. See F-035.
         */
        val signaturePayloadFormat: SignaturePayloadFormat? = null
    ) : InboxAuthConfig() {
        /** The format that applies, after the default is resolved. */
        val effectiveSignaturePayloadFormat: SignaturePayloadFormat
            get() = signaturePayloadFormat
                ?: if (timestampHeader != null) {
                    SignaturePayloadFormat.TIMESTAMP_DOT_BODY
                } else {
                    SignaturePayloadFormat.BODY
                }
    }
}
