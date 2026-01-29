package org.nxtspec.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import io.ktor.util.*
import io.mockk.every
import io.mockk.mockk
import org.nxtspec.InboxAuthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InboxAuthValidatorTest {

    private val validator = InboxAuthValidator()

    @Test
    fun `bearer - valid token returns success`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = "secret-token")
        val request = mockRequest(headers = mapOf("Authorization" to "Bearer secret-token"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `bearer - invalid token returns failure`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = "secret-token")
        val request = mockRequest(headers = mapOf("Authorization" to "Bearer wrong-token"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid bearer token", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `bearer - missing header returns failure`() = testApplication {
        val config = InboxAuthConfig.Bearer(token = "secret-token")
        val request = mockRequest()

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Missing Authorization header", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - valid key returns success`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = "my-api-key")
        val request = mockRequest(headers = mapOf("X-API-Key" to "my-api-key"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Success>(result)
    }

    @Test
    fun `api key - invalid key returns failure`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = "my-api-key")
        val request = mockRequest(headers = mapOf("X-API-Key" to "wrong-key"))

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid API key", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - missing header returns failure`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-API-Key", key = "my-api-key")
        val request = mockRequest()

        val result = validator.validate(request, config)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Missing X-API-Key header", result.message)
        assertEquals(HttpStatusCode.Unauthorized, result.statusCode)
    }

    @Test
    fun `api key - custom header name works`() = testApplication {
        val config = InboxAuthConfig.ApiKey(headerName = "X-Custom-Auth", key = "custom-key")
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
            secret = secret,
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
            secret = "webhook-secret",
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
            secret = "webhook-secret",
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
            secret = "webhook-secret",
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
            secret = secret,
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
        val expectedSignature = validator.computeHmac(body, secret, "HmacSHA256", "sha256=")
        val currentTimestamp = System.currentTimeMillis().toString()

        val config = InboxAuthConfig.HmacSignature(
            secret = secret,
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
            secret = secret,
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
