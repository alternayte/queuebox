package org.nxtspec.auth

import org.nxtspec.Secret
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import io.ktor.util.*
import io.mockk.every
import io.mockk.mockk
import org.nxtspec.InboxAuthConfig
import org.nxtspec.SignaturePayloadFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxAuthValidatorTest {

    private val validator = InboxAuthValidator()

    @Test
    fun `bearer - valid token returns success`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = Secret("secret-token"))
        val request = mockRequest(headers = mapOf("Authorization" to "Bearer secret-token"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `bearer - invalid token returns failure`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = Secret("secret-token"))
        val request = mockRequest(headers = mapOf("Authorization" to "Bearer wrong-token"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid bearer token", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `bearer - missing header returns failure`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = Secret("secret-token"))
        val request = mockRequest()

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Missing Authorization header", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - valid key returns success`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret("my-api-key"))
        val request = mockRequest(headers = mapOf("X-API-Key" to "my-api-key"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `api key - invalid key returns failure`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret("my-api-key"))
        val request = mockRequest(headers = mapOf("X-API-Key" to "wrong-key"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid API key", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - missing header returns failure`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = Secret("my-api-key"))
        val request = mockRequest()

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Missing X-API-Key header", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - custom header name works`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-Custom-Auth", key = Secret("custom-key"))
        val request = mockRequest(headers = mapOf("X-Custom-Auth" to "custom-key"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `hmac - valid signature returns success`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val expectedSignature = validator.computeHmac(body, secret, "HmacSHA256", "sha256=")

        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256="
        )

        val request = mockRequestWithBody(
            headers = mapOf("X-Signature" to expectedSignature),
            body = body
        )

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `hmac - invalid signature returns failure`() = testApplication {
        val config = InboxAuthConfig.HmacSignature(
            secret = Secret("webhook-secret"),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256="
        )

        val body = """{"data":"test"}""".toByteArray()
        val request = mockRequestWithBody(
            headers = mapOf("X-Signature" to "sha256=invalid-signature"),
            body = body
        )

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid signature", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `hmac - missing signature header returns failure`() = testApplication {
        val config = InboxAuthConfig.HmacSignature(
            secret = Secret("webhook-secret"),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256="
        )

        val request = mockRequestWithBody(body = """{"data":"test"}""".toByteArray())

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Missing X-Signature header", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `hmac - missing body returns failure`() = testApplication {
        val config = InboxAuthConfig.HmacSignature(
            secret = Secret("webhook-secret"),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256="
        )

        val request = mockRequest(headers = mapOf("X-Signature" to "sha256=somesig"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Cannot verify signature - body not available", result.message)
        assertEquals(HttpStatusCode.InternalServerError, result.statusCode)
    }

    @Test
    fun `hmac - timestamp validation rejects expired request`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val expectedSignature = validator.computeHmac(body, secret, "HmacSHA256", "sha256=")
        val oldTimestamp = (System.currentTimeMillis() - 600000).toString() // 10 minutes ago

        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256=",
            timestampHeader = "X-Timestamp",
            timestampTolerance = 300000 // 5 minutes
        )

        val request = mockRequestWithBody(
            headers = mapOf(
                "X-Signature" to expectedSignature,
                "X-Timestamp" to oldTimestamp
            ),
            body = body
        )

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Request timestamp expired", result.message)
    }

    @Test
    fun `hmac - timestamp validation accepts valid timestamp`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val currentTimestamp = System.currentTimeMillis().toString()
        val expectedSignature = validator.computeHmac(
            "$currentTimestamp.".toByteArray(Charsets.UTF_8) + body,
            secret,
            "HmacSHA256",
            "sha256="
        )

        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256=",
            timestampHeader = "X-Timestamp",
            timestampTolerance = 300000
        )

        val request = mockRequestWithBody(
            headers = mapOf(
                "X-Signature" to expectedSignature,
                "X-Timestamp" to currentTimestamp
            ),
            body = body
        )

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `hmac - SHA512 algorithm works`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val expectedSignature = validator.computeHmac(body, secret, "HmacSHA512", "sha512=")

        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA512",
            signaturePrefix = "sha512="
        )

        val request = mockRequestWithBody(
            headers = mapOf("X-Signature" to expectedSignature),
            body = body
        )

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `secure compare - same strings return true`() {
        val result = validator.secureCompare("hello", "hello")
        assertEquals(true, result)
    }

    @Test
    fun `secure compare - different strings return false`() {
        val result = validator.secureCompare("hello", "world")
        assertEquals(false, result)
    }

    @Test
    fun `secure compare - different length strings return false`() {
        val result = validator.secureCompare("short", "longer")
        assertEquals(false, result)
    }

    // F-036: the Authorization header must carry the "Bearer" scheme, compared case insensitively.
    @Test
    fun `bearer - scheme parsing table`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = Secret("secret-token"))
        val cases = listOf(
            "Bearer secret-token" to true,
            "bearer secret-token" to true,
            "BEARER secret-token" to true,
            "secret-token" to false,
            "Basic secret-token" to false,
            "" to false
        )

        for ((header, expectSuccess) in cases) {
            val result = validator.validate(mockRequest(mapOf("Authorization" to header)), config)
            if (expectSuccess) {
                assertIs<AuthResult.Success>(result, "expected success for header '$header'")
            } else {
                assertIs<AuthResult.Failure>(result, "expected failure for header '$header'")
                assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
            }
        }
    }

    // F-035: a captured request with a fresh timestamp must fail on the signature.
    @Test
    fun `hmac - replay with an updated timestamp is rejected`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val capturedTimestamp = System.currentTimeMillis() - 1000
        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256=",
            timestampHeader = "X-Timestamp",
            timestampTolerance = 300000
        )

        val capturedSignature = validator.computeHmac(
            "$capturedTimestamp.".toByteArray(Charsets.UTF_8) + body,
            secret,
            "HmacSHA256",
            "sha256="
        )

        // The original request passes.
        val original = mockRequestWithBody(
            headers = mapOf(
                "X-Signature" to capturedSignature,
                "X-Timestamp" to capturedTimestamp.toString()
            ),
            body = body
        )
        assertIs<AuthResult.Success>(validator.validate(original, config))

        // The replay uses a fresh timestamp inside the tolerance window.
        val replayTimestamp = System.currentTimeMillis()
        val replay = mockRequestWithBody(
            headers = mapOf(
                "X-Signature" to capturedSignature,
                "X-Timestamp" to replayTimestamp.toString()
            ),
            body = body
        )

        val result = validator.validate(replay, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid signature", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `hmac - body only format signs the body alone`() = testApplication {
        val secret = "webhook-secret"
        val body = """{"data":"test"}""".toByteArray()
        val config = InboxAuthConfig.HmacSignature(
            secret = Secret(secret),
            headerName = "X-Signature",
            algorithm = "HmacSHA256",
            signaturePrefix = "sha256=",
            timestampHeader = "X-Timestamp",
            timestampTolerance = 300000,
            signaturePayloadFormat = SignaturePayloadFormat.BODY
        )

        val request = mockRequestWithBody(
            headers = mapOf(
                "X-Signature" to validator.computeHmac(body, secret, "HmacSHA256", "sha256="),
                "X-Timestamp" to System.currentTimeMillis().toString()
            ),
            body = body
        )

        assertIs<AuthResult.Success>(validator.validate(request, config))
    }

    // F-037: the comparison uses MessageDigest.isEqual over SHA-256 digests.
    @Test
    fun `secure compare - behaviour is unchanged for equal and unequal inputs`() {
        assertTrue(validator.secureCompare("hello", "hello"))
        assertTrue(validator.secureCompare("", ""))
        assertFalse(validator.secureCompare("hello", "world"))
        assertFalse(validator.secureCompare("short", "longer"))
        assertFalse(validator.secureCompare("hello", "hellO"))
        assertFalse(validator.secureCompare("", "x"))
    }

    private fun mockRequest(headers: Map<String, String> = emptyMap()): ApplicationRequest {
        val call = mockk<ApplicationCall>()
        val request = mockk<ApplicationRequest>()
        val headersBuilder = Headers.build {
            headers.forEach { (k, v) -> append(k, v) }
        }

        every { request.headers } returns headersBuilder
        every { request.call } returns call
        every { call.attributes.getOrNull(RawBodyKey) } returns null

        return request
    }

    private fun mockRequestWithBody(
        headers: Map<String, String> = emptyMap(),
        body: ByteArray
    ): ApplicationRequest {
        val call = mockk<ApplicationCall>()
        val request = mockk<ApplicationRequest>()
        val headersBuilder = Headers.build {
            headers.forEach { (k, v) -> append(k, v) }
        }

        every { request.headers } returns headersBuilder
        every { request.call } returns call
        every { call.attributes.getOrNull(RawBodyKey) } returns body

        return request
    }
}
