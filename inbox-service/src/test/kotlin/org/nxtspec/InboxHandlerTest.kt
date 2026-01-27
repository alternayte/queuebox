package org.nxtspec

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboxHandlerTest {

    private lateinit var mockRepository: InboxRepository
    private lateinit var extractor: IdempotencyExtractor
    private lateinit var handler: InboxHandler

    @BeforeEach
    fun setup() {
        mockRepository = mockk()
        extractor = IdempotencyExtractor()
        handler = InboxHandler(mockRepository, extractor)
    }

    @Test
    fun `should return Accepted when message stored successfully`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.orderId"
        )
        val payload = Json.parseToJsonElement("""{ "orderId": "order-123", "data": "test" }""")

        val result = handler.handle("stripe", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Accepted)
        coVerify { mockRepository.store(match { it.idempotencyKey == "order-123" }) }
    }

    @Test
    fun `should return Duplicate when repository detects duplicate`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Duplicate

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "dup-123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.Duplicate)
    }

    @Test
    fun `should return ExtractionFailed when idempotency key path not found`() = runTest {
        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.missing.field"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        assertTrue(result is InboxHandlerResult.ExtractionFailed)
        coVerify(exactly = 0) { mockRepository.store(any()) }
    }

    @Test
    fun `should return StorageFailed when repository errors`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Error("DB connection failed")

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        val result = handler.handle("source", sourceConfig, payload)

        require(result is InboxHandlerResult.StorageFailed)
        assertEquals("DB connection failed", result.reason)
    }

    @Test
    fun `should extract eventType when eventTypePath configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            eventTypePath = "$.type"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "type": "payment.completed" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == "payment.completed" }) }
    }

    @Test
    fun `should set eventType to null when eventTypePath not configured`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "type": "payment.completed" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == null }) }
    }

    @Test
    fun `should set eventType to null when eventTypePath does not match`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id",
            eventTypePath = "$.missing.type"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.eventType == null }) }
    }

    @Test
    fun `should set correct source in message`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123" }""")

        handler.handle("stripe-webhooks", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.source == "stripe-webhooks" }) }
    }

    @Test
    fun `should include payload in stored message`() = runTest {
        coEvery { mockRepository.store(any()) } returns InboxResult.Stored

        val sourceConfig = SourceConfig.Http(
            path = "/webhook",
            idempotencyKeyPath = "$.id"
        )
        val payload = Json.parseToJsonElement("""{ "id": "123", "data": { "nested": "value" } }""")

        handler.handle("source", sourceConfig, payload)

        coVerify { mockRepository.store(match { it.payload == payload }) }
    }
}
