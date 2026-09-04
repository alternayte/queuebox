package org.nxtspec.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.util.*
import org.nxtspec.InboxAuthConfig
import org.nxtspec.SignaturePayloadFormat
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Result of authentication validation.
 */
sealed class AuthResult {
    data object Success : AuthResult()
    data class Failure(val message: String, val statusCode: HttpStatusCode) : AuthResult()
}

/**
 * Attribute key for storing raw request body bytes.
 * Used for HMAC signature verification.
 */
val RawBodyKey = AttributeKey<ByteArray>("RawBody")

/**
 * Validates incoming requests against configured authentication schemes.
 */
class InboxAuthValidator {

    /**
     * Validates a request against the provided authentication configuration.
     *
     * @param request The incoming HTTP request
     * @param authConfig The authentication configuration to validate against
     * @return AuthResult indicating success or failure with details
     */
    fun validate(request: ApplicationRequest, authConfig: InboxAuthConfig): AuthResult {
        return when (authConfig) {
            is InboxAuthConfig.Bearer -> validateBearer(request, authConfig)
            is InboxAuthConfig.ApiKey -> validateApiKey(request, authConfig)
            is InboxAuthConfig.HmacSignature -> validateHmac(request, authConfig)
        }
    }

    private fun validateBearer(request: ApplicationRequest, config: InboxAuthConfig.Bearer): AuthResult {
        val header = request.headers["Authorization"]
            ?: return AuthResult.Failure("Missing Authorization header", HttpStatusCode.Unauthorized)

        // F-036: parse the header into a scheme and credentials. RFC 7235 makes the scheme
        // case insensitive, and a header without the scheme must not pass.
        val separator = header.indexOf(' ')
        if (separator < 0) {
            return AuthResult.Failure("Invalid Authorization scheme", HttpStatusCode.Unauthorized)
        }
        val scheme = header.substring(0, separator)
        if (!scheme.equals("Bearer", ignoreCase = true)) {
            return AuthResult.Failure("Invalid Authorization scheme", HttpStatusCode.Unauthorized)
        }
        val token = header.substring(separator + 1).trim()
        return if (secureCompare(token, config.token.reveal())) {
            AuthResult.Success
        } else {
            AuthResult.Failure("Invalid bearer token", HttpStatusCode.Unauthorized)
        }
    }

    private fun validateApiKey(request: ApplicationRequest, config: InboxAuthConfig.ApiKey): AuthResult {
        val key = request.headers[config.headerName]
            ?: return AuthResult.Failure("Missing ${config.headerName} header", HttpStatusCode.Unauthorized)

        return if (secureCompare(key, config.key.reveal())) {
            AuthResult.Success
        } else {
            AuthResult.Failure("Invalid API key", HttpStatusCode.Unauthorized)
        }
    }

    private fun validateHmac(request: ApplicationRequest, config: InboxAuthConfig.HmacSignature): AuthResult {
        val signature = request.headers[config.headerName]
            ?: return AuthResult.Failure("Missing ${config.headerName} header", HttpStatusCode.Unauthorized)

        // Timestamp validation for replay attack prevention
        var timestampValue: String? = null
        config.timestampHeader?.let { tsHeader ->
            val timestampStr = request.headers[tsHeader]
                ?: return AuthResult.Failure("Missing timestamp header", HttpStatusCode.Unauthorized)

            val timestamp = timestampStr.toLongOrNull()
                ?: return AuthResult.Failure("Invalid timestamp format", HttpStatusCode.Unauthorized)

            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - timestamp) > config.timestampTolerance) {
                return AuthResult.Failure("Request timestamp expired", HttpStatusCode.Unauthorized)
            }
            timestampValue = timestampStr
        }

        // Get raw body bytes from attributes (must be stored by route handler)
        val bodyBytes = request.call.attributes.getOrNull(RawBodyKey)
            ?: return AuthResult.Failure(
                "Cannot verify signature - body not available",
                HttpStatusCode.InternalServerError
            )

        // F-035: the timestamp must be part of the signed payload. Without it an attacker
        // replays a captured request with a fresh timestamp header.
        val signedPayload = when (config.effectiveSignaturePayloadFormat) {
            SignaturePayloadFormat.BODY -> bodyBytes
            SignaturePayloadFormat.TIMESTAMP_DOT_BODY -> {
                val timestamp = timestampValue
                    ?: return AuthResult.Failure("Missing timestamp header", HttpStatusCode.Unauthorized)
                "$timestamp.".toByteArray(Charsets.UTF_8) + bodyBytes
            }
        }

        val expectedSignature =
            computeHmac(signedPayload, config.secret.reveal(), config.algorithm, config.signaturePrefix)
        return if (secureCompare(signature, expectedSignature)) {
            AuthResult.Success
        } else {
            AuthResult.Failure("Invalid signature", HttpStatusCode.Unauthorized)
        }
    }

    /**
     * Computes HMAC signature of data using the specified algorithm.
     */
    internal fun computeHmac(data: ByteArray, secret: String, algorithm: String, prefix: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
        val hash = mac.doFinal(data)
        return prefix + hash.toHexString()
    }

    /**
     * Constant-time string comparison to prevent timing attacks. See F-037.
     *
     * The values are reduced to SHA-256 digests first. The digests always have the same length,
     * so the comparison does not leak the length of the secret.
     */
    internal fun secureCompare(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestA = digest.digest(a.toByteArray(Charsets.UTF_8))
        digest.reset()
        val digestB = digest.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(digestA, digestB)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
