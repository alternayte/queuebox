package org.nxtspec.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.util.*
import org.nxtspec.InboxAuthConfig
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

        val token = header.removePrefix("Bearer ").trim()
        return if (secureCompare(token, config.token)) {
            AuthResult.Success
        } else {
            AuthResult.Failure("Invalid bearer token", HttpStatusCode.Unauthorized)
        }
    }

    private fun validateApiKey(request: ApplicationRequest, config: InboxAuthConfig.ApiKey): AuthResult {
        val key = request.headers[config.headerName]
            ?: return AuthResult.Failure("Missing ${config.headerName} header", HttpStatusCode.Unauthorized)

        return if (secureCompare(key, config.key)) {
            AuthResult.Success
        } else {
            AuthResult.Failure("Invalid API key", HttpStatusCode.Unauthorized)
        }
    }

    private fun validateHmac(request: ApplicationRequest, config: InboxAuthConfig.HmacSignature): AuthResult {
        val signature = request.headers[config.headerName]
            ?: return AuthResult.Failure("Missing ${config.headerName} header", HttpStatusCode.Unauthorized)

        // Timestamp validation for replay attack prevention
        config.timestampHeader?.let { tsHeader ->
            val timestampStr = request.headers[tsHeader]
                ?: return AuthResult.Failure("Missing timestamp header", HttpStatusCode.Unauthorized)

            val timestamp = timestampStr.toLongOrNull()
                ?: return AuthResult.Failure("Invalid timestamp format", HttpStatusCode.Unauthorized)

            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - timestamp) > config.timestampTolerance) {
                return AuthResult.Failure("Request timestamp expired", HttpStatusCode.Unauthorized)
            }
        }

        // Get raw body bytes from attributes (must be stored by route handler)
        val bodyBytes = request.call.attributes.getOrNull(RawBodyKey)
            ?: return AuthResult.Failure(
                "Cannot verify signature - body not available",
                HttpStatusCode.InternalServerError
            )

        val expectedSignature = computeHmac(bodyBytes, config.secret, config.algorithm, config.signaturePrefix)
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
     * Constant-time string comparison to prevent timing attacks.
     */
    internal fun secureCompare(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
